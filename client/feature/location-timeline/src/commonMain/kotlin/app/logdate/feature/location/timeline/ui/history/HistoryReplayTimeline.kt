package app.logdate.feature.location.timeline.ui.history

import androidx.compose.runtime.Immutable
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.HistoryGap
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import kotlinx.datetime.TimeZone
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant

@Immutable
data class ReplaySample(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Float,
    val progress: Float,
    val activeItemId: String,
    val timestamp: Instant,
    val mode: TravelMode? = null,
    val isJourney: Boolean = false,
)

@Immutable
internal data class ReplaySegment(
    val itemId: String,
    val start: Instant,
    val end: Instant,
    val weight: Double,
    val points: List<HistoryCoordinate>,
    val cumulativeDistancesMeters: List<Double>,
    val mode: TravelMode?,
    val isJourney: Boolean,
)

@Immutable
internal data class ReplayTimeline(
    val segments: List<ReplaySegment>,
    val totalWeight: Double,
) {
    fun sampleAt(progress: Float): ReplaySample? {
        if (segments.isEmpty() || totalWeight <= 0.0 || progress.isNaN()) return null
        val clamped = progress.coerceIn(0f, 1f)
        val targetWeight = clamped.toDouble() * totalWeight
        var accumulated = 0.0
        var active = segments.last()
        var localFraction = 1.0
        for (segment in segments) {
            val nextAccumulated = accumulated + segment.weight
            if (targetWeight <= nextAccumulated || segment === segments.last()) {
                active = segment
                localFraction =
                    if (segment.weight > 0.0) {
                        ((targetWeight - accumulated) / segment.weight).coerceIn(0.0, 1.0)
                    } else {
                        1.0
                    }
                break
            }
            accumulated = nextAccumulated
        }
        val (coord, bearing) = interpolateAlong(active.points, active.cumulativeDistancesMeters, localFraction)
        val startMs = active.start.toEpochMilliseconds()
        val endMs = active.end.toEpochMilliseconds().coerceAtLeast(startMs)
        val timestampMs = startMs + ((endMs - startMs) * localFraction).toLong()
        return ReplaySample(
            latitude = coord.latitude,
            longitude = coord.longitude,
            bearingDegrees = bearing,
            progress = clamped,
            activeItemId = active.itemId,
            timestamp = Instant.fromEpochMilliseconds(timestampMs),
            mode = active.mode,
            isJourney = active.isJourney,
        )
    }

    fun progressForItem(itemId: String): Float {
        if (segments.isEmpty() || totalWeight <= 0.0) return 0f
        var accumulated = 0.0
        for (segment in segments) {
            if (segment.itemId == itemId) {
                return ((accumulated + segment.weight * 0.5) / totalWeight).toFloat().coerceIn(0f, 1f)
            }
            accumulated += segment.weight
        }
        return 0f
    }
}

internal fun buildReplayTimeline(items: List<LocationDayItem>): ReplayTimeline {
    if (items.isEmpty()) return ReplayTimeline(emptyList(), 0.0)
    var lastKnownCoord: HistoryCoordinate? = null
    val segments =
        items.mapNotNull { item ->
            val rawPoints =
                when (item) {
                    is PlaceVisit -> {
                        if (isValidCoordinate(item.latitude, item.longitude)) {
                            listOf(HistoryCoordinate(item.latitude, item.longitude)).also { lastKnownCoord = it.first() }
                        } else {
                            emptyList()
                        }
                    }

                    is JourneyLeg -> {
                        val routePoints =
                            (item.smoothedRoute.ifEmpty { item.route })
                                .filter { isValidCoordinate(it.latitude, it.longitude) }
                                .map { HistoryCoordinate(it.latitude, it.longitude) }
                        val resolved =
                            when {
                                routePoints.isNotEmpty() -> {
                                    routePoints
                                }

                                item.endpointCoordinates != null -> {
                                    val ends = item.endpointCoordinates!!
                                    listOf(
                                        HistoryCoordinate(ends.first.first, ends.first.second),
                                        HistoryCoordinate(ends.second.first, ends.second.second),
                                    ).filter { isValidCoordinate(it.latitude, it.longitude) }
                                }

                                else -> {
                                    emptyList()
                                }
                            }
                        if (resolved.isNotEmpty()) lastKnownCoord = resolved.last()
                        resolved
                    }

                    is HistoryGap -> {
                        listOfNotNull(lastKnownCoord)
                    }
                }
            if (rawPoints.isEmpty()) return@mapNotNull null
            val durationSeconds = (item.end - item.start).inWholeSeconds.coerceAtLeast(30L).toDouble()
            val cumulative = cumulativeDistances(rawPoints)
            ReplaySegment(
                itemId = item.id,
                start = item.start,
                end = item.end,
                weight = durationSeconds,
                points = rawPoints,
                cumulativeDistancesMeters = cumulative,
                mode = (item as? JourneyLeg)?.mode,
                isJourney = item is JourneyLeg,
            )
        }
    return ReplayTimeline(segments, segments.sumOf { it.weight })
}

