package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateMedia
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionObjectCleanupPostgresTest {
    @Test fun aHoldPreventsCandidateCleanupBeforeStorageIo() =
        withFixture {
            val operation = begin()
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(operation))
            var calls = 0
            assertEquals(
                IdentityFenceOutcome.HELD,
                assertFailsWith<IdentityWriteRefusedException> {
                    backups.cleanupUnreferencedObject(id, CANDIDATE) {
                        calls++
                        true
                    }
                }.outcome,
            )
            assertEquals(0, calls)
        }

    @Test fun admittedCandidateCleanupBlocksHoldWithoutAnOpenTransaction() =
        withFixture {
            val operation = begin()
            assertTrue(
                backups.cleanupUnreferencedObject(id, CANDIDATE) {
                    assertNoIdleTransaction()
                    assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold(operation))
                    true
                },
            )
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(operation))
        }

    @Test fun failedCandidateCleanupSurvivesARepositoryRestart() =
        withFixture {
            assertFalse(backups.cleanupUnreferencedObject(id, CANDIDATE) { false })
            val operation = begin()
            assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold(operation))
            assertTrue(PostgreSQLLogDateBackupRepository().cleanupUnreferencedObject(id, CANDIDATE) { true })
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(operation))
        }

    @Test fun cleanupCannotDeleteAPathRegisteredInAnotherStore() =
        withFixture {
            val original = media.upsertMedia(id, record(CANDIDATE))
            var calls = 0
            assertFailsWith<IdentityWriteRefusedException> {
                backups.cleanupUnreferencedObject(id, CANDIDATE) {
                    calls++
                    true
                }
            }
            assertEquals(0, calls)
            assertEquals(original, media.getMedia(id, original.mediaId))
        }

    @Test fun completedCandidatePathCannotReturnAsRegisteredMedia() =
        withFixture {
            assertTrue(backups.cleanupUnreferencedObject(id, CANDIDATE) { true })
            assertFailsWith<IdentityWriteRefusedException> { media.upsertMedia(id, record(CANDIDATE)) }
            assertEquals(null, media.getMedia(id, "media"))
        }

    @Test fun candidateCleanupCannotBorrowANewEpochAfterFailure() =
        withFixture {
            assertFalse(backups.cleanupUnreferencedObject(id, CANDIDATE) { false })
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            var calls = 0
            assertFailsWith<IdentityWriteRefusedException> {
                backups.cleanupUnreferencedObject(id, CANDIDATE) {
                    calls++
                    true
                }
            }
            assertEquals(0, calls)
        }

    @Test fun canceledCandidateCleanupKeepsItsBarrier() =
        withFixture {
            assertFailsWith<CancellationException> {
                backups.cleanupUnreferencedObject(id, CANDIDATE) { throw CancellationException("synthetic cancellation") }
            }
            val operation = begin()
            assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold(operation))
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val backups = PostgreSQLLogDateBackupRepository()
        val media = PostgreSQLLogDateMediaRepository()
        private val adoption = PostgreSQLIdentityAdoptionRepository(source)

        fun record(path: String) = LogDateMedia("media", "note", id, "file", "audio/mp4", 1, byteArrayOf(1), path, 1, 0, DeviceId("device"))

        suspend fun begin() =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        fun hold(operation: IdentityAdoptionOperation) =
            runBlocking {
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now())
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
                    "Cleanup owner",
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

    private companion object {
        const val CANDIDATE = "synthetic/unreferenced-candidate"
    }
}
