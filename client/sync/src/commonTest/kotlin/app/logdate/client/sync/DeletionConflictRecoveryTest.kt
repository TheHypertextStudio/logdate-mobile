package app.logdate.client.sync

import app.logdate.client.sync.conflict.DeletionConflictRecovery
import app.logdate.client.sync.conflict.KeyValueSyncConflictStore
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.test.FakeJournalContentRepository
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.testSyncTransactionManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeletionConflictRecoveryTest {
    @Test
    fun `retained edit survives restart and recovery creates only one fresh item`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            val notes = FakeJournalNotesRepository()
            val journals = FakeJournalRepository()
            val contents = FakeJournalContentRepository()
            val original = notes.addTestNote("An offline edit")
            val scope = UploadScope("owner", "https://cloud.example")
            var selected: UploadScope? = scope

            fun recovery() =
                DeletionConflictRecovery(
                    KeyValueSyncConflictStore(storage),
                    notes,
                    journals,
                    contents,
                    testSyncTransactionManager(),
                    { selected },
                )
            recovery().retain(EntityType.NOTE, original.uid.toString())
            notes.deleteFromSync(original.uid)
            val retained = recovery().list().single()
            assertEquals(original, retained.deletedEdit?.note)
            selected = scope.copy(ownerId = "other")
            assertTrue(recovery().list().isEmpty())
            selected = scope
            val id = assertNotNull(recovery().saveAsNew(retained.id))
            assertNotEquals(original.uid, id)
            assertEquals(id, recovery().saveAsNew(retained.id))
            assertTrue(recovery().list().isEmpty(), "Completed recovery keeps a receipt, not another hidden content owner")
            assertEquals(null, KeyValueSyncConflictStore(storage).list().single().deletedEdit)
            assertEquals("An offline edit", (notes.getNoteById(id) as app.logdate.client.repository.journals.JournalNote.Text).content)
            assertEquals(null, notes.getNoteById(original.uid))
        }

    @Test
    fun `corrupt recovery storage is preserved instead of overwritten`() =
        runTest {
            val storage = InMemoryKeyValueStorage()
            storage.values["sync_conflict_queue"] = "broken retained data"
            val notes = FakeJournalNotesRepository()
            val original = notes.addTestNote("Keep this too")
            val recovery =
                DeletionConflictRecovery(
                    KeyValueSyncConflictStore(storage),
                    notes,
                    FakeJournalRepository(),
                    FakeJournalContentRepository(),
                    testSyncTransactionManager(),
                    { UploadScope("owner", "origin") },
                )
            assertFailsWith<Exception> { recovery.retain(EntityType.NOTE, original.uid.toString()) }
            assertEquals("broken retained data", storage.values["sync_conflict_queue"])
        }

    @Test
    fun `an unavailable attachment cannot be reported as safely retained`() =
        runTest {
            val store = KeyValueSyncConflictStore(InMemoryKeyValueStorage())
            val notes = FakeJournalNotesRepository()
            val now = kotlin.time.Instant.fromEpochMilliseconds(1)
            val original =
                app.logdate.client.repository.journals.JournalNote.Audio(
                    mediaRef = "file:///missing-recording.m4a",
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            notes.create(original)
            val recovery =
                DeletionConflictRecovery(
                    store,
                    notes,
                    FakeJournalRepository(),
                    FakeJournalContentRepository(),
                    testSyncTransactionManager(),
                    { UploadScope("owner", "origin") },
                    app.logdate.client.media
                        .InMemoryMediaManager(),
                )
            assertFailsWith<MissingMediaException> { recovery.retain(EntityType.NOTE, original.uid.toString()) }
            assertEquals(original, notes.getNoteById(original.uid))
            assertTrue(recovery.list().isEmpty())
        }

    @Test
    fun `a deleted draft can be retained and saved under a new identity`() =
        runTest {
            val store = KeyValueSyncConflictStore(InMemoryKeyValueStorage())
            val journals = FakeJournalRepository()
            val draft =
                app.logdate.shared.model
                    .EditorDraft()
            journals.saveDraft(draft)
            val recovery =
                DeletionConflictRecovery(
                    store,
                    FakeJournalNotesRepository(),
                    journals,
                    FakeJournalContentRepository(),
                    testSyncTransactionManager(),
                    { UploadScope("owner", "origin") },
                )
            recovery.retain(EntityType.DRAFT, draft.id.toString())
            journals.deleteDraft(draft.id)
            val saved = recovery.list().single()
            val newId = assertNotNull(recovery.saveAsNew(saved.id))
            assertNotEquals(draft.id, newId)
            assertNotNull(journals.getDraft(newId))
        }

    @Test
    fun `a remote note deletion preserves a pending membership before removing the note`() =
        runTest {
            val api =
                app.logdate.client.sync.test
                    .fakeCloudApiClient()
            val notes = FakeJournalNotesRepository()
            val original = notes.addTestNote("Placed in a journal while offline")
            val journalId = kotlin.uuid.Uuid.random()
            val metadata =
                app.logdate.client.sync.test
                    .fakeSyncMetadataService()
            metadata.enqueuePending(
                app.logdate.client.sync.metadata
                    .AssociationPendingKey(journalId, original.uid)
                    .toPendingId(),
                EntityType.ASSOCIATION,
                app.logdate.client.sync.metadata.PendingOperation.CREATE,
            )
            api.getContentChangesResponse =
                Result.success(
                    app.logdate.client.sync.cloud.ContentChangesResponse(
                        changes = emptyList(),
                        deletions =
                            listOf(
                                app.logdate.client.sync.cloud
                                    .ContentDeletion(original.uid.toString(), 100),
                            ),
                        lastTimestamp = 100,
                    ),
                )
            val manager =
                app.logdate.client.sync.test.testDefaultSyncManager(
                    cloudContentDataSource =
                        app.logdate.client.sync.cloud
                            .DefaultCloudContentDataSource(api),
                    journalNotesRepository = notes,
                    syncMetadataService = metadata,
                    syncScope = backgroundScope,
                )
            assertTrue(manager.downloadRemoteChanges().success)
            val saved = manager.deletionRecovery.list().single()
            assertEquals(original, saved.deletedEdit?.note)
            assertTrue(
                saved.deletedEdit
                    ?.journalIds
                    .orEmpty()
                    .contains(journalId.toString()),
            )
            assertEquals(null, notes.getNoteById(original.uid))
            assertTrue(metadata.getPendingUploads(EntityType.ASSOCIATION).isEmpty())
        }

    @Test
    fun `matching relationship deletions settle without trying to snapshot a missing note`() =
        runTest {
            val api =
                app.logdate.client.sync.test
                    .fakeCloudApiClient()
            val journalId = kotlin.uuid.Uuid.random()
            val noteId = kotlin.uuid.Uuid.random()
            val metadata =
                app.logdate.client.sync.test
                    .fakeSyncMetadataService()
            val key =
                app.logdate.client.sync.metadata
                    .AssociationPendingKey(journalId, noteId)
                    .toPendingId()
            metadata.enqueuePending(key, EntityType.ASSOCIATION, app.logdate.client.sync.metadata.PendingOperation.DELETE)
            api.getAssociationChangesResponse =
                Result.success(
                    app.logdate.client.sync.cloud.AssociationChangesResponse(
                        changes = emptyList(),
                        deletions =
                            listOf(
                                app.logdate.client.sync.cloud
                                    .AssociationDeletion(journalId.toString(), noteId.toString(), 100),
                            ),
                        lastTimestamp = 100,
                    ),
                )
            val manager =
                app.logdate.client.sync.test.testDefaultSyncManager(
                    cloudAssociationDataSource =
                        app.logdate.client.sync.cloud
                            .DefaultCloudAssociationDataSource(api),
                    syncMetadataService = metadata,
                    syncScope = backgroundScope,
                )
            assertTrue(manager.downloadRemoteChanges().success)
            assertTrue(manager.deletionRecovery.list().isEmpty())
            assertTrue(metadata.getPendingUploads(EntityType.ASSOCIATION).isEmpty())
        }
}
