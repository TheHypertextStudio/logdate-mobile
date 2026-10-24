package app.logdate.feature.journals.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.search.SearchRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.content_picker_add_failed
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class JournalContentPickerViewModel(
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    private val searchRepository: SearchRepository,
) : ViewModel() {
    private val journalIdState = MutableStateFlow<Uuid?>(null)
    private val searchQueryState = MutableStateFlow("")
    private val selectedIdsState = MutableStateFlow<Set<Uuid>>(emptySet())
    private val addState = MutableStateFlow(AddState())

    private val pickerData: Flow<PickerData> =
        combine(journalIdState.filterNotNull(), searchQueryState) { journalId, query -> journalId to query }
            .flatMapLatest { (journalId, query) ->
                val searchIds =
                    if (query.isBlank()) {
                        flowOf<Set<Uuid>?>(null)
                    } else {
                        searchRepository.searchRanked(query, SEARCH_RESULT_LIMIT).map { results ->
                            results.mapTo(mutableSetOf()) { it.uid }
                        }
                    }
                combine(
                    journalRepository.observeJournalById(journalId),
                    journalNotesRepository.allNotesObserved,
                    journalContentRepository.observeContentForJournal(journalId),
                    searchIds,
                ) { journal, notes, currentMembers, matchingIds ->
                    val memberIds = currentMembers.mapTo(mutableSetOf()) { it.uid }
                    val allEligible =
                        notes
                            .asSequence()
                            .filterNot { it.uid in memberIds }
                            .sortedByDescending(JournalNote::creationTimestamp)
                            .map { note -> note.toPickerItem() }
                            .toList()
                    PickerData(
                        journalTitle = journal.title,
                        allEligible = allEligible,
                        visibleItems = matchingIds?.let { ids -> allEligible.filter { it.id in ids } } ?: allEligible,
                    )
                }
            }

    val uiState: StateFlow<JournalContentPickerUiState> =
        combine(pickerData, searchQueryState, selectedIdsState, addState) { data, query, selectedIds, add ->
            val selectedItems = data.allEligible.filter { it.id in selectedIds }
            JournalContentPickerUiState(
                journalTitle = data.journalTitle,
                query = query,
                groups = data.visibleItems.groupByDate(),
                selectedItems = selectedItems,
                isAdding = add.isAdding,
                addError = add.error,
                addedCount = add.addedCount,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            JournalContentPickerUiState(),
        )

    fun setJournalId(journalId: Uuid) {
        if (journalIdState.value != journalId) {
            journalIdState.value = journalId
            selectedIdsState.value = emptySet()
            addState.value = AddState()
        }
    }

    fun updateSearchQuery(query: String) {
        searchQueryState.value = query
    }

    fun toggleSelection(contentId: Uuid) {
        selectedIdsState.update { selected ->
            if (contentId in selected) selected - contentId else selected + contentId
        }
        addState.update { it.copy(error = null, addedCount = null) }
    }

    fun removeSelection(contentId: Uuid) {
        selectedIdsState.update { it - contentId }
        addState.update { it.copy(error = null, addedCount = null) }
    }

    fun addSelected() {
        val journalId = journalIdState.value ?: return
        val contentIds = selectedIdsState.value.toList()
        if (contentIds.isEmpty() || addState.value.isAdding) return

        viewModelScope.launch {
            addState.value = AddState(isAdding = true)
            runCatching { journalContentRepository.addContentsToJournal(contentIds, journalId) }
                .onSuccess { count -> addState.value = AddState(addedCount = count) }
                .onFailure { error ->
                    Napier.e("Could not add selected journal content", error)
                    addState.value = AddState(error = Res.string.content_picker_add_failed)
                }
        }
    }

    private fun JournalNote.toPickerItem(): JournalContentPickerItem =
        when (this) {
            is JournalNote.Handwriting -> {
                JournalContentPickerItem(
                    uid,
                    JournalContentPickerItemKind.WRITING,
                    creationTimestamp,
                    title = null,
                )
            }

            is JournalNote.Text -> {
                JournalContentPickerItem(uid, JournalContentPickerItemKind.WRITING, creationTimestamp, content)
            }

            is JournalNote.Image -> {
                JournalContentPickerItem(
                    uid,
                    JournalContentPickerItemKind.PHOTO,
                    creationTimestamp,
                    caption.ifBlank { null },
                    mediaRef,
                )
            }

            is JournalNote.Video -> {
                JournalContentPickerItem(
                    uid,
                    JournalContentPickerItemKind.VIDEO,
                    creationTimestamp,
                    caption.ifBlank { null },
                    mediaRef,
                )
            }

            is JournalNote.Audio -> {
                JournalContentPickerItem(
                    uid,
                    JournalContentPickerItemKind.RECORDING,
                    creationTimestamp,
                    location?.displayName,
                    mediaRef,
                )
            }
        }

    private fun List<JournalContentPickerItem>.groupByDate(): List<JournalContentPickerDateGroup> =
        groupBy { item -> item.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date }
            .map { (date, items) -> JournalContentPickerDateGroup(date, items) }

    private data class PickerData(
        val journalTitle: String,
        val allEligible: List<JournalContentPickerItem>,
        val visibleItems: List<JournalContentPickerItem>,
    )

    private data class AddState(
        val isAdding: Boolean = false,
        val error: StringResource? = null,
        val addedCount: Int? = null,
    )

    private companion object {
        const val SEARCH_RESULT_LIMIT = 200
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
