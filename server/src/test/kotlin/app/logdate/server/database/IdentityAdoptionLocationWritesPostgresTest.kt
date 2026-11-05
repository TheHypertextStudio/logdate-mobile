package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.database.support.createTestAccount
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.sync.DbLocationHistoryRepository
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.LocationHistoryUpload
import app.logdate.shared.model.sync.SpaceMigrationPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionLocationWritesPostgresTest {
    @Test
    fun heldSourceCannotUpload() =
        withFixture {
            hold()
            refuse(IdentityFenceOutcome.HELD) { repository.upload(id, listOf(record("new"))) }
        }

    @Test
    fun heldSourceCannotDelete() =
        withFixture {
            hold()
            val current = repository.changes(id, 0, 100).records.single()
            val deletion =
                record("kept").copy(
                    payload = null,
                    deleted = true,
                    deviceVersion = 2,
                    expectedServerVersion = current.serverVersion,
                )
            refuse(IdentityFenceOutcome.HELD) { repository.upload(id, listOf(deletion)) }
        }

    @Test
    fun heldBatchCannotAllocateVersionsOrPartiallyPersist() =
        withFixture {
            hold()
            refuse(IdentityFenceOutcome.HELD) { repository.upload(id, listOf(record("one"), record("two"))) }
        }

    @Test
    fun staleEpochCannotWrite() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuse(IdentityFenceOutcome.CHANGED) { repository.upload(id, listOf(record("new"))) }
        }

    @Test
    fun deletedOriginCannotRecreateLocationRows() =
        withFixture {
            assertTrue(PostgreSQLAccountRepository().deleteAccount(owner.id))
            refuse(IdentityFenceOutcome.DELETED) { repository.upload(id, listOf(record("new"))) }
        }

    @Test
    fun missingOriginCannotAllocateAVersion() =
        withFixture {
            val missing = Uuid.random().toJavaUUID()
            assertEquals(
                IdentityFenceOutcome.DELETED,
                assertFailsWith<IdentityWriteRefusedException> { repository.upload(missing, listOf(record("new"))) }.outcome,
            )
            assertNull(version(missing))
            assertTrue(repository.changes(missing, 0, 100).records.isEmpty())
        }

    @Test
    fun retiredIdentityCannotWrite() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, " +
                        "identity_epoch, retired_at) VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, owner.did)
                    it.setObject(2, id)
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuse(IdentityFenceOutcome.CHANGED) { repository.upload(id, listOf(record("new"))) }
        }

    @Test
    fun switchedStorageCannotAcceptLegacyLocationsAtEpochZero() =
        withFixture {
            val claim = migration(null)
            assertTrue(
                PostgreSQLSpaceMigrationStore().replaceAndSwitch(
                    claim,
                    claim.next(Clock.System.now()) { copy(phase = SpaceMigrationPhase.SWITCHED) },
                    AccountStorageMode.LEGACY,
                    AccountStorageMode.PERSONAL_SPACE_V1,
                ),
            )
            refuse(IdentityFenceOutcome.CHANGED) { repository.upload(id, listOf(record("new"))) }
        }

    @Test
    fun legacyStorageHoldCannotBeBypassed() =
        withFixture {
            migration(Clock.System.now())
            refuse(IdentityFenceOutcome.HELD) { repository.upload(id, listOf(record("new"))) }
        }

    @Test
    fun expiredStorageHoldLetsLegacyLocationsResume() =
        withFixture {
            migration(Clock.System.now() - 31.minutes)
            repository.upload(id, listOf(record("new")))
            assertEquals(
                setOf("kept", "new"),
                repository
                    .changes(id, 0, 100)
                    .records
                    .map { it.id }
                    .toSet(),
            )
        }

    @Test
    fun authorizationBeforeAdoptionHoldCannotCompleteAfterIt() =
        withFixture {
            coroutineScope {
                val authorized = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                val writer =
                    async(Dispatchers.IO) {
                        assertEquals(AccountStorageMode.LEGACY, PostgreSQLAccountRepository().findById(owner.id)?.storageMode)
                        authorized.complete(Unit)
                        resume.await()
                        runCatching { repository.upload(id, listOf(record("new"))) }
                    }
                try {
                    withTimeout(5_000) { authorized.await() }
                    hold()
                } finally {
                    withContext(NonCancellable) { resume.complete(Unit) }
                }
                assertEquals(
                    IdentityFenceOutcome.HELD,
                    assertIs<IdentityWriteRefusedException>(
                        withTimeout(5_000) {
                            writer.await()
                        }.exceptionOrNull(),
                    ).outcome,
                )
                assertEquals(listOf("kept"), repository.changes(id, 0, 100).records.map { it.id })
                assertEquals(1L, version(id))
            }
        }

    @Test
    fun accountLockPrecedesVersionRowLock() =
        withFixture {
            coroutineScope {
                source.connection.use { control ->
                    control.autoCommit = false
                    control.prepareStatement("SELECT version FROM location_history_versions WHERE user_id = ? FOR UPDATE").use {
                        it.setObject(1, id)
                        it.executeQuery().close()
                    }
                    val writer = async(Dispatchers.IO) { runCatching { repository.upload(id, listOf(record("new"))) } }
                    try {
                        withTimeout(5_000) { while (waitingOnVersion() == 0) delay(20) }
                        val failure =
                            assertFailsWith<SQLException> {
                                source.blueskyTransaction {
                                    prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT").use {
                                        it.setObject(1, id)
                                        it.executeQuery().close()
                                    }
                                }
                            }
                        assertEquals("55P03", failure.sqlState)
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            control.rollback()
                            writer.await()
                        }
                    }
                    writer.await().getOrThrow()
                    assertEquals(2L, version(id))
                }
            }
        }

    @Test
    fun anotherLegacyOwnerCanStillUploadWhileTheSourceIsHeld() =
        withFixture {
            val other = Uuid.random().toJavaUUID()
            createTestAccount(other)
            hold()
            repository.upload(other, listOf(record("other")))
            assertEquals(listOf("other"), repository.changes(other, 0, 100).records.map { it.id })
            assertEquals(listOf("kept"), repository.changes(id, 0, 100).records.map { it.id })
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val repository = DbLocationHistoryRepository()

        suspend fun hold() {
            val adoption = PostgreSQLIdentityAdoptionRepository(source)
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    adoption.begin(
                        IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                        Clock.System.now(),
                    ),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now()),
            )
        }

        suspend fun migration(heldAt: Instant?): SpaceMigration {
            val now = Clock.System.now()
            val claim = SpaceMigration(id, "location-move", SpaceMigrationPhase.UPLOADING, heldAt ?: now, now, holdStartedAt = heldAt)
            assertTrue(PostgreSQLSpaceMigrationStore().replace(null, claim))
            return claim
        }

        suspend fun version(account: UUID): Long? =
            source.blueskyTransaction {
                prepareStatement("SELECT version FROM location_history_versions WHERE user_id = ?").use {
                    it.setObject(1, account)
                    it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
                }
            }

        suspend fun refuse(
            outcome: IdentityFenceOutcome,
            action: () -> Unit,
        ) {
            val before = repository.changes(id, 0, 100)
            val beforeVersion = version(id)
            assertEquals(outcome, assertFailsWith<IdentityWriteRefusedException> { action() }.outcome)
            assertEquals(before, repository.changes(id, 0, 100))
            assertEquals(beforeVersion, version(id))
        }

        suspend fun waitingOnVersion(): Int =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' " +
                        "AND query LIKE '%location_history_versions%' AND query NOT LIKE '%pg_stat_activity%'",
                ).use {
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getInt(1)
                    }
                }
            }
    }

    private fun withFixture(action: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Location owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                    createdAt = Clock.System.now(),
                )
            assertTrue(PostgreSQLAccountRepository().create(owner))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, " +
                        "operation_json, created_at, publication_attempted) " +
                        "VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
                ).use {
                    it.setObject(1, Uuid.random().toJavaUUID())
                    it.setObject(2, owner.id.toJavaUUID())
                    it.setString(3, owner.did)
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            val fixture = Fixture(source, owner)
            fixture.repository.upload(fixture.id, listOf(record("kept")))
            fixture.action()
        }

    private companion object {
        fun record(id: String) = LocationHistoryUpload(id, "observation", "LDSE2:synthetic", deviceId = "device", deviceVersion = 1)
    }
}
