package app.logdate.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Manages multi-selection state for lists and grids.
 *
 * Supports:
 * - **Ctrl+click / Cmd+click**: Toggle individual item selection
 * - **Plain click**: Clear selection and navigate (single select)
 *
 * Use [rememberMultiSelectState] to create an instance.
 */
@Stable
class MultiSelectState {
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    var lastSelectedId by mutableStateOf<String?>(null)
        private set

    val hasSelection: Boolean get() = selectedIds.isNotEmpty()
    val selectionCount: Int get() = selectedIds.size

    fun toggle(id: String) {
        selectedIds =
            if (id in selectedIds) {
                selectedIds - id
            } else {
                selectedIds + id
            }
        lastSelectedId = id
    }

    fun selectAll(allIds: List<String>) {
        selectedIds = allIds.toSet()
    }

    fun clear() {
        selectedIds = emptySet()
        lastSelectedId = null
    }

    fun isSelected(id: String): Boolean = id in selectedIds
}

/**
 * Creates and remembers a [MultiSelectState] that survives configuration changes.
 */
@Composable
fun rememberMultiSelectState(): MultiSelectState =
    rememberSaveable(
        saver =
            Saver(
                save = { it.selectedIds.toList() },
                restore = { saved ->
                    MultiSelectState().also { state ->
                        saved.forEach { state.toggle(it) }
                    }
                },
            ),
    ) {
        MultiSelectState()
    }
