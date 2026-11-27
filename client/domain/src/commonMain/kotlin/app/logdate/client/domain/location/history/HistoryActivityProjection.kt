package app.logdate.client.domain.location.history

import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private const val ACTIVITY_GAP_EXPIRY_MINUTES = 30
private const val GPS_OVERRIDE_MIN_SAMPLES = 3
private const val GPS_OVERRIDE_MIN_SPAN_METERS = 150.0
private const val GPS_OVERRIDE_MIN_SPEED_MPS = 3.0
private const val GPS_OVERRIDE_MAX_ACCURACY_METERS = 50f

private data class ActivityInterval(
    val mode: TravelMode,
    val start: Instant,
    val end: Instant,
)

internal fun applyActivityEvidence(
    observations: List<LocationObservation>,
    activities: List<ActivityObservation>,
): List<LocationObservation> {
    val intervalsByDevice =
        activities
            .distinctBy { Triple(it.ownerId, it.deviceId, it.id) }
            .groupBy { it.ownerId to it.deviceId }
            .mapValues { (_, values) -> buildActivityIntervals(values) }

    val groupedIndices = observations.withIndex().groupBy { it.value.ownerId to it.value.deviceId }
    val projected = observations.toMutableList()
    groupedIndices.forEach { (deviceKey, indexedPoints) ->
        val intervals = intervalsByDevice[deviceKey].orEmpty()
        if (intervals.isEmpty()) return@forEach
        val sortedPoints = indexedPoints.sortedWith(compareBy({ it.value.timestamp }, { it.value.id }))
        projectIntervalsForDevice(sortedPoints, intervals, projected)
        overrideStaleStillRuns(sortedPoints, observations, projected)
    }
    return projected
}

private fun projectIntervalsForDevice(
    sortedPoints: List<IndexedValue<LocationObservation>>,
    intervals: List<ActivityInterval>,
    projected: MutableList<LocationObservation>,
) {
    var lastEvidenceAt: Instant? = null
    val activeIntervalStartSeen = mutableSetOf<Int>()

    for (entry in sortedPoints) {
        val point = entry.value
        val prevEvidenceAt = lastEvidenceAt
        lastEvidenceAt = point.timestamp
        if (point.activity != TravelMode.UNKNOWN) continue

        val intervalIdx = intervals.indexOfFirst { point.timestamp >= it.start && point.timestamp < it.end }
        if (intervalIdx < 0) continue

        val interval = intervals[intervalIdx]
        val referenceTime =
            if (intervalIdx in activeIntervalStartSeen && prevEvidenceAt != null && prevEvidenceAt >= interval.start) {
                prevEvidenceAt
            } else {
                interval.start
            }
        activeIntervalStartSeen += intervalIdx
        // Orphaned transitions across long silence gaps > 30m expire instead of projecting indefinitely.
        if (point.timestamp - referenceTime <= ACTIVITY_GAP_EXPIRY_MINUTES.minutes) {
            projected[entry.index] = point.copy(activity = interval.mode)
        }
    }
}

/**
 * Clears projected [TravelMode.STILL] tags when consecutive precise GPS fixes prove sustained movement.
 */
private fun overrideStaleStillRuns(
    sortedPoints: List<IndexedValue<LocationObservation>>,
    original: List<LocationObservation>,
    projected: MutableList<LocationObservation>,
) {
    var runStart = 0
    while (runStart < sortedPoints.size) {
        val curr = projected[sortedPoints[runStart].index]
        val currAcc = curr.accuracyMeters
        if (curr.activity != TravelMode.STILL || currAcc == null || currAcc > GPS_OVERRIDE_MAX_ACCURACY_METERS) {
            runStart++
            continue
        }
        val runEnd = findHighSpeedStillRunEnd(sortedPoints, projected, runStart)
        if (runEnd - runStart >= GPS_OVERRIDE_MIN_SAMPLES) {
            val span = distanceMeters(projected[sortedPoints[runStart].index], projected[sortedPoints[runEnd - 1].index])
            if (span >= GPS_OVERRIDE_MIN_SPAN_METERS) {
                for (k in runStart until runEnd) {
                    val origIndex = sortedPoints[k].index
                    if (original[origIndex].activity == TravelMode.UNKNOWN) {
                        projected[origIndex] = projected[origIndex].copy(activity = TravelMode.UNKNOWN)
                    }
                }
            }
        }
        runStart = if (runEnd == runStart + 1) runStart + 1 else runEnd
    }
}

