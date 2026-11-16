package app.logdate.feature.journals.ui.detail

import app.logdate.client.domain.notes.RemoveNoteUseCase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sharing.ShareTheme
import app.logdate.client.sharing.SharingLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for [NoteViewerViewModel], which manages the display and interaction logic
 * for viewing individual journal notes of various types (text, image, video, and audio).
 *
 * This suite verifies that domain-level note models are correctly mapped to UI states,
 * and that user actions like deleting or sharing a note are properly executed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteViewerViewModelTest {
    @Test
    fun `follows one note without subscribing to every note`() =
        runTest(dispatcher) {
            val at = Instant.parse("2026-10-28T12:00:00Z")
            val note = JournalNote.Text(content = "Local entry", creationTimestamp = at, lastUpdated = at)
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewer =
                NoteViewerViewModel(
                    note.uid,
                    null,
                    repository,
                    journalRepository,
                    journalContentRepository,
                    RemoveNoteUseCase(repository),
                    sharingLauncher,
                )
            advanceUntilIdle()
            assertEquals("Local entry", assertIs<NoteViewerUiState.TextContent>(viewer.uiState.value).text)

            repository.setNotes(listOf(note.copy(content = "Edited entry")))
            advanceUntilIdle()

            assertEquals("Edited entry", assertIs<NoteViewerUiState.TextContent>(viewer.uiState.value).text)
            assertEquals(0, repository.wholeTableSubscriptions)
        }

    private val dispatcher = StandardTestDispatcher()
    private val journalRepository = FakeDetailJournalRepository()
    private val journalContentRepository = FakeDetailJournalContentRepository()
    private val sharingLauncher = RecordingSharingLauncher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `maps text note to content state`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Text(
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "Hello",
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewModel =
                NoteViewerViewModel(
                    noteId = noteId,
                    journalId = null,
                    notesRepository = repository,
                    journalRepository = journalRepository,
                    journalContentRepository = journalContentRepository,
                    removeNoteUseCase = RemoveNoteUseCase(repository),
                    sharingLauncher = sharingLauncher,
                )

            advanceUntilIdle()

            val state = viewModel.uiState.value
            val content = assertIs<NoteViewerUiState.TextContent>(state)
            assertEquals("Hello", content.text)
            assertEquals(noteId, content.shared.noteId)
        }

    @Test
    fun `maps image note to content state`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Image(
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "image://test",
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewModel =
                NoteViewerViewModel(
                    noteId = noteId,
                    journalId = null,
                    notesRepository = repository,
                    journalRepository = journalRepository,
                    journalContentRepository = journalContentRepository,
                    removeNoteUseCase = RemoveNoteUseCase(repository),
                    sharingLauncher = sharingLauncher,
                )

            advanceUntilIdle()

            val state = viewModel.uiState.value
            val content = assertIs<NoteViewerUiState.ImageContent>(state)
            assertEquals("image://test", content.mediaRef)
        }

    @Test
    fun `maps video note to content state`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Video(
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "video://test",
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewModel =
                NoteViewerViewModel(
                    noteId = noteId,
                    journalId = null,
                    notesRepository = repository,
                    journalRepository = journalRepository,
                    journalContentRepository = journalContentRepository,
                    removeNoteUseCase = RemoveNoteUseCase(repository),
                    sharingLauncher = sharingLauncher,
                )

            advanceUntilIdle()

            val state = viewModel.uiState.value
            val content = assertIs<NoteViewerUiState.VideoContent>(state)
            assertEquals("video://test", content.mediaRef)
        }

    @Test
    fun `maps audio note to content state`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Audio(
                    mediaRef = "audio://test",
                    durationMs = 1234L,
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewModel =
                NoteViewerViewModel(
                    noteId = noteId,
                    journalId = null,
                    notesRepository = repository,
                    journalRepository = journalRepository,
                    journalContentRepository = journalContentRepository,
                    removeNoteUseCase = RemoveNoteUseCase(repository),
                    sharingLauncher = sharingLauncher,
                )

            advanceUntilIdle()

            val state = viewModel.uiState.value
            val content = assertIs<NoteViewerUiState.AudioContent>(state)
            assertEquals("audio://test", content.mediaRef)
            assertEquals(1234L, content.durationMs)
        }

    @Test
    fun `delete note invokes use case`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Text(
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "Delete me",
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewModel =
                NoteViewerViewModel(
                    noteId = noteId,
                    journalId = null,
                    notesRepository = repository,
                    journalRepository = journalRepository,
                    journalContentRepository = journalContentRepository,
                    removeNoteUseCase = RemoveNoteUseCase(repository),
                    sharingLauncher = sharingLauncher,
                )

            var deleted = false
            viewModel.deleteNote { deleted = true }
            advanceUntilIdle()

            assertTrue(deleted)
            assertEquals(noteId, repository.lastRemovedId)
        }

    @Test
    fun `share current note shares text content as plain text`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Text(
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                    content = "Share me",
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewModel =
                NoteViewerViewModel(
                    noteId = noteId,
                    journalId = null,
                    notesRepository = repository,
                    journalRepository = journalRepository,
                    journalContentRepository = journalContentRepository,
                    removeNoteUseCase = RemoveNoteUseCase(repository),
                    sharingLauncher = sharingLauncher,
                )

            advanceUntilIdle()
            viewModel.shareCurrentNote(chooserTitle = "Share note")

            assertEquals("Share me", sharingLauncher.sharedText)
            assertTrue(sharingLauncher.sharedMediaUris.isEmpty())
        }

    @Test
    fun `share current note shares image content as media`() =
        runTest(dispatcher) {
            val noteId = Uuid.random()
            val now = Instant.parse("2025-01-01T00:00:00Z")
            val note =
                JournalNote.Image(
                    uid = noteId,
                    creationTimestamp = now,
                    lastUpdated = now,
                    mediaRef = "content://media/image/1",
                )
            val repository = FakeJournalNotesRepository(listOf(note))
            val viewModel =
                NoteViewerViewModel(
                    noteId = noteId,
                    journalId = null,
                    notesRepository = repository,
                    journalRepository = journalRepository,
                    journalContentRepository = journalContentRepository,
                    removeNoteUseCase = RemoveNoteUseCase(repository),
                    sharingLauncher = sharingLauncher,
                )

            advanceUntilIdle()
            viewModel.shareCurrentNote(chooserTitle = "Share note")

            assertEquals(listOf("content://media/image/1"), sharingLauncher.sharedMediaUris)
        }

    private class RecordingSharingLauncher : SharingLauncher {
        var sharedText: String? = null
        var sharedMediaUris: List<String> = emptyList()

        override fun shareContent(
            text: String?,
            mediaUris: List<String>,
            title: String?,
            chooserTitle: String?,
        ) {
            sharedText = text
            sharedMediaUris = mediaUris
        }

        override fun shareMemoryDay(
            date: kotlinx.datetime.LocalDate,
            summary: String,
            mediaUris: List<String>,
        ) = Unit

        override fun shareJournalToInstagram(
            journalId: Uuid,
            theme: ShareTheme,
        ) = Unit

        override fun shareJournalLink(journalId: Uuid) = Unit

        override fun shareJournalQrCode(journalId: Uuid) = Unit

        override fun sharePhotoToInstagramFeed(photoId: String) = Unit

        override fun shareVideoToInstagramFeed(videoId: String) = Unit

        override fun getUriFromMedia(uid: String): Any = uid
    }
}
