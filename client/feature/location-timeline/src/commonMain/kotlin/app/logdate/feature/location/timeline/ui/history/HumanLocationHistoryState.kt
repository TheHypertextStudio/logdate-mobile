package app.logdate.feature.location.timeline.ui.history

import androidx.compose.runtime.Immutable
import app.logdate.client.text.UiText
import app.logdate.shared.model.HandwritingDocument
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import kotlinx.datetime.LocalDate
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.all_time
import logdate.client.feature.location.timeline.generated.resources.history_sort_alphabetical
import logdate.client.feature.location.timeline.generated.resources.history_sort_most_memories
import logdate.client.feature.location.timeline.generated.resources.history_sort_most_visited
import logdate.client.feature.location.timeline.generated.resources.history_sort_recent
import logdate.client.feature.location.timeline.generated.resources.history_today
import logdate.client.feature.location.timeline.generated.resources.last_30_days
import logdate.client.feature.location.timeline.generated.resources.last_90_days
import logdate.client.feature.location.timeline.generated.resources.year_to_date
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

enum class HistoryTab { Day, Places }

enum class HistoryItemKind { Visit, Journey, Gap }

enum class HistoryMemoryKind { Text, Image, Audio, Video }

enum class HistoryEditAction { ChangePlace, ChangeActivity, ChangeTime, AddVisit, AddMemory, LinkMemory, Delete }

enum class PlacesSortOption(
    val labelRes: StringResource,
) {
    Recent(Res.string.history_sort_recent),
    MostVisited(Res.string.history_sort_most_visited),
    MostMemories(Res.string.history_sort_most_memories),
    Alphabetical(Res.string.history_sort_alphabetical),
}

enum class PlacesRangePreset(
    val labelRes: StringResource,
    val days: Int,
) {
    Days30(Res.string.last_30_days, 30),
    Days90(Res.string.last_90_days, 90),
    ThisYear(Res.string.year_to_date, 365),
    AllTime(Res.string.all_time, 3650),
}

@Immutable
data class PlaceCandidateChipUi(
    val place: SemanticPlace,
    val label: UiText,
    val isConfirmation: Boolean = false,
)

@Immutable
data class HistoryMemoryUi(
    val id: Uuid,
    val title: UiText,
    val kind: HistoryMemoryKind,
    val supportingText: UiText? = null,
    val thumbnailUri: String? = null,
    val audio: app.logdate.ui.timeline.MomentAudioUiState? = null,
    /** The ink of a handwritten memory, so its row shows the writing itself. */
    val handwriting: HandwritingDocument? = null,
    val handwritingUnreadable: Boolean = false,
)

@Immutable
data class HistoryItemUi(
    val id: String,
    val kind: HistoryItemKind,
    val title: UiText,
    val timeLabel: UiText,
    val supportingText: UiText? = null,
    val memories: List<HistoryMemoryUi> = emptyList(),
    val isApproximate: Boolean = false,
    val travelMode: TravelMode? = null,
    val isConnector: Boolean = false,
    val placeId: String? = null,
    val placeCategory: String? = null,
    val placeConfirmed: Boolean = true,
    val disambiguationCandidates: List<PlaceCandidateChipUi> = emptyList(),
    val canMergePrevious: Boolean = false,
    val canMergeNext: Boolean = false,
    val canSplit: Boolean = false,
    val anchorEvidenceId: String? = null,
)

@Immutable
data class HistoryPlaceUi(
    val id: String,
    val title: UiText,
    val supportingText: UiText,
    val memories: List<HistoryMemoryUi> = emptyList(),
    val sourceIds: Set<String> = setOf(id),
    val category: String? = null,
    val visitCount: Int = 0,
    val latestTimestampMs: Long = 0L,
)

@Immutable
data class RecordedDeviceOptionUi(
    val id: String,
    val label: UiText,
)

