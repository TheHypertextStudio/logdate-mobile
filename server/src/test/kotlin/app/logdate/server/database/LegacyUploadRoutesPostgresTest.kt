@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LegacyObjectUploads
import app.logdate.server.logdate.LogDateBlobFileWriteRequest
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.asLogDateCollectionsRepository
import app.logdate.server.routes.support.backupUploadMultipartContent
import app.logdate.server.routes.support.mediaUploadMultipartContent
import app.logdate.server.routes.syncRoutes
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.MediaUploadResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class LegacyUploadRoutesPostgresTest {
    @Test fun mediaAndBackupRoutesJournalBeforeStorageAndCompleteAfterRegistration() = exercise(Scenario.NORMAL)

    @Test fun mediaAndBackupRoutesRefuseAFenceWithoutStartingStorage() = exercise(Scenario.HELD)

    @Test fun mediaAndBackupRoutesCleanLateWritesAfterAccountDeletion() = exercise(Scenario.DELETED)

    @Test fun aConcurrentMediaReceiptWinnerKeepsItsObjectAndRetiresOnlyTheCandidate() = exercise(Scenario.RECEIPT_LOSER)

    private enum class Scenario { NORMAL, HELD, DELETED, RECEIPT_LOSER }

    private fun exercise(scenario: Scenario) {
        val namespaces =
            if (scenario == Scenario.RECEIPT_LOSER) {
                listOf(LogDateBlobNamespace.MEDIA)
            } else {
                listOf(LogDateBlobNamespace.MEDIA, LogDateBlobNamespace.BACKUP)
            }
        for (namespace in namespaces) {
            withBlueskyOAuthDatabase { source ->
                val fixture = Fixture(source, createOwner(source), scenario)
                fixture.operation = fixture.begin()
                if (scenario == Scenario.HELD) assertIs<IdentityAdoptionOutcome.Accepted<*>>(fixture.hold())
                testApplication {
                    fixture.configure(this)
                    val endpoint = if (namespace == LogDateBlobNamespace.MEDIA) "media" else "backups"
                    val response =
                        client.post("/api/v1/$endpoint") {
                            bearerAuth(fixture.token)
                            if (scenario == Scenario.RECEIPT_LOSER) {
                                header("X-LogDate-Upload-Intent", "00000000-0000-4000-8000-000000000001")
                            }
                            val bytes = "Synthetic upload".encodeToByteArray()
                            setBody(
                                if (namespace == LogDateBlobNamespace.MEDIA) {
                                    mediaUploadMultipartContent(
                                        if (scenario == Scenario.RECEIPT_LOSER) "loser-entry" else "entry",
                                        "synthetic.bin",
                                        "application/octet-stream",
                                        bytes,
                                    )
                                } else {
                                    backupUploadMultipartContent("synthetic-device", "{}", bytes)
                                },
                            )
                        }
                    val expected =
                        when (scenario) {
                            Scenario.NORMAL, Scenario.RECEIPT_LOSER -> HttpStatusCode.Created
                            Scenario.HELD -> HttpStatusCode.Conflict
                            Scenario.DELETED -> HttpStatusCode.Unauthorized
                        }
                    assertEquals(expected, response.status)
                    assertEquals(if (scenario == Scenario.HELD) 0 else 1, fixture.writes)
                    val phase =
                        when (scenario) {
                            Scenario.NORMAL -> listOf("ATTACHED")
                            Scenario.HELD -> emptyList()
                            Scenario.DELETED, Scenario.RECEIPT_LOSER -> listOf("RETIRED")
                        }
                    assertEquals(phase, fixture.phases())
                    if (scenario == Scenario.DELETED) assertEquals(emptyList(), fixture.memory.listOwnerBlobPaths(fixture.id))
                    if (scenario == Scenario.RECEIPT_LOSER) {
                        assertEquals(listOf(fixture.winnerPath), fixture.memory.listOwnerBlobPaths(fixture.id))
                        val receipt = Json.decodeFromString<MediaUploadResponse>(response.bodyAsText())
                        assertEquals("winner-entry", receipt.contentId)
                        assertEquals(1L, receipt.uploadedAt)
                    }
                }
            }
        }
    }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val scenario: Scenario,
    ) {
        val id = owner.id.toJavaUUID()
        val accounts = PostgreSQLAccountRepository()
        val memory = InMemoryLogDateBlobStorage()
        val tokens = JwtTokenService("synthetic-upload-route-test")
        val token = tokens.generateAccessToken(owner.id.toString())
        val adoption = PostgreSQLIdentityAdoptionRepository(source)
        lateinit var operation: IdentityAdoptionOperation
        val media = PostgreSQLLogDateMediaRepository()
        var winnerPath: String? = null
        var writes = 0
        val storage =
            object : LogDateBlobStorage by memory {
                override fun putBlob(request: LogDateBlobWriteRequest): String {
                    beforeWrite(request)
                    return memory.putBlob(request)
                }

                override fun putBlobFile(request: LogDateBlobFileWriteRequest): String {
                    beforeWrite()
                    return memory.putBlobFile(request)
                }
            }

        fun beforeWrite(request: LogDateBlobWriteRequest? = null) {
            writes++
            if (scenario == Scenario.RECEIPT_LOSER) {
                val candidate = requireNotNull(request)
                val path = memory.putBlob(candidate.copy(blobId = "synthetic-receipt-winner"))
                winnerPath = path
                media.upsertMedia(
                    id,
                    LogDateMedia(
                        candidate.blobId.dropLast(37),
                        "winner-entry",
                        id,
                        "synthetic.bin",
                        candidate.contentType,
                        candidate.bytes.size.toLong(),
                        ByteArray(0),
                        path,
                        1,
                        0,
                        DeviceId("synthetic-device"),
                    ),
                )
            } else if (scenario == Scenario.DELETED) {
                assertTrue(runBlocking { accounts.deleteAccount(owner.id) })
            } else {
                assertEquals(listOf("WRITING"), phases())
                assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold())
                assertNoIdleTransaction()
            }
        }

        fun configure(builder: ApplicationTestBuilder) {
            builder.application {
                install(ContentNegotiation) { json() }
                routing {
                    route("/api/v1") {
                        syncRoutes(
                            tokenService = tokens,
                            mediaStorage = storage,
                            metrics = SyncMetricsRegistry(),
                            collectionsRepository = InMemorySyncRepository().asLogDateCollectionsRepository(),
                            mediaBlobRepository =
                                CompositeLogDateMediaBlobRepository(
                                    media,
                                    PostgreSQLLogDateAtprotoBlobRepository(),
                                ),
                            backupRepository = PostgreSQLLogDateBackupRepository(),
                            objectUploads = LegacyObjectUploads(PostgreSQLObjectUploads()),
                        )
                    }
                }
            }
        }

        suspend fun begin() =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        fun hold() =
            runBlocking {
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now())
            }

        fun phases(): List<String> =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement("SELECT phase FROM object_upload_intents WHERE account_id = ? ORDER BY id").use {
                        it.setObject(1, id)
                        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                    }
                }
            }

        fun assertNoIdleTransaction() =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'",
                    ).use {
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
    }

    private suspend fun createOwner(source: DataSource): Account {
        val id = Uuid.random()
        val owner =
            Account(
                id,
                id.toHexString(),
                "Synthetic upload owner",
                did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                createdAt = Clock.System.now(),
            )
        assertTrue(PostgreSQLAccountRepository().create(owner))
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, created_at, " +
                    "publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
            ).use {
                it.setObject(1, Uuid.random().toJavaUUID())
                it.setObject(2, id.toJavaUUID())
                it.setString(3, owner.did)
                it.setTimestamp(4, Clock.System.now().timestamp())
                assertEquals(1, it.executeUpdate())
            }
        }
        return owner
    }
}
