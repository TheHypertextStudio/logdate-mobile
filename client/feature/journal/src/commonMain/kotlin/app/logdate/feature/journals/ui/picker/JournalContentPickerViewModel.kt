package app.logdate.feature.journals.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.util.runCatchingUnlessCancelled
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.content_picker_add_failed
import org.jetbrains.compose.resources.StringResource
import kotlin.coroutines.CoroutineContext
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class JournalContentPickerViewModel(
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    private val searchRepository: SearchRepository,
    private val workContext: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private val journalIdState = MutableStateFlow<Uuid?>(null)
    private val searchQueryState = MutableStateFlow("")
    private val pageLimitState = MutableStateFlow(PAGE_SIZE)
    private val selectedState = MutableStateFlow<Map<Uuid, JournalContentPickerItem>>(emptyMap())
    private val addState = MutableStateFlow(AddState())
    private var nextLimit: Int? = null

    private val candidates: Flow<Candidates> =
        searchQueryState
            .map { query -> query.takeUnless(String::isBlank) }
            .distinctUntilChanged()
            .flatMapLatest { query ->
                if (query == null) {
                    pageLimitState.flatMapLatest { limit ->
                        journalNotesRepository
                            .observeNotesStream(limit)
                            .map { notes -> Candidates(notes, nextLimit = (limit + PAGE_SIZE).takeIf { notes.size >= limit }) }
                    }
                } else {
                    searchRepository.searchRanked(query, SEARCH_RESULT_LIMIT).map { results ->
                        val matches = journalNotesRepository.getNotesByIds(results.mapTo(mutableSetOf()) { it.uid })
                        Candidates(matches.values.toList(), nextLimit = null)
                    }
                }
            }

    private val pickerData: Flow<PickerData> =
        journalIdState
            .filterNotNull()
            .flatMapLatest { journalId ->
                combine(
                    journalRepository.observeJournalById(journalId),
                    journalContentRepository
                        .observeContentForJournal(journalId)
                        .map { members -> members.mapTo(mutableSetOf()) { it.uid } }
                        .distinctUntilChanged(),
                    candidates,
                ) { journal, memberIds, candidates ->
                    PickerData(
                        journalTitle = journal.title,
                        groups =
                            candidates.notes
                                .asSequence()
                                .filterNot { it.uid in memberIds }
                                .sortedByDescending(JournalNote::creationTimestamp)
                                .map { note -> note.toPickerItem() }
                                .toList()
                                .groupByDate(),
                        nextLimit = candidates.nextLimit,
                    )
                }
            }.flowOn(workContext)
            .onEach { data -> nextLimit = data.nextLimit }

    val uiState: StateFlow<JournalContentPickerUiState> =
        combine(pickerData, searchQueryState, selectedState, addState) { data, query, selected, add ->
            JournalContentPickerUiState(
                journalTitle = data.journalTitle,
                query = query,
                groups = data.groups,
                canLoadMore = data.nextLimit != null,
                selectedItems = selected.values.toList(),
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
        if (journalIdState.value == journalId) return
        journalIdState.value = journalId
        pageLimitState.value = PAGE_SIZE
        selectedState.value = emptyMap()
        addState.value = AddState()
    }

    fun updateSearchQuery(query: String) {
        searchQueryState.value = query
    }

    /** Extends the browsed content by one page once the picker shows the end of what is loaded. */
    fun loadMore() {
        val next = nextLimit ?: return
        pageLimitState.update { limit -> maxOf(limit, next) }
    }

    fun toggleSelection(contentId: Uuid) {
        val item =
            uiState.value.groups
                .asSequence()
                .flatMap { it.items }
                .firstOrNull { it.id == contentId }
        selectedState.update { selected ->
            when {
                contentId in selected -> selected - contentId
                item != null -> selected + (contentId to item)
                else -> selected
            }
        }
        addState.update { it.copy(error = null, addedCount = null) }
    }

    fun removeSelection(contentId: Uuid) {
        selectedState.update { it - contentId }
        addState.update { it.copy(error = null, addedCount = null) }
    }

    fun addSelected() {
        val journalId = journalIdState.value ?: return
        val contentIds = selectedState.value.keys.toList()
        if (contentIds.isEmpty() || addState.value.isAdding) return

        viewModelScope.launch {
            addState.value = AddState(isAdding = true)
            runCatchingUnlessCancelled { journalContentRepository.addContentsToJournal(contentIds, journalId) }
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

    private data class Candidates(
        val notes: List<JournalNote>,
        val nextLimit: Int?,
    )

    private data class PickerData(
        val journalTitle: String,
        val groups: List<JournalContentPickerDateGroup>,
        val nextLimit: Int?,
    )

    private data class AddState(
        val isAdding: Boolean = false,
        val error: StringResource? = null,
        val addedCount: Int? = null,
    )

    private companion object {
        const val PAGE_SIZE = 60
        const val SEARCH_RESULT_LIMIT = 200
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
