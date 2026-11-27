package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.HistoryGap
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.LocationObservation
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal const val ALL_DEVICES_SOURCE = "all"

internal data class DaySources(
    val items: List<LocationDayItem>,
    val devices: List<String>,
    val preferred: String,
)

internal fun LocationDayItem.overlaps(
    start: Instant,
    end: Instant,
): Boolean = this.start < end && (this.end > start || (this.start == this.end && this.start >= start))

internal fun resolveCaptureZone(
    observations: List<LocationObservation>,
    windowStart: Instant,
    windowEnd: Instant,
    fallbackZone: TimeZone,
): Pair<TimeZone, Boolean> {
    val windowObservations =
        observations.filter {
            it.timestamp >= windowStart - 14.hours && it.timestamp < windowEnd + 14.hours
        }
    val validZones =
        windowObservations.mapNotNull { obs ->
            obs.timeZoneId?.let { id -> runCatching { TimeZone.of(id) }.getOrNull()?.let { obs to it } }
        }
    val effective =
        validZones
            .groupingBy { (_, tz) -> tz.id }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?.let { runCatching { TimeZone.of(it) }.getOrNull() }
            ?: fallbackZone
    val distinctOffsets = validZones.map { (obs, tz) -> tz.offsetAt(obs.timestamp).totalSeconds }.distinct()
    return effective to (distinctOffsets.size >= 2)
}

internal fun selectDaySources(
    inferredByDevice: Map<String, List<LocationDayItem>>,
    payloads: List<HistoryPayload>,
    first: LocalDate,
    last: LocalDate,
    zone: TimeZone,
    sourceDeviceId: String?,
    localDevice: String,
): DaySources {
    val edits = payloads.filterIsInstance<HistoryPayload.Correction>().map { it.value }
    val correctedByDevice = inferredByDevice.mapValues { (_, items) -> ApplyHistoryEdits()(items, edits, emptyList()) }
    // Collections union each day's chosen source while preserving original item identities and intervals.
    val selectedItems = linkedMapOf<String, LocationDayItem>()
    val availableDevices = mutableSetOf<String>()
    var preferred = ""
    var day = first
    while (day <= last) {
        val dayStart = day.atStartOfDayIn(zone)
        val nextDay = day.plus(DatePeriod(days = 1))
        val dayEnd = nextDay.atStartOfDayIn(zone)
        val available = correctedByDevice.filterValues { items -> items.any { it.overlaps(dayStart, dayEnd) } }.keys.sorted()
        availableDevices.addAll(available)
        if (sourceDeviceId == ALL_DEVICES_SOURCE && available.isNotEmpty()) {
            val primary = localDevice.takeIf { it in available } ?: available.first()
            preferred = ALL_DEVICES_SOURCE
            fuseMultiDeviceItems(inferredByDevice, primary, dayStart, dayEnd).forEach { selectedItems[it.id] = it }
        } else {
            preferred =
                sourceDeviceId?.takeIf { it in available }
                    ?: localDevice.takeIf { it in available }
                    ?: available.firstOrNull().orEmpty()
            val selectedIds =
                correctedByDevice[preferred]
                    .orEmpty()
                    .filter { it.overlaps(dayStart, dayEnd) }
                    .map { it.id }
                    .toSet()
            inferredByDevice[preferred].orEmpty().filter { it.id in selectedIds }.forEach { selectedItems[it.id] = it }
        }
        day = nextDay
    }
    return DaySources(selectedItems.values.sortedBy { it.start }, availableDevices.sorted(), preferred)
}

internal fun fuseMultiDeviceItems(
    byDevice: Map<String, List<LocationDayItem>>,
    primaryDevice: String,
    dayStart: Instant,
    dayEnd: Instant,
    maximumGap: Duration = 10.minutes,
): List<LocationDayItem> {
    val primaryAll = byDevice[primaryDevice].orEmpty().filter { it.overlaps(dayStart, dayEnd) }.sortedBy { it.start }
    val primaryItems = primaryAll.filterNot { it is HistoryGap }
    if (primaryItems.isEmpty()) {
        return byDevice.values
            .flatten()
            .filter { it.overlaps(dayStart, dayEnd) }
            .sortedBy { it.start }
    }
    val gaps = findPrimaryGaps(primaryItems, dayStart, dayEnd, maximumGap)
    val spliced = primaryItems.toMutableList()
    val secondary =
        byDevice
            .filterKeys { it != primaryDevice }
            .values
            .flatten()
            .filter { it !is HistoryGap && it.overlaps(dayStart, dayEnd) }
            .sortedBy { it.start }
    for (candidate in secondary) {
        val fitsInGap = gaps.any { (gapStart, gapEnd) -> candidate.start >= gapStart && candidate.end <= gapEnd }
        val overlapsAlreadyAdded = spliced.any { existing -> candidate.start < existing.end && candidate.end > existing.start }
        if (fitsInGap && !overlapsAlreadyAdded) {
            spliced += candidate
        }
    }
    primaryAll.filterIsInstance<HistoryGap>().forEach { gap ->
        if (spliced.none { it.start < gap.end && it.end > gap.start }) {
            spliced += gap
        }
    }
    return spliced.sortedBy { it.start }
}

private fun findPrimaryGaps(
    primaryItems: List<LocationDayItem>,
    dayStart: Instant,
    dayEnd: Instant,
    maximumGap: Duration,
): List<Pair<Instant, Instant>> =
    buildList {
        if (primaryItems.first().start - dayStart > maximumGap) add(dayStart to primaryItems.first().start)
        primaryItems.zipWithNext().forEach { (prev, next) ->
            if (next.start - prev.end > maximumGap) add(prev.end to next.start)
        }
        if (dayEnd - primaryItems.last().end > maximumGap) add(primaryItems.last().end to dayEnd)
    }
