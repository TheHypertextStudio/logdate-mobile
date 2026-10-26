package app.logdate.client.data.notes

import app.logdate.client.data.fakes.FakeAudioNoteDao
import app.logdate.client.data.fakes.FakeImageNoteDao
import app.logdate.client.data.fakes.FakeJournalContentDao
import app.logdate.client.data.fakes.FakeJournalRepository
import app.logdate.client.data.fakes.FakeMediaCaptionDao
import app.logdate.client.data.fakes.FakeSyncMetadataService
import app.logdate.client.data.fakes.FakeTextNoteDao
import app.logdate.client.data.fakes.FakeVideoNoteDao
import app.logdate.client.media.InMemoryMediaManager
import app.logdate.client.media.MediaManager
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class RecoveryMediaOwnershipTest {
    private val images = FakeImageNoteDao()
    private val media = TrackingMedia()
    private val metadata = FakeSyncMetadataService()
    private val journals = FakeJournalRepository()

    private fun repository(retained: suspend () -> Set<String>) =
        OfflineFirstJournalNotesRepository(
            FakeTextNoteDao(),
            images,
            FakeAudioNoteDao(),
            FakeVideoNoteDao(),
            FakeJournalContentDao(),
            journals,
            FakeMediaCaptionDao(),
            mediaManager = media,
            syncMetadataService = metadata,
            retainedMedia = retained,
        )

    @Test
    fun `saved edits keep media after the original note is removed`() =
        runTest {
            val now = Clock.System.now()
            val note = JournalNote.Image(creationTimestamp = now, lastUpdated = now, mediaRef = "file:///saved-photo.jpg")
            images.addNote(note.toEntity())
            val notes = repository { setOf(note.mediaRef) }
            notes.remove(note)
            assertTrue(media.deleted.isEmpty())
            assertEquals(setOf(note.mediaRef), notes.notesReferencingMediaPaths(setOf(note.mediaRef)))
        }

    @Test
    fun `unreadable saved edits cannot authorize deleting media`() =
        runTest {
            val now = Clock.System.now()
            val note = JournalNote.Image(creationTimestamp = now, lastUpdated = now, mediaRef = "file:///saved-photo.jpg")
            images.addNote(note.toEntity())
            val notes = repository { error("Recovery store unavailable") }
            notes.remove(note)
            val pending = metadata.getPendingUploads(EntityType.NOTE).single()
            assertEquals(note.uid.toString(), pending.entityId)
            assertEquals(PendingOperation.DELETE, pending.operation)
            assertEquals(setOf(note.mediaRef), notes.notesReferencingMediaPaths(setOf(note.mediaRef)))
            assertTrue(media.deleted.isEmpty())
        }

    @Test
    fun `deleting a note cannot delete the cover of a recovered journal`() =
        runTest {
            val now = Clock.System.now()
            val note = JournalNote.Image(creationTimestamp = now, lastUpdated = now, mediaRef = "file:///shared-cover.jpg")
            images.addNote(note.toEntity())
            journals.create(
                app.logdate.shared.model
                    .Journal(title = "Recovered journal", coverImageUri = note.mediaRef),
            )
            val notes = repository { emptySet() }

            notes.remove(note)

            assertTrue(media.deleted.isEmpty())
            assertEquals(setOf(note.mediaRef), notes.notesReferencingMediaPaths(setOf(note.mediaRef)))
            assertEquals(PendingOperation.DELETE, metadata.getPendingUploads(EntityType.NOTE).single().operation)
        }

    private class TrackingMedia : MediaManager by InMemoryMediaManager() {
        val deleted = mutableListOf<String>()

        override suspend fun deleteOwnedMedia(uri: String): Boolean {
            deleted += uri
            return true
        }
    }
}
