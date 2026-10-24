package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode

/**
 * A stretch of precise samples where someone stayed in one place.
 *
 * [range] covers every sample from arrival to departure, including brief drift away from the spot;
 * [anchors] are the samples that actually sat at the spot and decide where it is.
 */
internal data class DetectedStay(
    val range: IntRange,
    val anchors: List<Int>,
) {
    fun center(points: List<LocationObservation>): Pair<Double, Double> = weightedCenter(anchors.map(points::get))
}

/**
 * Finds stays in a time-ordered run of precise samples with no recording gaps.
 *
 * A stay grows from a starting sample while new samples land near its centre. Samples that wander
 * off and come back after at most [HistoryReconstructionParameters.excursionTolerance] are kept as drift.
 * A candidate only counts when it lasts [HistoryReconstructionParameters.minimumStay], its centre
 * holds still (a slow walk keeps moving it), and the recorded activity is not a vehicle.
 */
internal class StayDetector(
    private val parameters: HistoryReconstructionParameters,
) {
    fun detect(points: List<LocationObservation>): List<DetectedStay> {
        val stays = mutableListOf<DetectedStay>()
        var start = 0
        while (start < points.size) {
            val candidate = grow(points, start)
            val stay = candidate?.let { trim(points, it) }?.takeIf { qualifies(points, it) }
            start =
                when {
                    stay != null -> stay.range.last + 1

                    // A long candidate that failed (riding, drifting) would fail again from its next
                    // sample, so halve the distance to its end rather than regrowing it from every one.
                    candidate != null -> maxOf(start + 1, (start + candidate.range.last) / 2)

                    else -> start + 1
                }
            stay?.let(stays::add)
        }
        return stays
    }

    fun belongs(
        center: Pair<Double, Double>,
        sample: LocationObservation,
    ): Boolean =
        center.distanceTo(sample) <=
            parameters.stayRadiusMeters + minOf(sample.accuracyMeters?.toDouble() ?: 0.0, parameters.accuracyAllowanceMeters)

    private fun grow(
        points: List<LocationObservation>,
        start: Int,
    ): DetectedStay? {
        val anchors = mutableListOf(start)
        val center = CenterAccumulator().apply { add(points[start]) }
        // Fixed once the stay first lasts long enough, so a centre that creeps with a slow drift
        // ends the stay instead of following the drift until the whole stretch is rejected.
        var settled: Pair<Double, Double>? = null
        val fits = { sample: LocationObservation -> belongs(center.value, sample) && settled?.let { belongs(it, sample) } != false }
        var index = start + 1
        while (index < points.size) {
            if (fits(points[index])) {
                anchors += index
                center.add(points[index])
                if (settled == null && points[index].timestamp - points[start].timestamp >= parameters.minimumStay) settled = center.value
                index++
                continue
            }
            index = returnAfterDrift(points, center.value, index, fits) ?: break
        }
        return DetectedStay(start..anchors.last(), anchors).takeIf { lasts(points, it) }
    }

    /**
     * The index where samples come back to the stay, provided the ones away from it span no longer
     * than the excursion tolerance and look like noise rather than a trip. One stray fix between two
     * at-home fixes spans no time at all, however far apart the phone happened to sample; two or more
     * fixes beyond the neighbourhood, or a recorded ride, are a real outing.
     */
    private fun returnAfterDrift(
        points: List<LocationObservation>,
        center: Pair<Double, Double>,
        firstAway: Int,
        fits: (LocationObservation) -> Boolean,
    ): Int? {
        val leftAt = points[firstAway].timestamp
        var farAway = 0
        var index = firstAway
        while (index < points.size) {
            val sample = points[index]
            if (index > firstAway && fits(sample)) return index
            if (sample.timestamp - leftAt > parameters.excursionTolerance || sample.activity in RIDING_MODES) return null
            if (center.distanceTo(sample) > parameters.neighborhoodMeters && ++farAway > 1) return null
            index++
        }
        return null
    }

    /** Drops arrival and departure samples that the finished stay's centre shows were still on the way. */
    private fun trim(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): DetectedStay? {
        val center = stay.center(points)
        val anchors = stay.anchors.dropWhile { !belongs(center, points[it]) }.dropLastWhile { !belongs(center, points[it]) }
        if (anchors.isEmpty()) return null
        return DetectedStay(anchors.first()..anchors.last(), anchors)
    }

    private fun qualifies(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): Boolean = lasts(points, stay) && !riding(points, stay) && !drifting(points, stay)

    private fun lasts(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): Boolean = points[stay.anchors.last()].timestamp - points[stay.anchors.first()].timestamp >= parameters.minimumStay

    private fun riding(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): Boolean {
        val activities = stay.anchors.map { points[it].activity }.filter { it != TravelMode.UNKNOWN }
        return activities.isNotEmpty() && activities.count { it in RIDING_MODES } * 2 > activities.size
    }

    /** A slow walk keeps a "stay" centred on the walker; its early and late samples sit apart. */
    private fun drifting(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): Boolean {
        if (stay.anchors.size < MINIMUM_SAMPLES_FOR_DRIFT) return false
        val third = stay.anchors.size / 3
        val early = weightedCenter(stay.anchors.take(third).map(points::get))
        val late = weightedCenter(stay.anchors.takeLast(third).map(points::get))
        return early.distanceTo(late) > parameters.stayRadiusMeters
    }

    private companion object {
        const val MINIMUM_SAMPLES_FOR_DRIFT = 6
    }
}
