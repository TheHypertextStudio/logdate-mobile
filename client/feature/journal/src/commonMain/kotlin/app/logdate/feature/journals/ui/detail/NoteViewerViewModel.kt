package app.logdate.feature.journals.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.notes.RemoveNoteUseCase
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.sharing.SharingLauncher
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.shared.model.Journal
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.note_viewer_error_delete_failed
import logdate.client.feature.journal.generated.resources.note_viewer_error_load_failed
import logdate.client.feature.journal.generated.resources.note_viewer_error_not_found
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * ViewModel that loads a note and exposes a type-safe viewer state.
 *
 * When [journalId] is provided, the viewer shows journal-connected context:
 * the journal's accent color, title, and prev/next sibling navigation.
 */
class NoteViewerViewModel(
    private val noteId: Uuid,
    private val journalId: Uuid?,
    private val notesRepository: JournalNotesRepository,
    private val journalRepository: JournalRepository,
    private val journalContentRepository: JournalContentRepository,
    private val removeNoteUseCase: RemoveNoteUseCase,
    private val sharingLauncher: SharingLauncher,
) : ViewModel() {
    private val _uiState = MutableStateFlow<NoteViewerUiState>(NoteViewerUiState.Loading)
    val uiState: StateFlow<NoteViewerUiState> = _uiState.asStateFlow()

    private var journalContext: JournalContext? = null

    init {
        if (journalId != null) {
            loadJournalContext(journalId)
        }
        observeNote()
    }

    private fun loadJournalContext(journalId: Uuid) {
        viewModelScope.launch {
            val journal = journalRepository.observeJournalById(journalId).firstOrNull() ?: return@launch
            val siblingNotes = journalContentRepository.observeContentForJournal(journalId).firstOrNull() ?: return@launch
            val sortedIds = siblingNotes.sortedByDescending { it.creationTimestamp }.map { it.uid }
            val currentIndex = sortedIds.indexOf(noteId).coerceAtLeast(0)

            journalContext =
                JournalContext(
                    journalId = journalId,
                    journalTitle = journal.title,
                    siblingNoteIds = sortedIds,
                    currentIndex = currentIndex,
                )

            // Re-emit current state with journal context if note is already loaded
            val current = _uiState.value
            if (current is NoteViewerUiState.Content) {
                _uiState.value = current.withJournalContext(journalContext)
            }
        }
    }

    private fun observeNote() {
        viewModelScope.launch {
            try {
                notesRepository.getNoteById(noteId)?.let(::updateForNote)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Napier.w("Direct local note read failed; observing retained entries", failure)
            }
            notesRepository.allNotesObserved
                .catch { error ->
                    _uiState.value =
                        NoteViewerUiState.Error(
                            Res.string.note_viewer_error_load_failed.toUiText(error.message.toString()),
                        )
                }.collect { notes ->
                    val note = notes.find { it.uid == noteId }
                    if (note == null) {
                        _uiState.value = NoteViewerUiState.Error(Res.string.note_viewer_error_not_found.toUiText())
                    } else {
                        updateForNote(note)
                    }
                }
        }
    }

    private fun updateForNote(note: JournalNote) {
        val shared =
            NoteViewerShared(
                noteId = note.uid,
                createdAt = note.creationTimestamp,
                lastUpdated = note.lastUpdated,
                location = note.location,
                journalContext = journalContext,
            )
        _uiState.value =
            when (note) {
                is JournalNote.Handwriting -> {
                    NoteViewerUiState.HandwritingContent(shared, note.document, note.unreadableContent != null)
                }

                is JournalNote.Text -> {
                    NoteViewerUiState.TextContent(shared, note.content)
                }

                is JournalNote.Image -> {
                    NoteViewerUiState.ImageContent(shared, note.mediaRef)
                }

                is JournalNote.Video -> {
                    NoteViewerUiState.VideoContent(shared, note.mediaRef)
                }

                is JournalNote.Audio -> {
                    NoteViewerUiState.AudioContent(
                        shared = shared,
                        mediaRef = note.mediaRef,
                        durationMs = note.durationMs,
                    )
                }
            }
    }

    /**
     * Deletion request for the current note.
     */
    fun deleteNote(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                removeNoteUseCase(noteId)
            }.onSuccess {
                onDeleted()
            }.onFailure { error ->
                _uiState.value = NoteViewerUiState.Error(Res.string.note_viewer_error_delete_failed.toUiText(error.message.toString()))
            }
        }
    }

    /**
     * All journals available for the "Add to journal" picker.
     */
    val allJournals: StateFlow<List<Journal>> =
        journalRepository.allJournalsObserved
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * IDs of journals this note currently belongs to.
     */
    val memberJournalIds: StateFlow<Set<Uuid>> =
        journalContentRepository
            .observeJournalsForContent(noteId)
            .map { journals -> journals.map { it.id }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /**
     * Adds or removes this note from a journal.
     */
    fun toggleJournalMembership(
        targetJournalId: Uuid,
        add: Boolean,
    ) {
        viewModelScope.launch {
            if (add) {
                journalContentRepository.addContentToJournal(noteId, targetJournalId)
            } else {
                journalContentRepository.removeContentFromJournal(noteId, targetJournalId)
            }
        }
    }

    /**
     * Shares the currently viewed note using the platform share surface.
     *
     * @param chooserTitle The share sheet's title, already resolved for the current locale.
     */
    fun shareCurrentNote(chooserTitle: String) {
        when (val state = _uiState.value) {
            is NoteViewerUiState.TextContent -> {
                sharingLauncher.shareContent(text = state.text, chooserTitle = chooserTitle)
            }

            is NoteViewerUiState.ImageContent -> {
                sharingLauncher.shareContent(mediaUris = listOf(state.mediaRef), chooserTitle = chooserTitle)
            }

            is NoteViewerUiState.VideoContent -> {
                sharingLauncher.shareContent(mediaUris = listOf(state.mediaRef), chooserTitle = chooserTitle)
            }

            is NoteViewerUiState.AudioContent -> {
                sharingLauncher.shareContent(mediaUris = listOf(state.mediaRef), chooserTitle = chooserTitle)
            }

            else -> {
                Unit
            }
        }
    }
}

