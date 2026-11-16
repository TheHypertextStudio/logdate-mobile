package app.logdate.feature.journals.ui.picker

import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class JournalContentPickerUiState(
    val journalTitle: String = "",
    val query: String = "",
    val groups: List<JournalContentPickerDateGroup> = emptyList(),
    val canLoadMore: Boolean = false,
    val selectedItems: List<JournalContentPickerItem> = emptyList(),
    val isAdding: Boolean = false,
    val addError: StringResource? = null,
    val addedCount: Int? = null,
)

data class JournalContentPickerDateGroup(
    val date: LocalDate,
    val items: List<JournalContentPickerItem>,
)

/**
 * One piece of content the picker can add to a journal.
 *
 * @property title The content's own words: the writing, a caption, or a place name. Null when the
 *   content has none, in which case the picker shows the label for its [kind].
 */
data class JournalContentPickerItem(
    val id: Uuid,
    val kind: JournalContentPickerItemKind,
    val timestamp: Instant,
    val title: String?,
    val mediaRef: String? = null,
)

enum class JournalContentPickerItemKind {
    WRITING,
    PHOTO,
    VIDEO,
    RECORDING,
}
