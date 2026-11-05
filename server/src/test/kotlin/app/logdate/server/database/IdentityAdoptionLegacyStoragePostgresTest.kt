package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.migration.SpaceMigration
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.SpaceMigrationPhase
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionLegacyStoragePostgresTest {
    @Test
    fun heldAccountCannotStartOrReplaceLegacyMigration() =
        withFixture(AccountStorageMode.LEGACY) {
            val original = migration(SpaceMigrationPhase.REVERTED)
            assertTrue(store.replace(null, original))
            hold(begin())
            assertFalse(store.replace(original, original.next(now) { copy(phase = SpaceMigrationPhase.UPLOADING) }))
            assertEquals(original, store.get(owner.id.toJavaUUID()))
        }

    @Test
    fun heldAccountCannotSwitchStorageMode() =
        withFixture(AccountStorageMode.LEGACY) {
            val original = migration(SpaceMigrationPhase.REVERTED)
            assertTrue(store.replace(null, original))
            hold(begin())
            assertFalse(
                store.replaceAndSwitch(
                    original,
                    original.next(now) { copy(phase = SpaceMigrationPhase.SWITCHED) },
                    AccountStorageMode.LEGACY,
                    AccountStorageMode.PERSONAL_SPACE_V1,
                ),
            )
            assertEquals(AccountStorageMode.LEGACY, account().storageMode)
            assertEquals(original, store.get(owner.id.toJavaUUID()))
        }

    @Test
    fun oldMigrationCannotResumeSwitchOrRelabelAfterEpochAdvance() =
        withFixture {
            val original = migration(SpaceMigrationPhase.SWITCHED)
            assertTrue(store.replace(null, original))
            advanceIdentity()
            val updated = original.next(now) { copy(holdStartedAt = now) }
            assertFalse(store.replace(original, updated))
            assertFalse(store.replaceAndSwitch(original, updated, AccountStorageMode.PERSONAL_SPACE_V1, AccountStorageMode.LEGACY))
            assertFalse(store.replace(original.copy(identityEpoch = 1), updated.copy(identityEpoch = 1)))
            assertEquals(original, store.get(owner.id.toJavaUUID()))
            assertEquals(AccountStorageMode.PERSONAL_SPACE_V1, account().storageMode)
            assertFalse(store.cleanupDue(now + 1.days, 10).any { it.accountId == owner.id.toJavaUUID() })
        }

    @Test
    fun persistedCleanupClaimBlocksAdoptionUntilCompletion() =
        withFixture {
            val claim = migration(SpaceMigrationPhase.CLEANED)
            assertTrue(store.replace(null, claim))
            val operation = begin()
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING),
                PostgreSQLIdentityAdoptionRepository(source).transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
            assertTrue(store.replace(claim, claim.next(now) { copy(cleanedAt = now) }))
            hold(operation)
        }

    @Test
    fun erasureWithoutExactStoredClaimPreservesFilesAndMetadata() =
        withFixture {
            val path = media()
            val claim = migration(SpaceMigrationPhase.CLEANED)
            val eraser = PostgreSQLLegacyJournalEraser(storage)
            assertFalse(eraser.erase(claim))
            assertTrue(store.replace(null, claim))
            assertFalse(eraser.erase(claim.copy(version = claim.version + 1)))
            assertFalse(eraser.erase(claim.copy(migrationId = "another-move")))
            assertNotNull(storage.getBlob(path))
            assertEquals(1, mediaCount())
        }

    @Test
    fun erasureCannotBorrowNewEpochOrReviveOldCleanup() =
        withFixture {
            val path = media()
            val claim = migration(SpaceMigrationPhase.CLEANED)
            assertTrue(store.replace(null, claim))
            advanceIdentity()
            val eraser = PostgreSQLLegacyJournalEraser(storage)
            assertFalse(eraser.erase(claim))
            assertFalse(eraser.erase(claim.copy(identityEpoch = 1)))
            assertNotNull(storage.getBlob(path))
            assertEquals(1, mediaCount())
            assertEquals(claim, store.get(owner.id.toJavaUUID()))
        }

    @Test
    fun missingStorageCannotEraseTheOnlyCleanupInventory() =
        withFixture {
            val path = media()
            val claim = migration(SpaceMigrationPhase.CLEANED)
            assertTrue(store.replace(null, claim))
            assertFailsWith<IllegalStateException> { PostgreSQLLegacyJournalEraser(null).erase(claim) }
            assertNotNull(storage.getBlob(path))
            assertEquals(1, mediaCount())
            assertEquals(claim, store.get(owner.id.toJavaUUID()))
        }

    @Test
    fun partialObjectDeletionRemainsClaimedAcrossWorkerRestart() =
        withFixture {
            media("one")
            media("two")
            val claim = migration(SpaceMigrationPhase.CLEANED)
            assertTrue(store.replace(null, claim))
            var calls = 0
            val failing =
                object : LogDateBlobStorage by storage {
                    override fun deleteBlobIfExists(storagePath: String) {
                        if (++calls == 2) error("Synthetic provider failure")
                        storage.deleteBlobIfExists(storagePath)
                    }
                }
            assertFailsWith<IllegalStateException> { PostgreSQLLegacyJournalEraser(failing).erase(claim) }
            assertEquals(1, storage.listOwnerBlobPaths(owner.id.toJavaUUID()).size)
            assertEquals(2, mediaCount())
            val operation = begin()
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING),
                repository.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
            assertTrue(PostgreSQLLegacyJournalEraser(storage).erase(claim))
            assertEquals(0, mediaCount())
            assertTrue(storage.listOwnerBlobPaths(owner.id.toJavaUUID()).isEmpty())
            val completed = claim.next(now) { copy(cleanedAt = now) }
            assertTrue(store.replace(claim, completed))
            assertFalse(PostgreSQLLegacyJournalEraser(storage).erase(claim))
            hold(operation)
        }

    @Test
    fun validCleanupKeepsNoTransactionOpenDuringStorageCalls() =
        withFixture {
            media()
            val claim = migration(SpaceMigrationPhase.CLEANED)
            assertTrue(store.replace(null, claim))
            var openTransactions = -1
            val inspected =
                object : LogDateBlobStorage by storage {
                    override fun deleteBlobIfExists(storagePath: String) {
                        source.connection.use { observer ->
                            observer
                                .prepareStatement(
                                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                                        "AND backend_type = 'client backend' AND pid <> pg_backend_pid() AND xact_start IS NOT NULL",
                                ).use { query ->
                                    query.executeQuery().use { rows ->
                                        rows.next()
                                        openTransactions = rows.getInt(1)
                                    }
                                }
                        }
                        storage.deleteBlobIfExists(storagePath)
                    }
                }
            assertTrue(PostgreSQLLegacyJournalEraser(inspected).erase(claim))
            assertEquals(0, openTransactions)
            assertEquals(0, mediaCount())
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val store = PostgreSQLSpaceMigrationStore()
        val repository = PostgreSQLIdentityAdoptionRepository(source)
        val storage = InMemoryLogDateBlobStorage()

        fun migration(phase: SpaceMigrationPhase) =
            SpaceMigration(
                owner.id.toJavaUUID(),
                "legacy-move",
                phase,
                now,
                now,
                switchedAt = now,
            )

        suspend fun account(): Account = assertNotNull(PostgreSQLAccountRepository().findById(owner.id))

        suspend fun advanceIdentity() =
            source.blueskyTransaction {
                prepareStatement(
                    "UPDATE accounts SET identity_epoch = 1, did = 'did:web:adopted.test', identity_kind = 'external' WHERE id = ?",
                ).use {
                    it.setObject(1, owner.id.toJavaUUID())
                    assertEquals(1, it.executeUpdate())
                }
            }

        suspend fun begin(): IdentityAdoptionOperation =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                repository.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        suspend fun hold(operation: IdentityAdoptionOperation) {
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                repository.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
        }

        fun media(name: String = "media"): String {
            val id = owner.id.toJavaUUID()
            val path =
                storage.putBlob(
                    LogDateBlobWriteRequest(id, LogDateBlobNamespace.MEDIA, name, "a.jpg", "image/jpeg", byteArrayOf(1, 2)),
                )
            PostgreSQLLogDateMediaRepository().upsertMedia(
                id,
                LogDateMedia(
                    mediaId = name,
                    contentId = name,
                    userId = id,
                    fileName = "a.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 2,
                    data = ByteArray(0),
                    storagePath = path,
                    createdAt = 1,
                    version = 0,
                    deviceId = DeviceId("device"),
                ),
            )
            return path
        }

        fun mediaCount(): Int = PostgreSQLLogDateMediaRepository().listAllForUser(owner.id.toJavaUUID()).size
    }

    private fun withFixture(
        mode: AccountStorageMode = AccountStorageMode.PERSONAL_SPACE_V1,
        block: suspend Fixture.() -> Unit,
    ) = withBlueskyOAuthDatabase { source ->
        val id = Uuid.random()
        val owner =
            Account(
                id,
                id.toHexString(),
                "Test account",
                createdAt = Clock.System.now(),
                did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                storageMode = mode,
            )
        assertTrue(PostgreSQLAccountRepository().create(owner))
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                    "created_at, publication_attempted) " +
                    "VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
            ).use {
                it.setObject(1, Uuid.random().toJavaUUID())
                it.setObject(2, id.toJavaUUID())
                it.setString(3, owner.did)
                it.setTimestamp(4, Clock.System.now().timestamp())
                assertEquals(1, it.executeUpdate())
            }
        }
        Fixture(source, assertNotNull(PostgreSQLAccountRepository().findById(id))).block()
    }
}