/**
 * Shared metadata for note viewer presentations.
 */
data class NoteViewerShared(
    val noteId: Uuid,
    val createdAt: Instant,
    val lastUpdated: Instant,
    val location: NoteLocation?,
    val journalContext: JournalContext? = null,
)

/**
 * Context connecting the viewed note to the journal it was opened from.
 * Enables accent color, journal title display, and prev/next navigation.
 */
data class JournalContext(
    val journalId: Uuid,
    val journalTitle: String,
    val siblingNoteIds: List<Uuid>,
    val currentIndex: Int,
) {
    val hasPrevious: Boolean get() = currentIndex > 0
    val hasNext: Boolean get() = currentIndex < siblingNoteIds.size - 1
    val previousNoteId: Uuid? get() = if (hasPrevious) siblingNoteIds[currentIndex - 1] else null
    val nextNoteId: Uuid? get() = if (hasNext) siblingNoteIds[currentIndex + 1] else null
}

/**
 * Type-safe UI state for note viewing.
 */
sealed interface NoteViewerUiState {
    data object Loading : NoteViewerUiState

    data class Error(
        val message: UiText,
    ) : NoteViewerUiState

    /**
     * Base contract for content-bearing viewer states.
     */
    sealed interface Content : NoteViewerUiState {
        val shared: NoteViewerShared

        fun withJournalContext(context: JournalContext?): Content
    }

    data class HandwritingContent(
        override val shared: NoteViewerShared,
        val document: app.logdate.shared.model.HandwritingDocument,
        val unreadable: Boolean = false,
    ) : Content {
        override fun withJournalContext(context: JournalContext?) = copy(shared = shared.copy(journalContext = context))
    }

    data class TextContent(
        override val shared: NoteViewerShared,
        val text: String,
    ) : Content {
        override fun withJournalContext(context: JournalContext?) = copy(shared = shared.copy(journalContext = context))
    }

    data class ImageContent(
        override val shared: NoteViewerShared,
        val mediaRef: String,
    ) : Content {
        override fun withJournalContext(context: JournalContext?) = copy(shared = shared.copy(journalContext = context))
    }

    data class VideoContent(
        override val shared: NoteViewerShared,
        val mediaRef: String,
    ) : Content {
        override fun withJournalContext(context: JournalContext?) = copy(shared = shared.copy(journalContext = context))
    }

    data class AudioContent(
        override val shared: NoteViewerShared,
        val mediaRef: String,
        val durationMs: Long,
    ) : Content {
        override fun withJournalContext(context: JournalContext?) = copy(shared = shared.copy(journalContext = context))
    }
}
