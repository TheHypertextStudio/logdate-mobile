package app.logdate.feature.core.settings.account.plan.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.storage.StoredItem
import app.logdate.client.domain.storage.StoredItemsDeletion
import app.logdate.client.domain.storage.StoredKind
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** What the last deletion did. */
data class CleanupOutcome(
    val freedBytes: Long,
    val deletedCount: Int,
    val failedCount: Int,
)

sealed interface StorageCleanupUiState {
    data object Loading : StorageCleanupUiState

    /**
     * Everything of one kind, largest first, and what is chosen for deleting. [outcome] is the
     * last deletion's result, until something is chosen again.
     */
    data class Ready(
        val items: List<StoredItem>,
        val selected: Set<Uuid> = emptySet(),
        val deleting: Boolean = false,
        val outcome: CleanupOutcome? = null,
    ) : StorageCleanupUiState {
        /** The room everything listed takes on this device. */
        val totalBytes: Long get() = items.sumOf { it.sizeBytes ?: 0L }

        val selectedItems: List<StoredItem> get() = items.filter { it.id in selected }

        /** The room deleting the chosen items frees. */
        val selectedBytes: Long get() = selectedItems.sumOf { it.sizeBytes ?: 0L }
    }
}

/**
 * The clean-up page for one [kind] of stored thing: choose several and delete them.
 *
 * Deleting goes through the same path as deleting one note, so it reaches the server on the next
 * sync. Items that could not be deleted stay chosen, so trying again is one tap.
 */
class StorageCleanupViewModel(
    val kind: StoredKind,
    observeItems: (StoredKind) -> Flow<List<StoredItem>>,
    private val deleteItems: suspend (List<StoredItem>) -> StoredItemsDeletion,
) : ViewModel() {
    private val _state = MutableStateFlow<StorageCleanupUiState>(StorageCleanupUiState.Loading)
    val state: StateFlow<StorageCleanupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            observeItems(kind)
                .catch { error ->
                    Napier.e("Could not list stored $kind", error)
                    emit(emptyList())
                }.collect { items ->
                    val ids = items.mapTo(mutableSetOf()) { it.id }
                    _state.update { current ->
                        when (current) {
                            StorageCleanupUiState.Loading -> StorageCleanupUiState.Ready(items)
                            is StorageCleanupUiState.Ready -> current.copy(items = items, selected = current.selected intersect ids)
                        }
                    }
                }
        }
    }

    /** Chooses the item [id], or lets go of it. */
    fun toggle(id: Uuid) {
        updateReady { state ->
            if (state.deleting) return@updateReady state
            state.copy(selected = if (id in state.selected) state.selected - id else state.selected + id, outcome = null)
        }
    }

    fun clearSelection() {
        updateReady { state -> if (state.deleting) state else state.copy(selected = emptySet(), outcome = null) }
    }

    /** Deletes everything chosen. */
    fun deleteSelected() {
        val ready = _state.value as? StorageCleanupUiState.Ready ?: return
        val chosen = ready.selectedItems
        if (chosen.isEmpty() || ready.deleting) return
        updateReady { it.copy(deleting = true, outcome = null) }
        viewModelScope.launch {
            val result = deleteItems(chosen)
            updateReady { state ->
                state.copy(
                    deleting = false,
                    selected = result.failed.mapTo(mutableSetOf()) { it.id } intersect state.items.mapTo(mutableSetOf()) { it.id },
                    outcome = CleanupOutcome(result.freedBytes, result.deleted.size, result.failed.size),
                )
            }
        }
    }

    /** Lets go of the last deletion's result once it has been shown. */
    fun dismissOutcome() {
        updateReady { it.copy(outcome = null) }
    }

    private fun updateReady(change: (StorageCleanupUiState.Ready) -> StorageCleanupUiState.Ready) {
        _state.update { if (it is StorageCleanupUiState.Ready) change(it) else it }
    }
}
