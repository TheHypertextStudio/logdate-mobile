package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.routes.syncRoutes
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.DeviceId
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionObjectDeletionPostgresTest {
    @Test fun heldMediaDeletionCannotReachStorage() =
        withFixture {
            hold()
            var calls = 0
            assertEquals(
                IdentityFenceOutcome.HELD,
                assertFailsWith<IdentityWriteRefusedException> {
                    media.deleteMediaWithObject(id, original.mediaId, 99) {
                        calls++
                        true
                    }
                }.outcome,
            )
            assertEquals(0, calls)
            assertEquals(original, media.getMedia(id, original.mediaId))
        }

    @Test fun heldBackupDeletionCannotReachStorage() =
        withFixture {
            hold()
            var calls = 0
            assertEquals(
                IdentityFenceOutcome.HELD,
                assertFailsWith<IdentityWriteRefusedException> {
                    backups.deleteBackupWithObject(id, backup.id) {
                        calls++
                        true
                    }
                }.outcome,
            )
            assertEquals(0, calls)
            assertEquals(backup, backups.getBackup(id, backup.id))
        }

    @Test fun deletionClaimBlocksHoldWithoutKeepingATransactionOpen() =
        withFixture {
            val operation = begin()
            assertTrue(
                media.deleteMediaWithObject(id, original.mediaId, 99) { path ->
                    assertEquals(original.storagePath, path)
                    assertNoIdleTransaction()
                    assertEquals(
                        IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING),
                        attemptHold(operation),
                    )
                    true
                },
            )
            assertNull(media.getMedia(id, original.mediaId))
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                attemptHold(operation),
            )
        }

    @Test fun failedDeletionRemainsClaimedAcrossRepositoryRestart() =
        withFixture {
            assertFalse(backups.deleteBackupWithObject(id, backup.id) { false })
            val operation = begin()
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING),
                attemptHold(operation),
            )
            val restarted = PostgreSQLLogDateBackupRepository()
            assertTrue(
                restarted.deleteBackupWithObject(id, backup.id) { path ->
                    assertEquals(backup.storagePath, path)
                    assertNoIdleTransaction()
                    true
                },
            )
            assertNull(restarted.getBackup(id, backup.id))
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                attemptHold(operation),
            )
        }

    @Test fun replacementCannotRaceAnAdmittedObjectDeletion() =
        withFixture {
            assertTrue(
                media.deleteMediaWithObject(id, original.mediaId, 99) {
                    assertFailsWith<IdentityWriteRefusedException> {
                        PostgreSQLLogDateMediaRepository().upsertMedia(id, original.copy(storagePath = "synthetic/new-copy"))
                    }
                    true
                },
            )
            assertNull(media.getMedia(id, original.mediaId))
        }

    @Test fun aDeletedPhysicalPathCannotBeReusedByANewRecord() =
        withFixture {
            assertTrue(media.deleteMediaWithObject(id, original.mediaId, 99) { true })
            assertFailsWith<IdentityWriteRefusedException> {
                media.upsertMedia(id, original.copy(mediaId = "new-record"))
            }
            assertNull(media.getMedia(id, "new-record"))
        }

    @Test fun ambientTransactionCannotReachObjectStorage() =
        withFixture {
            val accountId = id
            var calls = 0
            assertFailsWith<IdentityLifecycleConflictException> {
                transaction {
                    media.deleteMediaWithObject(accountId, original.mediaId, 99) {
                        calls++
                        true
                    }
                }
            }
            assertEquals(0, calls)
            assertEquals(original, media.getMedia(id, original.mediaId))
        }

    @Test fun rawJdbcContextCannotReachObjectStorage() =
        withFixture {
            var calls = 0
            val result =
                runCatching {
                    withContext(RawJdbcTransactionContext()) {
                        media.deleteMediaWithObject(id, original.mediaId, 99) {
                            calls++
                            true
                        }
                    }
                }
            assertEquals(0, calls)
            assertIs<IdentityLifecycleConflictException>(result.exceptionOrNull())
            assertEquals(original, media.getMedia(id, original.mediaId))
        }

    @Test fun unifiedMediaBoundaryMustPreserveDeletionAdmission() =
        withFixture {
            val unified = CompositeLogDateMediaBlobRepository(media, PostgreSQLLogDateAtprotoBlobRepository())
            hold()
            var calls = 0
            assertFailsWith<IdentityWriteRefusedException> {
                unified.deleteMediaWithObject(id, original.mediaId, 99) {
                    calls++
                    true
                }
            }
            assertEquals(0, calls)
        }

    @Test fun aSharedPhysicalPathCannotBeDeleted() =
        withFixture {
            val other = media.upsertMedia(id, original.copy(mediaId = "shared"))
            var calls = 0
            assertFailsWith<IdentityWriteRefusedException> {
                media.deleteMediaWithObject(id, original.mediaId, 99) {
                    calls++
                    true
                }
            }
            assertEquals(0, calls)
            assertEquals(other, media.getMedia(id, other.mediaId))
            assertEquals(original, media.getMedia(id, original.mediaId))
        }

    @Test fun cancellationRetainsTheDurableDeletionBarrier() =
        withFixture {
            assertFailsWith<CancellationException> {
                backups.deleteBackupWithObject(id, backup.id) { throw CancellationException("synthetic cancellation") }
            }
            val operation = begin()
            assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), attemptHold(operation))
            assertTrue(backups.deleteBackupWithObject(id, backup.id) { true })
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(attemptHold(operation))
        }

    @Test fun pendingDeletionCannotBorrowANewerEpoch() =
        withFixture {
            assertFalse(backups.deleteBackupWithObject(id, backup.id) { false })
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            var calls = 0
            assertFailsWith<IdentityWriteRefusedException> {
                backups.deleteBackupWithObject(id, backup.id, identityEpoch = 1) {
                    calls++
                    true
                }
            }
            assertEquals(0, calls)
            assertEquals(backup, backups.getBackup(id, backup.id))
        }

    @Test fun aClaimedMediaPathCannotBeRegisteredAsABackup() =
        withFixture {
            assertFalse(media.deleteMediaWithObject(id, original.mediaId, 99) { false })
            assertFailsWith<IdentityWriteRefusedException> {
                backups.createBackup(id, backup.copy(id = Uuid.random().toJavaUUID(), storagePath = requireNotNull(original.storagePath)))
            }
            assertEquals(listOf(backup), backups.listBackups(id))
        }

    @Test fun heldMediaHttpDeletionNeverCallsStorage() =
        withFixture {
            hold()
            assertHeldHttp("/media/${original.mediaId}")
        }

    @Test fun heldBackupHttpDeletionNeverCallsStorage() =
        withFixture {
            hold()
            assertHeldHttp("/backups/${backup.id}")
        }

    @Test fun heldBackupRetentionNeverCallsStorage() =
        withFixture {
            hold()
            assertHeldHttp("/ops/backups:purge?keepPerDevice=0", isPost = true)
        }

    @Test fun lostDeleteAcknowledgementCanBeReconciledByConfirmingAbsence() =
        withFixture {
            assertRetryAfterLostDeleteAcknowledgement()
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val media = PostgreSQLLogDateMediaRepository()
        val backups = PostgreSQLLogDateBackupRepository()
        val adoption = PostgreSQLIdentityAdoptionRepository(source)
        val original =
            media.upsertMedia(
                id,
                LogDateMedia(
                    "media",
                    "note",
                    id,
                    "file",
                    "audio/mp4",
                    1,
                    byteArrayOf(1),
                    "synthetic/media",
                    1,
                    0,
                    DeviceId("device"),
                ),
            )
        val backup = backups.createBackup(id, LogDateBackup(Uuid.random().toJavaUUID(), id, "device", "{}", "synthetic/backup", 1, 1))

        suspend fun begin() =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        fun attemptHold(operation: IdentityAdoptionOperation) =
            runBlocking {
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now())
            }

        suspend fun hold() {
            val operation = begin()
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                attemptHold(operation),
            )
        }

        fun assertHeldHttp(
            path: String,
            isPost: Boolean = false,
        ) = testApplication {
            val tokenService = JwtTokenService("synthetic-object-delete-test")
            val token = tokenService.generateAccessToken(owner.id.toString())
            var calls = 0
            val storage = mockk<LogDateBlobStorage>()
            every { storage.deleteBlob(any()) } answers {
                calls++
                true
            }
            every { storage.deleteBlobIfExists(any()) } answers {
                calls++
                Unit
            }
            installRoutes(storage, tokenService)
            val result =
                runCatching {
                    if (isPost) {
                        client.post("/api/v1$path") { bearerAuth(token) }
                    } else {
                        client.delete("/api/v1$path") { bearerAuth(token) }
                    }
                }
            assertEquals(0, calls)
            val response = result.getOrThrow()
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("IDENTITY_WRITE_HELD"))
            assertEquals(original, media.getMedia(id, original.mediaId))
            assertEquals(backup, backups.getBackup(id, backup.id))
        }

        fun ApplicationTestBuilder.installRoutes(
            storage: LogDateBlobStorage,
            tokenService: JwtTokenService,
        ) {
            application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") {
                        syncRoutes(
                            tokenService = tokenService,
                            mediaStorage = storage,
                            metrics = SyncMetricsRegistry(),
                            collectionsRepository = InMemorySyncRepository().asLogDateCollectionsRepository(),
                            mediaBlobRepository = CompositeLogDateMediaBlobRepository(media, PostgreSQLLogDateAtprotoBlobRepository()),
                            backupRepository = backups,
                        )
                    }
                }
            }
        }

        fun assertRetryAfterLostDeleteAcknowledgement() =
            testApplication {
                val tokenService = JwtTokenService("synthetic-retry-test")
                val token = tokenService.generateAccessToken(owner.id.toString())
                val storage = mockk<LogDateBlobStorage>()
                var exists = true
                var calls = 0
                every { storage.deleteBlob(any()) } answers {
                    calls++
                    exists = false
                    false
                }
                every { storage.deleteBlobIfExists(any()) } answers {
                    calls++
                    val existed = exists
                    exists = false
                    if (existed) throw IllegalStateException("synthetic lost acknowledgement")
                }
                installRoutes(storage, tokenService)
                val first = client.delete("/api/v1/media/${original.mediaId}") { bearerAuth(token) }
                assertEquals(HttpStatusCode.InternalServerError, first.status)
                assertFalse(exists)
                assertEquals(original, media.getMedia(id, original.mediaId))
                val second = client.delete("/api/v1/media/${original.mediaId}") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NoContent, second.status)
                assertEquals(2, calls)
                assertNull(media.getMedia(id, original.mediaId))
            }

        fun assertNoIdleTransaction() =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                            "AND state = 'idle in transaction'",
                    ).use {
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val accountId = Uuid.random()
            val owner =
                Account(
                    accountId,
                    accountId.toHexString(),
                    "Object owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            Fixture(source, assertNotNull(PostgreSQLAccountRepository().findById(owner.id))).block()
        }
}