private fun findHighSpeedStillRunEnd(
    sortedPoints: List<IndexedValue<LocationObservation>>,
    projected: List<LocationObservation>,
    runStart: Int,
): Int {
    var runEnd = runStart + 1
    while (runEnd < sortedPoints.size) {
        val next = projected[sortedPoints[runEnd].index]
        val nextAcc = next.accuracyMeters
        if (next.activity != TravelMode.STILL || nextAcc == null || nextAcc > GPS_OVERRIDE_MAX_ACCURACY_METERS) break
        val prev = projected[sortedPoints[runEnd - 1].index]
        val dt = (next.timestamp - prev.timestamp).inWholeMilliseconds / 1000.0
        val segSpeed = if (dt > 0.0) distanceMeters(prev, next) / dt else 0.0
        if (segSpeed < GPS_OVERRIDE_MIN_SPEED_MPS) break
        runEnd++
    }
    return runEnd
}

private fun buildActivityIntervals(events: List<ActivityObservation>): List<ActivityInterval> {
    if (events.isEmpty()) return emptyList()
    val byTimestamp = events.groupBy { it.timestamp }.entries.sortedBy { it.key }
    val intervals = mutableListOf<ActivityInterval>()
    var activeType: String? = null
    var activeStart: Instant? = null
    for ((timestamp, atTime) in byTimestamp.map { it.key to it.value }) {
        val exits = atTime.filter { it.transitionType == "EXIT" }
        val enters = atTime.filter { it.transitionType == "ENTER" }
        if (activeType != null && activeStart != null && exits.any { it.activityType == activeType }) {
            if (timestamp > activeStart) intervals += ActivityInterval(activeType.toTravelMode(), activeStart, timestamp)
            activeType = null
            activeStart = null
        }
        for (enter in enters) {
            val sameTimeExit = exits.any { it.activityType == enter.activityType }
            if (activeType != null && activeStart != null && timestamp > activeStart) {
                intervals += ActivityInterval(activeType.toTravelMode(), activeStart, timestamp)
            }
            activeType = if (sameTimeExit) null else enter.activityType
            activeStart = if (sameTimeExit) null else timestamp
        }
    }
    if (activeType != null && activeStart != null) {
        intervals += ActivityInterval(activeType.toTravelMode(), activeStart, Instant.DISTANT_FUTURE)
    }
    return intervals
}

internal fun LocationHistoryItem.toObservation(owner: String) =
    LocationObservation(
        id = sampleId,
        ownerId = owner,
        deviceId = deviceId,
        timestamp = timestamp,
        latitude = location.latitude,
        longitude = location.longitude,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        activity = activityType.toTravelMode(),
        timeZoneId = timeZoneId,
        source = captureSource.name,
        isMock = isMock,
        provider = provider,
        speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond,
        verticalAccuracyMeters = verticalAccuracyMeters,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )

internal fun String?.toTravelMode(): TravelMode =
    when (this) {
        "WALKING", "ON_FOOT" -> TravelMode.WALKING
        "RUNNING" -> TravelMode.RUNNING
        "ON_BICYCLE" -> TravelMode.CYCLING
        "IN_VEHICLE" -> TravelMode.VEHICLE
        "STILL" -> TravelMode.STILL
        else -> TravelMode.UNKNOWN
    }
