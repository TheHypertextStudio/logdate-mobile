package app.logdate.feature.journals.ui.creation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.shared.model.Journal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class JournalCreationViewModel(
    private val journalRepository: JournalRepository,
    private val notesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    // Nav3 passes route args directly to the entry composable; if a journalTitle is supplied
    // at navigation time, the calling code is expected to thread it into the ViewModel via
    // [setInitialTitle] rather than through SavedStateHandle.
    private val initialTitle: String = savedStateHandle.get<String>("journalTitle") ?: ""

    private val backingUiState =
        MutableStateFlow(
            JournalCreationUiState(
                title = initialTitle,
            ),
        )
    val uiState: StateFlow<JournalCreationUiState> = backingUiState

    /**
     * Recent notes available for adding to the new journal.
     */
    val recentNotes: StateFlow<List<RecentNoteItem>> =
        notesRepository
            .observeRecentNotes(limit = 30)
            .map { notes ->
                notes.map { note ->
                    RecentNoteItem(
                        id = note.uid,
                        note = note,
                        timestamp = note.creationTimestamp,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMediaUris(uris: List<String>) {
        backingUiState.update { state ->
            state.copy(selectedMediaUris = state.selectedMediaUris + uris)
        }
    }

    fun toggleNoteSelection(noteId: Uuid) {
        backingUiState.update { state ->
            val current = state.selectedNoteIds
            val updated = if (noteId in current) current - noteId else current + noteId
            state.copy(selectedNoteIds = updated)
        }
    }

    fun createJournal(data: NewJournalRequest) {
        viewModelScope.launch {
            // Auto-set cover from first selected media image
            val coverUri = backingUiState.value.selectedMediaUris.firstOrNull()

            val journalId =
                journalRepository.create(
                    Journal(
                        title = data.title,
                        description = data.contentDescription,
                        coverImageUri = coverUri,
                    ),
                )

            // Add selected notes to the new journal
            val selectedIds = backingUiState.value.selectedNoteIds
            for (noteId in selectedIds) {
                journalContentRepository.addContentToJournal(
                    contentId = noteId,
                    journalId = journalId,
                )
            }

            backingUiState.update { currentState ->
                currentState.copy(created = true, journalId = journalId)
            }
        }
    }
}

data class JournalCreationUiState(
    val created: Boolean = false,
    val journalId: Uuid? = null,
    val title: String = "",
    val selectedNoteIds: Set<Uuid> = emptySet(),
    val selectedMediaUris: List<String> = emptyList(),
)

/**
 * A recent note available for selection during journal creation.
 */
data class RecentNoteItem(
    val id: Uuid,
    /** The note itself, so the picker shows its photo, ink, recording or words. */
    val note: JournalNote,
    val timestamp: kotlin.time.Instant,
)
