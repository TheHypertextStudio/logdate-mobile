package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode

/** Recorded activities that mean the person was travelling rather than staying put. */
internal val MOVING_MODES =
    setOf(
        TravelMode.WALKING,
        TravelMode.RUNNING,
        TravelMode.CYCLING,
        TravelMode.VEHICLE,
        TravelMode.DRIVING,
        TravelMode.BUS,
        TravelMode.TRAIN,
    )

/** Activities that rule out a visit even when the position barely changes, such as sitting in traffic. */
internal val RIDING_MODES = MOVING_MODES - setOf(TravelMode.WALKING, TravelMode.RUNNING)

/** Accuracy assumed for a fix that did not report one when weighting positions. */
private const val UNKNOWN_ACCURACY_METERS = 100f

/** Accuracy floor for weighting, so one optimistic fix cannot outweigh all the others. */
private const val MINIMUM_WEIGHT_ACCURACY_METERS = 5f

/** A spike sits at least this far from both neighbours, however precise it claims to be. */
private const val MINIMUM_SPIKE_METERS = 150.0

/**
 * True when nothing was recorded between two consecutive samples for long enough that the history
 * cannot say what happened. A short quiet stretch that resumes at the same spot is not a gap: the
 * phone simply had nothing new to report while the person stayed put.
 */
internal fun HistoryReconstructionParameters.isPrecise(
    sample: LocationObservation,
    acceptUnrated: Boolean = false,
): Boolean = sample.accuracyMeters?.let { it in 0f..maximumPreciseAccuracyMeters } ?: acceptUnrated

internal fun HistoryReconstructionParameters.isRecordingGap(
    previous: LocationObservation,
    next: LocationObservation,
): Boolean {
    val quiet = next.timestamp - previous.timestamp
    if (quiet <= maximumGap) return false
    if (quiet > maximumQuietStay) return true
    val placeable = isPrecise(previous, acceptUnrated = true) && isPrecise(next, acceptUnrated = true)
    return !placeable || distanceMeters(previous, next) > stayRadiusMeters + accuracyAllowanceMeters
}

internal fun LocationObservation.hasValidCoordinates(): Boolean {
    val accuracy = accuracyMeters
    return latitude.isFinite() &&
        longitude.isFinite() &&
        latitude in -90.0..90.0 &&
        longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0 && (accuracy == null || accuracy > 100f))
}

/**
 * Splits samples into precise ones, which may decide where someone was, and the rest. Unrated and
 * coarse fixes still count as evidence of when the phone was recording, but never move a visit.
 * Precise-looking 1-to-3 sample bursts that jump away and straight back are demoted as well.
 *
 * With [acceptUnrated], fixes that never reported an accuracy count as precise. That is only for
 * recordings with nothing better, such as periodic background captures that kept no accuracy.
 */
internal fun preciseSamples(
    samples: List<LocationObservation>,
    parameters: HistoryReconstructionParameters,
    acceptUnrated: Boolean = false,
): Set<String> {
    val candidates = samples.filter { parameters.isPrecise(it, acceptUnrated) }
    val excludedIds = mutableSetOf<String>()
    for (windowSize in 3 downTo 1) {
        var i = 1
        while (i + windowSize < candidates.size) {
            val pre = candidates[i - 1]
            val post = candidates[i + windowSize]
            val burst = candidates.subList(i, i + windowSize)
            if (pre.id !in excludedIds &&
                post.id !in excludedIds &&
                burst.none { it.id in excludedIds } &&
                isBurstOutlier(pre, burst, post, parameters)
            ) {
                burst.forEach { excludedIds += it.id }
                i += windowSize
            } else {
                i++
            }
        }
    }
    return candidates.filterNot { it.id in excludedIds }.mapTo(mutableSetOf()) { it.id }
}

