package app.logdate.client.sync

import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.media.MediaPayload
import app.logdate.client.sync.conflict.DeletionConflictRecovery
import app.logdate.client.sync.conflict.KeyValueSyncConflictStore
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.test.FakeJournalContentRepository
import app.logdate.client.sync.test.FakeJournalNotesRepository
import app.logdate.client.sync.test.FakeJournalRepository
import app.logdate.client.sync.test.testSyncTransactionManager
import app.logdate.shared.model.Journal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalDeletionRecoveryTest {
    @Test
    fun `a missing journal cover cannot be reported as durably preserved`() =
        runTest {
            val journals = FakeJournalRepository()
            val journal = Journal(title = "Keep this journal", coverImageUri = "file:///missing-cover.jpg")
            journals.create(journal)
            val store = KeyValueSyncConflictStore(InMemoryKeyValueStorage())
            val recovery = recovery(store, journals, InMemoryMediaManager())

            assertFailsWith<MissingMediaException> { recovery.retain(EntityType.JOURNAL, journal.id.toString()) }

            assertEquals(journal, journals.getJournalById(journal.id))
            assertTrue(store.list().isEmpty())
        }

    @Test
    fun `a saved journal cover survives losing its external source and restarting recovery`() =
        runTest {
            val journals = FakeJournalRepository()
            val media = ExternalCoverMedia()
            val journal = Journal(title = "Offline edit", coverImageUri = media.externalUri)
            journals.create(journal)
            val store = KeyValueSyncConflictStore(InMemoryKeyValueStorage())
            recovery(store, journals, media).retain(EntityType.JOURNAL, journal.id.toString())
            val retained = store.list().single()
            val retainedCover = assertNotNull(retained.deletedEdit?.journal?.coverImageUri)
            assertNotEquals(media.externalUri, retainedCover)

            media.externalAvailable = false
            journals.delete(journal.id)
            val restarted = recovery(store, journals, media)
            val recoveredId = assertNotNull(restarted.saveAsNew(retained.id))

            assertNotEquals(journal.id, recoveredId)
            assertEquals(retainedCover, journals.getJournalById(recoveredId)?.coverImageUri)
            assertContentEquals(byteArrayOf(1, 2, 3), media.readMedia(retainedCover).data)
            assertEquals(recoveredId, restarted.saveAsNew(retained.id))
            assertNull(journals.getJournalById(journal.id))
        }

    private fun recovery(
        store: KeyValueSyncConflictStore,
        journals: FakeJournalRepository,
        media: InMemoryMediaManager,
    ) = DeletionConflictRecovery(
        store,
        FakeJournalNotesRepository(),
        journals,
        FakeJournalContentRepository(),
        testSyncTransactionManager(),
        { UploadScope("owner", "origin") },
        media,
    )

    private class ExternalCoverMedia : InMemoryMediaManager() {
        val externalUri = "content://photos/external-cover"
        var externalAvailable = true

        override suspend fun exists(mediaId: String): Boolean = if (mediaId == externalUri) externalAvailable else super.exists(mediaId)

        override suspend fun readMedia(uri: String): MediaPayload =
            if (uri == externalUri && externalAvailable) {
                MediaPayload("retained-cover.jpg", "image/jpeg", 3, byteArrayOf(1, 2, 3))
            } else {
                super.readMedia(uri)
            }
    }
}