@Immutable
data class HumanLocationHistoryState(
    val dateLabel: UiText = UiText.Resource(Res.string.history_today),
    val daySummary: UiText? = null,
    val items: List<HistoryItemUi> = emptyList(),
    val places: List<HistoryPlaceUi> = emptyList(),
    val selectedItemId: String? = null,
    val tab: HistoryTab = HistoryTab.Day,
    val placesQuery: String = "",
    val placesMapVisible: Boolean = false,
    val placesFilterLabel: UiText? = null,
    val placesRangePreset: PlacesRangePreset = PlacesRangePreset.Days30,
    val placesSort: PlacesSortOption = PlacesSortOption.Recent,
    val placesCategoryFilter: String? = null,
    val recordingDeviceOptions: List<RecordedDeviceOptionUi> = emptyList(),
    val selectedDeviceId: String = "",
    val recoveryMessage: UiText? = null,
    val detailVisible: Boolean = false,
    val replayPlaying: Boolean = false,
    val recoveryActionLabel: UiText? = null,
    val isRetryRecovery: Boolean = false,
    val selectionRevision: Long = 0,
    val replayProgress: Float = 0f,
    val replaySpeed: Float = 1f,
    val replayTimeLabel: UiText? = null,
    val replaySample: ReplaySample? = null,
    val isToday: Boolean = true,
    val selectedDate: LocalDate? = null,
    val timezoneBadgeLabel: UiText? = null,
    val weekDays: List<RecordedDayUi> = emptyList(),
    val calendarMonthLabel: UiText? = null,
    val calendarDays: List<RecordedDayUi> = emptyList(),
    val latestRecordedDate: LocalDate? = null,
    val fallbackLatitude: Double? = null,
    val fallbackLongitude: Double? = null,
) {
    val selectedItem: HistoryItemUi? get() = items.firstOrNull { it.id == selectedItemId }

    internal fun fallbackCoordinate(): HistoryCoordinate? =
        if (fallbackLatitude != null && fallbackLongitude != null) {
            HistoryCoordinate(fallbackLatitude, fallbackLongitude)
        } else {
            null
        }

    fun itemAtReplayPosition(position: Float): HistoryItemUi? {
        if (items.isEmpty() || position.isNaN()) return null
        return items[(position.coerceIn(0f, 1f) * items.lastIndex).roundToInt()]
    }

    fun adjacentItem(offset: Int): HistoryItemUi? {
        val index = items.indexOfFirst { it.id == selectedItemId }
        if (index < 0) return items.firstOrNull().takeIf { selectedItemId == null && offset > 0 }
        return items.getOrNull(index + offset)
    }

    fun filteredPlaces(): List<HistoryPlaceUi> {
        val query = placesQuery.trim()
        val category = placesCategoryFilter
        val filtered =
            places.filter { place ->
                (query.isEmpty() || place.title.literalOrEmpty().contains(query, ignoreCase = true)) &&
                    (category == null || place.category.equals(category, ignoreCase = true))
            }
        return when (placesSort) {
            PlacesSortOption.Recent -> {
                filtered.sortedByDescending { it.latestTimestampMs }
            }

            PlacesSortOption.MostVisited -> {
                filtered.sortedWith(
                    compareByDescending<HistoryPlaceUi> { it.visitCount }
                        .thenByDescending { it.latestTimestampMs },
                )
            }

            PlacesSortOption.MostMemories -> {
                filtered.sortedWith(
                    compareByDescending<HistoryPlaceUi> { it.memories.size }
                        .thenByDescending { it.latestTimestampMs },
                )
            }

            PlacesSortOption.Alphabetical -> {
                filtered.sortedBy { it.title.literalOrEmpty().lowercase() }
            }
        }
    }
}

class HumanLocationHistoryActions(
    val onSelectItem: (String) -> Unit = {},
    val onOpenDetail: (String) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onDayOffset: (Int) -> Unit = {},
    val onSelectDate: ((LocalDate) -> Unit)? = null,
    val onJumpToToday: (() -> Unit)? = null,
    val onCalendar: () -> Unit = {},
    val onCalendarMonthOffset: ((Int) -> Unit)? = null,
    val onJumpToLatest: (() -> Unit)? = null,
    val onTab: (HistoryTab) -> Unit = {},
    val onPlacesQuery: (String) -> Unit = {},
    val onPlacesMapVisible: (Boolean) -> Unit = {},
    val onPlacesFilter: () -> Unit = {},
    val onPlacesRangePreset: ((PlacesRangePreset) -> Unit)? = null,
    val onPlacesSort: ((PlacesSortOption) -> Unit)? = null,
    val onPlacesCategoryFilter: ((String?) -> Unit)? = null,
    val onConfirmPlace: ((visitId: String, place: SemanticPlace) -> Unit)? = null,
    val onOpenPlace: (String) -> Unit = {},
    val onOpenMemory: (Uuid) -> Unit = {},
    val onEdit: (String?, HistoryEditAction) -> Unit = { _, _ -> },
    val onReplayPlaying: ((Boolean) -> Unit)? = null,
    val onReplayScrub: ((Float) -> Unit)? = null,
    val onReplaySpeed: (() -> Unit)? = null,
    val onRecover: (() -> Unit)? = null,
)
