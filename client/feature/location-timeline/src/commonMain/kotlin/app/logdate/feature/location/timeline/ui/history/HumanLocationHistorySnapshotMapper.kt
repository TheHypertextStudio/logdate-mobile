package app.logdate.feature.location.timeline.ui.history

import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.domain.location.history.RecordedDaySummary
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.repository.user.devices.UserDevice
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.util.toReadableDateShort
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_all_devices
import logdate.client.feature.location.timeline.generated.resources.history_conflicts_banner
import logdate.client.feature.location.timeline.generated.resources.history_device_numbered
import logdate.client.feature.location.timeline.generated.resources.history_location_clues_count
import logdate.client.feature.location.timeline.generated.resources.history_memories_count
import logdate.client.feature.location.timeline.generated.resources.history_partial_load_failed
import logdate.client.feature.location.timeline.generated.resources.history_range_days_fallback
import logdate.client.feature.location.timeline.generated.resources.history_recording_off_banner
import logdate.client.feature.location.timeline.generated.resources.history_recording_paused_banner
import logdate.client.feature.location.timeline.generated.resources.history_review_changes
import logdate.client.feature.location.timeline.generated.resources.history_save_failed
import logdate.client.feature.location.timeline.generated.resources.history_try_again
import logdate.client.feature.location.timeline.generated.resources.history_visits_count
import kotlin.time.Clock

/**
 * Projects a domain [LocationHistorySnapshot] and tracking settings into [HumanLocationHistoryState] using
 * localized [UiText] handles instead of hardcoded UI strings.
 */
internal fun mapSnapshotToUiState(
    current: HumanLocationHistoryState,
    data: LocationHistorySnapshot,
    tracking: LocationTrackingSettings,
    knownDevices: List<UserDevice>,
    date: LocalDate,
    activeZone: TimeZone,
    deviceZone: TimeZone,
    rangeDays: Int,
    selection: String?,
    replayTimeline: ReplayTimeline,
    failedActionPending: Boolean,
): HumanLocationHistoryState {
    val notesById = data.notes.associateBy { it.uid }
    val visitsOnlyIndices =
        data.items
            .withIndex()
            .filter { it.value is PlaceVisit }
            .map { it.index }
    val today =
        Clock.System
            .now()
            .toLocalDateTime(activeZone)
            .date
    val latestVisit = data.items.filterIsInstance<PlaceVisit>().lastOrNull()
    val latestPlace = data.places.lastOrNull()
    val updatedProgress = if (selection != null) replayTimeline.progressForItem(selection) else 0f
    val initialSample = if (current.replayPlaying) replayTimeline.sampleAt(updatedProgress) else null
    val preset = PlacesRangePreset.entries.firstOrNull { it.days == rangeDays }
    return current.copy(
        selectedItemId = selection,
        detailVisible = current.detailVisible && selection != null,
        dateLabel = formatSelectedDateLabel(date, activeZone).toUiText(),
        selectedDate = date,
        isToday = date == today,
        timezoneBadgeLabel = formatTimezoneBadge(data.effectiveZoneId, date.atStartOfDayIn(activeZone), deviceZone),
        fallbackLatitude = latestVisit?.latitude ?: latestPlace?.latitude ?: tracking.defaultLocation?.latitude,
        fallbackLongitude = latestVisit?.longitude ?: latestPlace?.longitude ?: tracking.defaultLocation?.longitude,
        replayProgress = if (current.replayPlaying) current.replayProgress else updatedProgress,
        replaySample = initialSample,
        replayTimeLabel = replayTimeDescription(initialSample, activeZone),
        daySummary = buildDaySummary(data.items),
        items =
            data.items.mapIndexed { index, item ->
                val visitPos = visitsOnlyIndices.indexOf(index)
                item.toHistoryUi(
                    notes = if (item is PlaceVisit) item.memoryIds.mapNotNull(notesById::get) else emptyList(),
                    allPlaces = data.places,
                    canMergePrevious = visitPos > 0,
                    canMergeNext = visitPos in 0 until visitsOnlyIndices.lastIndex,
                )
            },
        places = data.placeRows(),
        placesFilterLabel =
            preset?.let { UiText.Resource(it.labelRes) }
                ?: UiText.Resource(Res.string.history_range_days_fallback, listOf(rangeDays)),
        recordingDeviceOptions = buildDeviceOptions(data.recordingDevices, knownDevices),
        selectedDeviceId = data.selectedDeviceId,
        recoveryActionLabel = buildRecoveryActionLabel(failedActionPending, data),
        recoveryMessage = buildRecoveryMessage(failedActionPending, data, tracking),
        isRetryRecovery = failedActionPending || data.failedSections.isNotEmpty(),
    )
}

