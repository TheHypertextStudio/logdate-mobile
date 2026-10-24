package app.logdate.feature.journals.ui.merge

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.repository.journals.JournalMergeCandidate
import app.logdate.client.repository.journals.JournalMergeResult
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class JournalMergeViewModel(
    private val mergeJournals: MergeJournalsUseCase,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(JournalMergeUiState(query = savedStateHandle[QUERY] ?: ""))
    val uiState: StateFlow<JournalMergeUiState> = mutableState.asStateFlow()
    private var openedSourceId: Uuid? = null
    private var sourceId: Uuid? = null
    private var allCandidates = emptyList<JournalMergeCandidate>()
    private var recoveryId: Uuid? = null
    private var previewJob: Job? = null

    fun open(
        sourceId: Uuid,
        pendingOperationId: Uuid? = null,
    ) {
        if (openedSourceId == sourceId && recoveryId == pendingOperationId) return
        openedSourceId = sourceId
        this.sourceId = savedStateHandle.get<String>(SOURCE)?.let(Uuid::parse) ?: sourceId
        recoveryId = pendingOperationId
        savedStateHandle[SOURCE] = this.sourceId.toString()
        savedStateHandle[RECOVERY] = pendingOperationId?.toString()
        restorePickerState(pendingOperationId)
        viewModelScope.launch {
            try {
                val operationId = savedStateHandle.get<String>(OPERATION)?.let(Uuid::parse)
                val operation = (pendingOperationId ?: operationId)?.let { mergeJournals.operation(it) }
                val restoredDestination = savedStateHandle.get<String>(DESTINATION)
                val recoveredCommit =
                    pendingOperationId != null &&
                        operation != null &&
                        !operation.needsDestination &&
                        (
                            operation.operationId != pendingOperationId ||
                                savedStateHandle.get<String>(STAGE) == COMPLETE ||
                                (
                                    savedStateHandle.get<String>(STAGE) == MERGING &&
                                        operation.destinationId.toString() == restoredDestination
                                )
                        )
                if (operation != null && (pendingOperationId == null || recoveredCommit)) {
                    mutableState.update { it.copy(stage = JournalMergeStage.Complete(operation), loading = false) }
                    return@launch
                }
                operation?.let { pending ->
                    if (pendingOperationId != null && pending.needsDestination) {
                        recoveryId = pending.operationId
                        savedStateHandle[RECOVERY] = pending.operationId.toString()
                        savedStateHandle[STAGE] = PICKER
                        savedStateHandle[DESTINATION] = null
                    }
                    mutableState.update { it.copy(sourceTitle = pending.sourceTitle) }
                }
                restoreDestinationSelection()
                mergeJournals.observeCandidates().collect { candidates ->
                    allCandidates = candidates
                    refreshCandidates()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not load journals for merge", error)
                mutableState.update { it.copy(loading = false, error = JournalMergeError.Failed) }
            }
        }
    }

    private fun restoreDestinationSelection() {
        val destinationId = savedStateHandle.get<String>(DESTINATION)?.let(Uuid::parse)
        if (destinationId != null && savedStateHandle.get<String>(STAGE) != PICKER) {
            choose(
                if (mutableState.value.pickerTarget ==
                    JournalMergePickerTarget.Source
                ) {
                    requireNotNull(sourceId)
                } else {
                    destinationId
                },
            )
        }
    }

    private fun restorePickerState(pendingOperationId: Uuid?) {
        val pickingSource = savedStateHandle.get<Boolean>(PICKING_SOURCE) == true
        mutableState.update {
            it.copy(
                recovery = pendingOperationId != null,
                loading = true,
                pickerTarget = if (pickingSource) JournalMergePickerTarget.Source else JournalMergePickerTarget.Destination,
                editingSelection = savedStateHandle[EDITING] ?: false,
                query = savedStateHandle[if (pickingSource) SOURCE_QUERY else QUERY] ?: "",
            )
        }
    }

    fun updateQuery(query: String) {
        savedStateHandle[if (mutableState.value.pickerTarget == JournalMergePickerTarget.Source) SOURCE_QUERY else QUERY] = query
        mutableState.update { it.copy(query = query) }
    }

    fun choose(candidateId: Uuid) {
        val selectingSource = mutableState.value.pickerTarget == JournalMergePickerTarget.Source
        val sourceId = if (selectingSource) candidateId else sourceId ?: return
        val destinationId = if (selectingSource) savedStateHandle.get<String>(DESTINATION)?.let(Uuid::parse) ?: return else candidateId
        if (mutableState.value.stage is JournalMergeStage.Merging) return
        previewJob?.cancel()
        mutableState.update { it.copy(loading = true, error = null) }
        previewJob =
            viewModelScope.launch {
                try {
                    val preview =
                        recoveryId?.let { mergeJournals.previewPending(it, destinationId) }
                            ?: if (recoveryId == null) mergeJournals.preview(sourceId, destinationId) else null
                    if (preview == null) {
                        mutableState.update { it.copy(loading = false, error = JournalMergeError.Unavailable) }
                    } else {
                        this@JournalMergeViewModel.sourceId = preview.source.id
                        savedStateHandle[SOURCE] = preview.source.id.toString()
                        savedStateHandle[DESTINATION] = destinationId.toString()
                        savedStateHandle[PICKING_SOURCE] = false
                        savedStateHandle[EDITING] = false
                        savedStateHandle[STAGE] = REVIEW
                        mutableState.update {
                            it.copy(
                                sourceTitle = preview.source.title,
                                destinationTitle = preview.destination.title,
                                pickerTarget = JournalMergePickerTarget.Destination,
                                editingSelection = false,
                                query = savedStateHandle[QUERY] ?: "",
                                stage = JournalMergeStage.Review(preview),
                                loading = false,
                                error = null,
                            )
                        }
                        refreshCandidates()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not preview journal merge", error)
                    mutableState.update { it.copy(loading = false, error = JournalMergeError.Failed) }
                }
            }
    }

    fun changeSource() {
        if (recoveryId != null || mutableState.value.stage !is JournalMergeStage.Review) return
        openPicker(JournalMergePickerTarget.Source, returnToReview = true)
    }

    fun restoreReview() {
        val state = mutableState.value
        if (state.stage != JournalMergeStage.Picker || !state.editingSelection) return
        val selected =
            if (state.pickerTarget ==
                JournalMergePickerTarget.Source
            ) {
                sourceId
            } else {
                savedStateHandle.get<String>(DESTINATION)?.let(Uuid::parse)
            }
        selected?.let(::choose)
    }

    fun changeDestination(returnToReview: Boolean = true) {
        if (mutableState.value.stage is JournalMergeStage.Merging) return
        openPicker(JournalMergePickerTarget.Destination, returnToReview)
    }

    private fun openPicker(
        target: JournalMergePickerTarget,
        returnToReview: Boolean,
    ) {
        previewJob?.cancel()
        savedStateHandle[STAGE] = PICKER
        savedStateHandle[PICKING_SOURCE] = target == JournalMergePickerTarget.Source
        savedStateHandle[EDITING] = returnToReview
        mutableState.update {
            it.copy(
                stage = JournalMergeStage.Picker,
                pickerTarget = target,
                editingSelection = returnToReview,
                query = savedStateHandle[if (target == JournalMergePickerTarget.Source) SOURCE_QUERY else QUERY] ?: "",
                error = null,
                loading = false,
            )
        }
        refreshCandidates()
    }

    private fun refreshCandidates() {
        val destination = savedStateHandle.get<String>(DESTINATION)?.let(Uuid::parse)
        mutableState.update { state ->
            state.copy(
                destinationTitle = allCandidates.firstOrNull { it.journal.id == destination }?.journal?.title ?: state.destinationTitle,
                source = allCandidates.firstOrNull { it.journal.id == sourceId } ?: state.source,
                sourceTitle = allCandidates.firstOrNull { it.journal.id == sourceId }?.journal?.title ?: state.sourceTitle,
                candidates =
                    allCandidates.filterNot {
                        it.journal.id == if (state.pickerTarget == JournalMergePickerTarget.Source) destination else sourceId
                    },
                loading = false,
            )
        }
    }

    fun confirm() {
        val review = mutableState.value.stage as? JournalMergeStage.Review ?: return
        val operationId = recoveryId ?: savedStateHandle.get<String>(OPERATION)?.let(Uuid::parse) ?: Uuid.random()
        savedStateHandle[OPERATION] = operationId.toString()
        savedStateHandle[STAGE] = MERGING
        mutableState.update { it.copy(stage = JournalMergeStage.Merging(review.preview), error = null) }
        viewModelScope.launch {
            val result =
                try {
                    recoveryId?.let { mergeJournals.retarget(it, review.preview) } ?: mergeJournals(review.preview, operationId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not merge journals", error)
                    JournalMergeResult.Failed
                }
            savedStateHandle[STAGE] = REVIEW
            when (result) {
                is JournalMergeResult.Merged -> {
                    savedStateHandle[STAGE] = COMPLETE
                    savedStateHandle[OPERATION] = result.operation.operationId.toString()
                    savedStateHandle[DESTINATION] = result.operation.destinationId.toString()
                    mutableState.update { it.copy(stage = JournalMergeStage.Complete(result.operation)) }
                }

                is JournalMergeResult.ReviewChanged -> {
                    mutableState.update {
                        it.copy(stage = JournalMergeStage.Review(result.preview), error = JournalMergeError.Changed)
                    }
                }

                JournalMergeResult.Failed -> {
                    mutableState.update {
                        it.copy(stage = review, error = JournalMergeError.Failed)
                    }
                }

                JournalMergeResult.Unavailable -> {
                    savedStateHandle[STAGE] = PICKER
                    savedStateHandle[DESTINATION] = null
                    mutableState.update { it.copy(stage = JournalMergeStage.Picker, error = JournalMergeError.Unavailable) }
                }
            }
        }
    }

    private companion object {
        const val SOURCE = "journal_merge_source"
        const val SOURCE_QUERY = "journal_merge_source_query"
        const val PICKING_SOURCE = "journal_merge_picking_source"
        const val EDITING = "journal_merge_editing_selection"
        const val RECOVERY = "journal_merge_recovery"
        const val QUERY = "journal_merge_query"
        const val DESTINATION = "journal_merge_destination"
        const val OPERATION = "journal_merge_operation"
        const val STAGE = "journal_merge_stage"
        const val PICKER = "picker"
        const val REVIEW = "review"
        const val MERGING = "merging"
        const val COMPLETE = "complete"
    }
}