internal fun applyReplayStep(
    current: HumanLocationHistoryState,
    timeline: ReplayTimeline,
    progress: Float,
    zone: TimeZone,
    resetDetail: Boolean = false,
): HumanLocationHistoryState {
    val clamped = progress.coerceIn(0f, 1f)
    val sample = timeline.sampleAt(clamped)
    val itemId = sample?.activeItemId ?: current.itemAtReplayPosition(clamped)?.id
    val changed = itemId != null && itemId != current.selectedItemId
    return current.copy(
        replayProgress = clamped,
        replaySample = sample,
        replayTimeLabel = replayTimeDescription(sample, zone),
        selectedItemId = itemId ?: current.selectedItemId,
        detailVisible = if (resetDetail) false else current.detailVisible,
        selectionRevision = if (changed) current.selectionRevision + 1 else current.selectionRevision,
    )
}

internal fun nextReplaySpeed(currentSpeed: Float): Float =
    when (currentSpeed) {
        1f -> 2f
        2f -> 4f
        else -> 1f
    }

internal fun replayTimeDescription(
    sample: ReplaySample?,
    zone: TimeZone,
): UiText? {
    if (sample == null) return null
    val timeLabel = sample.timestamp.formatLocalTimeLabel(zone).toUiText()
    val modeLabel = sample.mode?.takeIf { sample.isJourney }?.labelText()
    return if (modeLabel != null) {
        UiText.Joined(listOf(timeLabel, modeLabel), " · ")
    } else {
        timeLabel
    }
}

private fun cumulativeDistances(points: List<HistoryCoordinate>): List<Double> {
    if (points.isEmpty()) return emptyList()
    val result = ArrayList<Double>(points.size)
    result += 0.0
    var total = 0.0
    for (i in 1 until points.size) {
        total += haversineMeters(points[i - 1], points[i])
        result += total
    }
    return result
}

private fun interpolateAlong(
    points: List<HistoryCoordinate>,
    cumulative: List<Double>,
    fraction: Double,
): Pair<HistoryCoordinate, Float> {
    if (points.size == 1 || cumulative.lastOrNull() == 0.0) {
        val bearing = if (points.size >= 2) bearingDegrees(points.first(), points.last()) else 0f
        return points.first() to bearing
    }
    val totalLength = cumulative.last()
    val targetDistance = fraction.coerceIn(0.0, 1.0) * totalLength
    for (i in 1 until points.size) {
        val prevDist = cumulative[i - 1]
        val nextDist = cumulative[i]
        if (targetDistance <= nextDist || i == points.lastIndex) {
            val segmentLength = (nextDist - prevDist).coerceAtLeast(1e-6)
            val localT = ((targetDistance - prevDist) / segmentLength).coerceIn(0.0, 1.0)
            val a = points[i - 1]
            val b = points[i]
            val lat = a.latitude + (b.latitude - a.latitude) * localT
            val lon = a.longitude + (b.longitude - a.longitude) * localT
            return HistoryCoordinate(lat, lon) to bearingDegrees(a, b)
        }
    }
    return points.last() to bearingDegrees(points[points.lastIndex - 1], points.last())
}

private fun haversineMeters(
    a: HistoryCoordinate,
    b: HistoryCoordinate,
): Double {
    val dLat = (b.latitude - a.latitude) * PI / 180.0
    val dLon = (b.longitude - a.longitude) * PI / 180.0
    val lat1 = a.latitude * PI / 180.0
    val lat2 = b.latitude * PI / 180.0
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 6_371_000.0 * 2.0 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1.0 - h).coerceIn(0.0, 1.0)))
}

private fun bearingDegrees(
    from: HistoryCoordinate,
    to: HistoryCoordinate,
): Float {
    if (from == to) return 0f
    val lat1 = from.latitude * PI / 180.0
    val lat2 = to.latitude * PI / 180.0
    val dLon = (to.longitude - from.longitude) * PI / 180.0
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (((atan2(y, x) * 180.0 / PI) + 360.0) % 360.0).toFloat()
}

private fun isValidCoordinate(
    latitude: Double,
    longitude: Double,
): Boolean = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