internal fun formatSelectedDateLabel(
    date: LocalDate,
    zone: TimeZone,
): String {
    val label = date.atStartOfDayIn(zone).toReadableDateShort()
    val currentYear =
        Clock.System
            .now()
            .toLocalDateTime(zone)
            .year
    return if (date.year == currentYear) label else "$label, ${date.year}"
}

private fun buildDeviceOptions(
    recordingDevices: List<String>,
    knownDevices: List<UserDevice>,
): List<RecordedDeviceOptionUi> {
    if (recordingDevices.size <= 1) return emptyList()
    val devicesById = knownDevices.associateBy { it.uid }
    return recordingDevices.mapIndexed { index, deviceId ->
        val known = devicesById[deviceId]
        val namedLabel = known?.label?.takeIf { it.isNotBlank() } ?: known?.model?.takeIf { it.isNotBlank() }
        val label = namedLabel?.toUiText() ?: UiText.Resource(Res.string.history_device_numbered, listOf(index + 1))
        RecordedDeviceOptionUi(id = deviceId, label = label)
    } + RecordedDeviceOptionUi(id = "all", label = UiText.Resource(Res.string.history_all_devices))
}

private fun buildDaySummary(items: List<LocationDayItem>): UiText {
    val visits = items.filterIsInstance<PlaceVisit>()
    val legs = items.filterIsInstance<JourneyLeg>()
    val confirmed = visits.count { it.confirmedStay }
    val clues = visits.size - confirmed
    val totalDistance = formatRouteDistanceText(legs.sumOf { it.distanceMeters })
    val totalMemories = visits.sumOf { it.memoryIds.size }
    val parts =
        listOfNotNull(
            UiText.Plural(Res.plurals.history_visits_count, confirmed, listOf(confirmed)),
            UiText.Plural(Res.plurals.history_location_clues_count, clues, listOf(clues)).takeIf { clues > 0 },
            totalDistance,
            UiText.Plural(Res.plurals.history_memories_count, totalMemories, listOf(totalMemories)).takeIf { totalMemories > 0 },
        )
    return UiText.Joined(parts, " · ")
}

private fun buildRecoveryActionLabel(
    failedActionPending: Boolean,
    data: LocationHistorySnapshot,
): UiText? =
    when {
        failedActionPending || data.failedSections.isNotEmpty() -> UiText.Resource(Res.string.history_try_again)
        data.conflicts.isNotEmpty() -> UiText.Resource(Res.string.history_review_changes)
        else -> null
    }

private fun buildRecoveryMessage(
    failedActionPending: Boolean,
    data: LocationHistorySnapshot,
    tracking: LocationTrackingSettings,
): UiText? =
    when {
        failedActionPending -> {
            UiText.Resource(Res.string.history_save_failed)
        }

        data.failedSections.isNotEmpty() -> {
            UiText.Resource(Res.string.history_partial_load_failed)
        }

        data.conflicts.isNotEmpty() -> {
            UiText.Resource(Res.string.history_conflicts_banner)
        }

        !tracking.backgroundTrackingEnabled && tracking.captureMode == LocationCaptureMode.PASSIVE -> {
            UiText.Resource(Res.string.history_recording_off_banner)
        }

        !tracking.backgroundTrackingEnabled -> {
            UiText.Resource(Res.string.history_recording_paused_banner)
        }

        else -> {
            null
        }
    }

internal fun toRecordedDayUiList(
    summaries: Map<LocalDate, RecordedDaySummary>,
    today: LocalDate,
): List<RecordedDayUi> =
    summaries.values.sortedBy { it.date }.map { summary ->
        RecordedDayUi(
            date = summary.date,
            dayOfWeekRes = shortDayOfWeekResource(summary.date.dayOfWeek),
            dayOfMonth = summary.date.day,
            hasVisits = summary.hasVisits,
            hasMemories = summary.hasMemories,
            isToday = summary.date == today,
        )
    }
