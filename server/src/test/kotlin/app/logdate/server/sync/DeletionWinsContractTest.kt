package app.logdate.server.sync

import app.logdate.server.database.support.withH2Database
import app.logdate.shared.model.sync.DeviceId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeletionWinsContractTest {
    @Test
    fun `database rejects resurrection and retains unknown deletions`() =
        withH2Database(
            ContentSyncTable,
            JournalSyncTable,
            AssociationSyncTable,
            MediaSyncTable,
            BackupSyncTable,
        ) { verifyDeletionWins(DbSyncRepository()) }

    @Test
    fun `memory rejects resurrection and retains unknown deletions`() = verifyDeletionWins(InMemorySyncRepository())

    private fun verifyDeletionWins(repository: SyncRepository) {
        val owner = UUID.randomUUID()
        val otherOwner = UUID.randomUUID()
        val device = DeviceId("device")
        val note = ContentRecord("deleted-note", "TEXT", "retained edit", null, null, 1, Long.MAX_VALUE, 0, device)
        val journal = JournalRecord("deleted-journal", "Retained journal", "", 1, Long.MAX_VALUE, 0, device)
        val association = AssociationRecord(journal.id, note.id, Long.MAX_VALUE, 0, device)
        val key = AssociationKey(journal.id, note.id)
        val media = MediaRecord("deleted-media", note.id, owner, "audio.m4a", "audio/mp4", 1, byteArrayOf(1), null, 1, 0, device)

        repository.deleteContent(owner, note.id, 100)
        repository.deleteJournal(owner, journal.id, 100)
        repository.deleteAssociations(owner, listOf(key), 100)
        repository.deleteMedia(owner, media.mediaId, 100)
        repository.deleteContent(owner, note.id, 50)
        repository.deleteJournal(owner, journal.id, 50)
        repository.deleteAssociations(owner, listOf(key), 50)
        repository.deleteMedia(owner, media.mediaId, 50)

        assertFailsWith<StaleWriteException> { repository.upsertContent(owner, note) }
        assertFailsWith<StaleWriteException> { repository.upsertJournal(owner, journal) }
        assertFailsWith<StaleWriteException> { repository.upsertAssociations(owner, listOf(association)) }
        assertFailsWith<StaleWriteException> { repository.upsertMedia(owner, media) }
        assertNull(repository.getContent(owner, note.id))
        assertNull(repository.getJournal(owner, journal.id))
        assertNull(repository.getMedia(owner, media.mediaId))
        assertEquals(
            100L,
            repository
                .contentChanges(owner, 0, 20)
                .deletions
                .single()
                .deletedAt,
        )
        assertEquals(
            100L,
            repository
                .journalChanges(owner, 0, 20)
                .deletions
                .single()
                .deletedAt,
        )
        assertEquals(
            100L,
            repository
                .associationChanges(owner, 0, 20)
                .deletions
                .single()
                .deletedAt,
        )

        val contentDeletion = repository.contentChanges(owner, 0, 20).deletions.single()
        val journalDeletion = repository.journalChanges(owner, 0, 20).deletions.single()
        val associationDeletion = repository.associationChanges(owner, 0, 20).deletions.single()
        repository.purgeTombstones(owner, Long.MAX_VALUE)
        assertEquals(listOf(contentDeletion), repository.contentChanges(owner, 0, 20).deletions)
        assertEquals(listOf(journalDeletion), repository.journalChanges(owner, 0, 20).deletions)
        assertEquals(listOf(associationDeletion), repository.associationChanges(owner, 0, 20).deletions)
        repository.purgeTombstonesOlderThan(Long.MAX_VALUE)
        assertEquals(listOf(contentDeletion), repository.contentChanges(owner, 0, 20).deletions)
        assertEquals(listOf(journalDeletion), repository.journalChanges(owner, 0, 20).deletions)
        assertEquals(listOf(associationDeletion), repository.associationChanges(owner, 0, 20).deletions)
        assertFailsWith<StaleWriteException> { repository.upsertContent(owner, note) }
        assertFailsWith<StaleWriteException> { repository.upsertJournal(owner, journal) }
        assertFailsWith<StaleWriteException> { repository.upsertMedia(owner, media) }

        val fresh = association.copy(journalId = "fresh-journal", contentId = "fresh-note")
        assertFailsWith<StaleWriteException> { repository.upsertAssociations(owner, listOf(fresh, association)) }
        assertTrue(repository.associationChanges(owner, 0, 20).changes.isEmpty(), "A rejected batch must not partially persist")

        // A tombstone in one account does not reserve the identifier in another account.
        assertEquals(note.content, repository.upsertContent(otherOwner, note.copy(id = "other-note")).content)
        assertTrue(repository.contentChanges(otherOwner, 0, 20).deletions.isEmpty())
        verifyConcurrentDelete(repository, owner, note.copy(id = "concurrent-note"))
    }

    private fun verifyConcurrentDelete(
        repository: SyncRepository,
        owner: UUID,
        note: ContentRecord,
    ) {
        repository.upsertContent(owner, note)
        val start = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val edit =
                threads.submit {
                    start.await()
                    try {
                        repository.upsertContent(owner, note.copy(content = "concurrent edit"))
                    } catch (_: StaleWriteException) {
                        // The deletion acquired the write lock first.
                    }
                }
            val deletion =
                threads.submit {
                    start.await()
                    repository.deleteContent(owner, note.id, 200)
                }
            start.countDown()
            edit.get()
            deletion.get()
            assertNull(repository.getContent(owner, note.id))
            assertFailsWith<StaleWriteException> { repository.upsertContent(owner, note) }
        } finally {
            threads.shutdownNow()
        }
    }
}