private fun isBurstOutlier(
    previous: LocationObservation,
    burst: List<LocationObservation>,
    next: LocationObservation,
    parameters: HistoryReconstructionParameters,
): Boolean {
    val span = listOf(previous) + burst + listOf(next)
    if (span.zipWithNext().any { (a, b) -> b.timestamp - a.timestamp > parameters.maximumGap }) return false
    if (burst.last().timestamp - burst.first().timestamp > parameters.excursionTolerance) return false

    val implausibleSpeed =
        impliedSpeed(previous, burst.first()) > parameters.implausibleSpeedMetersPerSecond &&
            impliedSpeed(burst.last(), next) > parameters.implausibleSpeedMetersPerSecond

    if (distanceMeters(previous, next) > parameters.stayRadiusMeters) {
        return burst.size == 1 && implausibleSpeed
    }

    val allFar =
        burst.all { sample ->
            val farFromBoth = maxOf(MINIMUM_SPIKE_METERS, 2.0 * (sample.accuracyMeters ?: 0f))
            distanceMeters(previous, sample) > farFromBoth && distanceMeters(sample, next) > farFromBoth
        }
    if (!allFar) return false
    if (burst.size == 1 || implausibleSpeed) return true

    val anchorBaselineAccuracy = maxOf(previous.accuracyMeters ?: 15f, next.accuracyMeters ?: 15f)
    return burst.all { sample ->
        val sampleAccuracy = sample.accuracyMeters ?: 0f
        sampleAccuracy >= 2.5f * anchorBaselineAccuracy && sample.activity !in MOVING_MODES
    }
}

private fun impliedSpeed(
    from: LocationObservation,
    to: LocationObservation,
): Double {
    val seconds = (to.timestamp - from.timestamp).inWholeMilliseconds / 1000.0
    val meters = distanceMeters(from, to)
    return if (seconds <= 0.0) (if (meters > MINIMUM_SPIKE_METERS) Double.MAX_VALUE else 0.0) else meters / seconds
}

/** True when the recorded activity or a steady speed shows the person was on the move. */
internal fun showsMovement(samples: List<LocationObservation>): Boolean {
    val labelled = samples.map { it.activity }.filter { it != TravelMode.UNKNOWN }
    if (labelled.isNotEmpty() && labelled.count { it in MOVING_MODES } * 2 > labelled.size) return true
    val speeds = samples.mapNotNull { it.speedMetersPerSecond }.sorted()
    return speeds.size >= 2 && speeds[speeds.size / 2] > STEADY_MOVING_SPEED_METERS_PER_SECOND
}

private const val STEADY_MOVING_SPEED_METERS_PER_SECOND = 2f

/** The accuracy-weighted centre of the samples, so precise fixes count for more than vague ones. */
internal fun weightedCenter(samples: List<LocationObservation>): Pair<Double, Double> =
    CenterAccumulator().apply { samples.forEach(::add) }.value

/**
 * A running [weightedCenter], for growing a visit one sample at a time. Offsets are summed from the
 * first sample, so samples at one exact spot give back exactly that spot, and longitude offsets
 * take the short way across the antimeridian.
 */
internal class CenterAccumulator {
    private var origin: Pair<Double, Double>? = null
    private var latitudeOffset = 0.0
    private var longitudeOffset = 0.0
    private var total = 0.0

    val value: Pair<Double, Double>
        get() {
            val (latitude, longitude) = checkNotNull(origin) { "A centre needs at least one sample" }
            return latitude + latitudeOffset / total to wrapLongitude(longitude + longitudeOffset / total)
        }

    fun add(sample: LocationObservation) {
        val (latitude, longitude) = origin ?: (sample.latitude to sample.longitude).also { origin = it }
        val accuracy = (sample.accuracyMeters ?: UNKNOWN_ACCURACY_METERS).coerceAtLeast(MINIMUM_WEIGHT_ACCURACY_METERS)
        val weight = 1.0 / (accuracy * accuracy)
        latitudeOffset += (sample.latitude - latitude) * weight
        longitudeOffset += wrapLongitude(sample.longitude - longitude) * weight
        total += weight
    }
}

/** Brings a longitude or longitude difference into (-180, 180]. */
private fun wrapLongitude(degrees: Double): Double =
    when {
        degrees > 180.0 -> degrees - 360.0
        degrees <= -180.0 -> degrees + 360.0
        else -> degrees
    }

internal fun Pair<Double, Double>.distanceTo(sample: LocationObservation): Double =
    geographicDistance(first, second, sample.latitude, sample.longitude)

internal fun Pair<Double, Double>.distanceTo(other: Pair<Double, Double>): Double =
    geographicDistance(first, second, other.first, other.second)
