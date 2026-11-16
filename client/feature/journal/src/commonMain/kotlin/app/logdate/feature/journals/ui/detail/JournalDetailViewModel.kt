package app.logdate.feature.journals.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.search.SearchInJournalUseCase
import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

/**
 * ViewModel for interacting with journal details
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JournalDetailViewModel(
    private val repository: JournalRepository,
    private val journalContentRepository: JournalContentRepository,
    private val getJournalMembership: GetJournalMembershipUseCase,
    private val searchInJournal: SearchInJournalUseCase,
    savedStateHandle: SavedStateHandle,
    private val workContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    // Simple MutableStateFlow to store the journal ID
    private val journalIdState = MutableStateFlow<Uuid?>(null)

    // Current sort order preference
    private val sortOrderState = MutableStateFlow(SortOrder.NEWEST_FIRST)

    // Search within the journal
    private val searchQueryState = MutableStateFlow("")

    val searchResults: StateFlow<List<SearchResult>> =
        searchQueryState
            .combine(journalIdState.filterNotNull()) { query, journalId -> query to journalId }
            .flatMapLatest { (query, journalId) ->
                if (query.isBlank()) {
                    flowOf(emptyList())
                } else {
                    searchInJournal(query, journalId)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchQuery: StateFlow<String> = searchQueryState

    fun updateSearchQuery(query: String) {
        searchQueryState.value = query
    }

    // Best-effort init from SavedStateHandle. The Nav3 entry passes the journal id directly
    // to the screen, which then calls [setJournalId]; this fallback only fires if a caller
    // populated SavedStateHandle["journalId"] manually (deep-link push, instrumentation test).
    init {
        savedStateHandle.get<String>("journalId")?.let { raw ->
            runCatching { Uuid.parse(raw) }.onSuccess { journalIdState.value = it }
        }
    }

    // Journal contents based on the current journal ID
    private val journalContentsState =
        journalIdState
            .filterNotNull()
            .flatMapLatest { journalId ->
                journalContentRepository.observeContentForJournal(journalId)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state combines journal data with contents and sort order
    val uiState: StateFlow<JournalDetailUiState> =
        journalIdState
            .flatMapLatest { journalId ->
                if (journalId == null) {
                    return@flatMapLatest flowOf(JournalDetailUiState.Loading)
                }

                // Membership follows the set of notes, and a sort toggle only reorders what is already mapped.
                val entries =
                    journalContentsState.flatMapLatest { notes ->
                        val noteIds = notes.mapTo(HashSet()) { it.uid }
                        if (noteIds.isEmpty()) {
                            flowOf(emptyList())
                        } else {
                            getJournalMembership(noteIds).map { membershipMap ->
                                notes.map { note ->
                                    val otherJournals =
                                        membershipMap[note.uid]
                                            .orEmpty()
                                            .filter { it.id != journalId }
                                            .map { JournalReference(it.id, it.title) }
                                    note.toEntryDisplayData(otherJournals)
                                }
                            }
                        }
                    }
                combine(repository.observeJournalById(journalId), entries, sortOrderState) { journal, displayData, sortOrder ->
                    val sortedEntries =
                        when (sortOrder) {
                            SortOrder.NEWEST_FIRST -> displayData.sortedByDescending { it.timestamp }
                            SortOrder.OLDEST_FIRST -> displayData.sortedBy { it.timestamp }
                        }
                    JournalDetailUiState.Success(journal.id, journal.title, sortedEntries, sortOrder)
                }.flowOn(workContext)
            }.catch { e ->
                emit(
                    JournalDetailUiState.Error(
                        "error",
                        "Could not load this journal: ${e.message}",
                    ),
                )
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                JournalDetailUiState.Loading,
            )

    /**
     * Toggles the sort order between newest first and oldest first
     */
    fun toggleSortOrder() {
        sortOrderState.update { currentOrder ->
            when (currentOrder) {
                SortOrder.NEWEST_FIRST -> SortOrder.OLDEST_FIRST
                SortOrder.OLDEST_FIRST -> SortOrder.NEWEST_FIRST
            }
        }
    }

    /**
     * Sets the selected journal ID.
     * This is the primary way to set which journal to display.
     */
    fun setSelectedJournalId(id: Uuid) {
        journalIdState.value = id
    }

    /**
     * Removes a note from the current journal without deleting the note itself.
     */
    fun removeNoteFromJournal(noteId: Uuid) {
        viewModelScope.launch {
            journalIdState.value?.let { journalId ->
                journalContentRepository.removeContentFromJournal(
                    contentId = noteId,
                    journalId = journalId,
                )
            }
        }
    }

    /**
     * Deletes the current journal and triggers the onDelete callback.
     */
    fun deleteJournal(onDelete: () -> Unit) {
        viewModelScope.launch {
            journalIdState.value?.let { journalId ->
                repository.delete(journalId)
                onDelete()
            }
        }
    }
}
