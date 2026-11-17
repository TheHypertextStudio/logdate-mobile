package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.text.UiText
import app.logdate.shared.model.HandwritingDocument
import kotlin.math.roundToInt

enum class HistoryTab { Day, Places }

enum class HistoryItemKind { Visit, Journey, Gap }

enum class HistoryMemoryKind { Text, Image, Audio, Video }

enum class HistoryEditAction { ChangePlace, ChangeActivity, ChangeTime, AddVisit, AddMemory, LinkMemory, Delete }

data class HistoryMemoryUi(
    val id: String,
    val title: UiText,
    val kind: HistoryMemoryKind,
    val supportingText: String = "",
    val thumbnailUri: String? = null,
    val audio: app.logdate.ui.timeline.MomentAudioUiState? = null,
    /** The ink of a handwritten memory, so its row shows the writing itself. */
    val handwriting: HandwritingDocument? = null,
    val handwritingUnreadable: Boolean = false,
)

data class HistoryItemUi(
    val id: String,
    val kind: HistoryItemKind,
    val title: UiText,
    val timeLabel: UiText,
    val supportingText: UiText = UiText.Raw(""),
    val memories: List<HistoryMemoryUi> = emptyList(),
    val isApproximate: Boolean = false,
)

data class HistoryPlaceUi(
    val id: String,
    val title: String,
    val supportingText: List<UiText>,
    val memories: List<HistoryMemoryUi> = emptyList(),
    val sourceIds: Set<String> = setOf(id),
)

data class HumanLocationHistoryState(
    val dateLabel: UiText,
    val daySummary: UiText = UiText.Raw(""),
    val items: List<HistoryItemUi> = emptyList(),
    val places: List<HistoryPlaceUi> = emptyList(),
    val selectedItemId: String? = null,
    val tab: HistoryTab = HistoryTab.Day,
    val placesQuery: String = "",
    val placesMapVisible: Boolean = false,
    val placesFilterLabel: UiText? = null,
    val recoveryMessage: UiText? = null,
    val detailVisible: Boolean = false,
    val replayPlaying: Boolean = false,
    val recoveryActionLabel: UiText? = null,
    val canRetry: Boolean = false,
    val selectionRevision: Long = 0,
) {
    val selectedItem: HistoryItemUi? get() = items.firstOrNull { it.id == selectedItemId }

    fun itemAtReplayPosition(position: Float): HistoryItemUi? {
        if (items.isEmpty() || position.isNaN()) return null
        return items[(position.coerceIn(0f, 1f) * items.lastIndex).roundToInt()]
    }

    fun adjacentItem(offset: Int): HistoryItemUi? {
        val index = items.indexOfFirst { it.id == selectedItemId }
        if (index < 0) return items.firstOrNull().takeIf { selectedItemId == null && offset > 0 }
        return items.getOrNull(index + offset)
    }

    fun filteredPlaces(): List<HistoryPlaceUi> = places.filter { it.title.contains(placesQuery.trim(), ignoreCase = true) }
}

class HumanLocationHistoryActions(
    val onSelectItem: (String) -> Unit = {},
    val onOpenDetail: (String) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onDayOffset: (Int) -> Unit = {},
    val onCalendar: () -> Unit = {},
    val onTab: (HistoryTab) -> Unit = {},
    val onPlacesQuery: (String) -> Unit = {},
    val onPlacesMapVisible: (Boolean) -> Unit = {},
    val onPlacesFilter: () -> Unit = {},
    val onOpenPlace: (String) -> Unit = {},
    val onOpenMemory: (String) -> Unit = {},
    val onEdit: (String?, HistoryEditAction) -> Unit = { _, _ -> },
    val onReplayPlaying: ((Boolean) -> Unit)? = null,
    val onRecover: (() -> Unit)? = null,
)
