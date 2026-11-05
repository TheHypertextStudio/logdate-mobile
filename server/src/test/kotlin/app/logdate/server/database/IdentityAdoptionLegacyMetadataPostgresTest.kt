package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.LogDateAtprotoBlob
import app.logdate.server.logdate.LogDateBackup
import app.logdate.server.logdate.LogDateMedia
import app.logdate.server.logdate.asLogDateMediaRepository
import app.logdate.server.sync.AssociationKey
import app.logdate.server.sync.AssociationRecord
import app.logdate.server.sync.BackupRecord
import app.logdate.server.sync.BackupSyncTable
import app.logdate.server.sync.ContentRecord
import app.logdate.server.sync.DbSyncRepository
import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.JournalRecord
import app.logdate.server.sync.MediaRecord
import app.logdate.shared.model.sync.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityAdoptionLegacyMetadataPostgresTest {
    @Test fun legacySyncMutationsCannotBypassAnAdoptionHold() =
        withFixture {
            transaction { SchemaUtils.create(BackupSyncTable) }
            val repository = DbSyncRepository()
            val device = DeviceId("legacy-device")
            val content = ContentRecord("note", "TEXT", "original", null, null, 1, 1, 0, device)
            val journal = JournalRecord("journal", "Original", "", 1, 1, 0, device)
            val association = AssociationRecord(journal.id, content.id, 1, 0, device)
            val mediaRecord = MediaRecord("media", content.id, id, "file", "audio/mp4", 1, byteArrayOf(1), null, 1, 0, device)
            val backup = BackupRecord(Uuid.random().toJavaUUID(), id, "device", "{}", "synthetic/legacy-backup", 1, 1)
            repository.upsertContent(id, content)
            repository.upsertJournal(id, journal)
            val storedContent = assertNotNull(repository.getContent(id, content.id))
            val storedJournal = assertNotNull(repository.getJournal(id, journal.id))
            repository.upsertAssociations(id, listOf(association))
            val storedMedia = repository.upsertMedia(id, mediaRecord)
            repository.createBackupRecord(id, backup)
            hold()
            val writes: List<() -> Any?> =
                listOf(
                    { repository.upsertContent(id, content.copy(content = "changed")) },
                    { repository.deleteContent(id, content.id, 99) },
                    { repository.upsertJournal(id, journal.copy(title = "Changed")) },
                    { repository.deleteJournal(id, journal.id, 99) },
                    { repository.upsertAssociations(id, listOf(association.copy(createdAt = 99))) },
                    { repository.deleteAssociations(id, listOf(AssociationKey(journal.id, content.id)), 99) },
                    { repository.upsertMedia(id, mediaRecord.copy(data = byteArrayOf(9))) },
                    { repository.deleteMedia(id, mediaRecord.mediaId, 99) },
                    { repository.createBackupRecord(id, backup.copy(id = Uuid.random().toJavaUUID())) },
                    { repository.deleteBackup(id, backup.id) },
                )
            writes.forEach { write ->
                assertEquals(IdentityFenceOutcome.HELD, assertFailsWith<IdentityWriteRefusedException> { write() }.outcome)
                assertEquals(storedContent, repository.getContent(id, content.id))
                assertEquals(storedJournal, repository.getJournal(id, journal.id))
                assertContentEquals(storedMedia.data, assertNotNull(repository.getMedia(id, mediaRecord.mediaId)).data)
                assertEquals(listOf(backup), repository.listBackups(id))
                assertEquals(1L, repository.status(id).associationCount.toLong())
            }
        }

    @Test fun legacySyncCannotWriteAfterEpochAdvance() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val repository = DbSyncRepository()
            val content = ContentRecord("stale", "TEXT", "edit", null, null, 1, 1, 0, DeviceId("device"))
            assertEquals(
                IdentityFenceOutcome.CHANGED,
                assertFailsWith<IdentityWriteRefusedException> { repository.upsertContent(id, content) }.outcome,
            )
            assertEquals(null, repository.getContent(id, content.id))
        }

    @Test fun globalLegacyRetentionSkipsHeldSourceInventory() =
        withFixture {
            val repository = DbSyncRepository()
            repository.upsertContent(id, ContentRecord("deleted", "TEXT", "retained", null, null, 1, 1, 0, DeviceId("device")))
            repository.deleteContent(id, "deleted", 2)
            hold()
            assertEquals(0, repository.purgeTombstonesOlderThan(99).contentPurged)
        }

    @Test fun legacyRetentionCannotEraseAHeldSourceInventory() =
        withFixture {
            val repository = DbSyncRepository()
            val content = ContentRecord("deleted", "TEXT", "retained", null, null, 1, 1, 0, DeviceId("device"))
            repository.upsertContent(id, content)
            repository.deleteContent(id, content.id, 2)
            hold()
            assertEquals(
                IdentityFenceOutcome.HELD,
                assertFailsWith<IdentityWriteRefusedException> { repository.purgeTombstones(id, 99) }.outcome,
            )
            assertEquals(0, repository.purgeTombstonesOlderThan(99).contentPurged)
            source.blueskyTransaction {
                prepareStatement("SELECT content FROM sync_content WHERE user_id = ? AND id = ?").use {
                    it.setObject(1, id)
                    it.setString(2, content.id)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals("retained", rows.getString(1))
                    }
                }
            }
        }

    @Test fun heldMediaCannotChange() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { media.upsertMedia(id, originalMedia.copy(data = byteArrayOf(9))) }
        }

    @Test fun heldMediaCannotBecomeATombstone() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { media.deleteMedia(id, originalMedia.mediaId, 99) }
        }

    @Test fun heldBackupCannotBeCreated() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { backups.createBackup(id, originalBackup.copy(id = Uuid.random().toJavaUUID())) }
        }

    @Test fun heldBackupCannotBeDeleted() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { backups.deleteBackup(id, originalBackup.id) }
        }

    @Test fun heldAtprotoBlobCannotBeReboundToAnotherPath() =
        withFixture {
            hold()
            refuses(IdentityFenceOutcome.HELD) { blobs.upsertBlob(id, originalBlob.copy(storagePath = "synthetic/changed")) }
        }

    @Test fun metadataCannotBorrowAnotherEpoch() =
        withFixture {
            refuses(IdentityFenceOutcome.CHANGED) { media.upsertMedia(id, originalMedia.copy(data = byteArrayOf(9)), identityEpoch = 1) }
        }

    @Test fun missingEpochCannotWriteAfterAuthorityAdvances() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { backups.deleteBackup(id, originalBackup.id) }
            val changed = media.upsertMedia(id, originalMedia.copy(data = byteArrayOf(9)), identityEpoch = 1)
            assertContentEquals(byteArrayOf(9), changed.data)
        }

    @Test fun legacyAdapterCannotSilentlyDropASuppliedEpoch() =
        withFixture {
            val repository = InMemorySyncRepository().asLogDateMediaRepository()
            assertFailsWith<IdentityWriteRefusedException> { repository.upsertMedia(id, originalMedia, identityEpoch = 1) }
            assertEquals(null, repository.getMedia(id, originalMedia.mediaId))
        }

    @Test fun accountLockPrecedesMediaRowLock() =
        withFixture {
            coroutineScope {
                source.connection.use { control ->
                    control.autoCommit = false
                    control.prepareStatement("SELECT media_id FROM logdate_media_records WHERE user_id = ? FOR UPDATE").use {
                        it.setObject(1, id)
                        it.executeQuery().close()
                    }
                    val writer = async(Dispatchers.IO) { runCatching { media.upsertMedia(id, originalMedia.copy(data = byteArrayOf(9))) } }
                    try {
                        withTimeout(5_000) { while (waitingOnMedia() == 0) delay(20) }
                        val refusal =
                            assertFailsWith<SQLException> {
                                source.blueskyTransaction {
                                    prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE NOWAIT").use {
                                        it.setObject(1, id)
                                        it.executeQuery().close()
                                    }
                                }
                            }
                        assertEquals("55P03", refusal.sqlState)
                        control.rollback()
                        assertContentEquals(byteArrayOf(9), withTimeout(5_000) { writer.await() }.getOrThrow().data)
                    } finally {
                        control.rollback()
                        withContext(NonCancellable) { withTimeout(5_000) { writer.await() } }
                    }
                }
            }
        }

    @Test fun compositeMediaBoundaryCarriesTheOriginatingEpoch() =
        withFixture {
            val composite = CompositeLogDateMediaBlobRepository(media, blobs)
            refuses(IdentityFenceOutcome.CHANGED) { composite.upsertMedia(id, originalMedia, identityEpoch = 1) }
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_epoch = 1 WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val updated = composite.upsertMedia(id, originalMedia.copy(data = byteArrayOf(9)), identityEpoch = 1)
            assertContentEquals(byteArrayOf(9), updated.data)
            assertEquals(originalMedia.storagePath, updated.storagePath)
        }

    @Test fun unknownIdentityKindCannotWriteLegacyMetadata() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET identity_kind = 'future-kind' WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { media.upsertMedia(id, originalMedia.copy(data = byteArrayOf(9))) }
        }

    @Test fun unknownStorageModeCannotWriteOrReserveAdoption() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement("UPDATE accounts SET storage_mode = 'future-mode' WHERE id = ?").use {
                    it.setObject(1, id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val adoption = PostgreSQLIdentityAdoptionRepository(source)
            assertEquals(
                IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.SOURCE_INELIGIBLE),
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            )
            refuses(IdentityFenceOutcome.CHANGED) { backups.deleteBackup(id, originalBackup.id) }
        }

    @Test fun retiredCurrentIdentityCannotMutateLegacyMetadata() =
        withFixture {
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO retired_identity_dids(source_did, account_id, operation_id, identity_epoch, retired_at) " +
                        "VALUES (?, ?, ?, 1, ?)",
                ).use {
                    it.setString(1, owner.did)
                    it.setObject(2, id)
                    it.setString(3, Uuid.random().toString())
                    it.setTimestamp(4, Clock.System.now().timestamp())
                    assertEquals(1, it.executeUpdate())
                }
            }
            refuses(IdentityFenceOutcome.CHANGED) { blobs.upsertBlob(id, originalBlob.copy(storagePath = "synthetic/changed")) }
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val media = PostgreSQLLogDateMediaRepository()
        val backups = PostgreSQLLogDateBackupRepository()
        val blobs = PostgreSQLLogDateAtprotoBlobRepository()
        val originalMedia =
            media.upsertMedia(
                id,
                LogDateMedia(
                    "media",
                    "entry",
                    id,
                    "original.enc",
                    "application/octet-stream",
                    2,
                    byteArrayOf(1, 2),
                    "synthetic/media",
                    42,
                    0,
                    DeviceId("synthetic-device"),
                ),
            )
        val originalBackup =
            backups.createBackup(
                id,
                LogDateBackup(
                    Uuid.random().toJavaUUID(),
                    id,
                    "synthetic-device",
                    "{}",
                    "synthetic/backup",
                    42,
                    2,
                ),
            )
        val originalBlob = blobs.upsertBlob(id, LogDateAtprotoBlob("bafy-owned", id, "application/octet-stream", 2, "synthetic/blob", 42))

        suspend fun refuses(
            outcome: IdentityFenceOutcome,
            mutate: () -> Any?,
        ) {
            assertEquals(outcome, assertFailsWith<IdentityWriteRefusedException> { mutate() }.outcome)
            assertEquals(originalMedia, media.getMedia(id, originalMedia.mediaId))
            assertEquals(listOf(originalBackup), backups.listBackups(id))
            assertEquals(originalBlob, blobs.getBlob(id, originalBlob.cid))
        }

        suspend fun hold() {
            val adoption = PostgreSQLIdentityAdoptionRepository(source)
            val binding = IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0)
            val operation =
                assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                    adoption.begin(binding, Clock.System.now()),
                ).value
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(
                adoption.transition(
                    owner.id,
                    operation.id,
                    operation.revision,
                    IdentityAdoptionPhase.FENCED,
                    Clock.System.now(),
                ),
            )
        }

        suspend fun waitingOnMedia(): Int =
            source.blueskyTransaction {
                prepareStatement(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() " +
                        "AND wait_event_type = 'Lock' AND query LIKE '%logdate_media_records%' AND query NOT LIKE '%pg_stat_activity%'",
                ).use {
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(id, id.toHexString(), "Metadata owner", did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa", createdAt = Clock.System.now())
            assertTrue(PostgreSQLAccountRepository().create(owner))
            source.blueskyTransaction {
                prepareStatement(
                    "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, " +
                        "created_at, publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
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
