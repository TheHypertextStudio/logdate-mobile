package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.SemanticPlace
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

internal fun dynamicCoreRadius(
    anchors: List<LocationObservation>,
    parameters: HistoryReconstructionParameters,
): Double {
    val accuracies = anchors.mapNotNull { it.accuracyMeters }.sorted()
    return coreRadiusForMedianAccuracy(accuracies.getOrNull(accuracies.size / 2), parameters)
}

/** The core stay radius for anchors whose median reported accuracy is [medianAccuracy]. */
internal fun coreRadiusForMedianAccuracy(
    medianAccuracy: Float?,
    parameters: HistoryReconstructionParameters,
): Double {
    val medianAcc = medianAccuracy?.toDouble() ?: return parameters.stayRadiusMeters
    val lower = minOf(parameters.minimumCoreStayRadiusMeters, parameters.stayRadiusMeters)
    return (lower + 0.75 * maxOf(0.0, medianAcc - 15.0)).coerceIn(lower, parameters.stayRadiusMeters)
}

/**
 * The reported accuracies of a growing stay's anchors, kept in order so the median that sets its
 * radius costs one insertion per sample instead of a copy and sort of every anchor so far.
 */
internal class SortedAccuracies {
    private val values = ArrayList<Float>()

    fun add(accuracy: Float?) {
        if (accuracy == null) return
        val found = values.binarySearch(accuracy)
        values.add(if (found >= 0) found else -(found + 1), accuracy)
    }

    /** The same element [dynamicCoreRadius] picks: index size / 2 of the sorted accuracies. */
    fun median(): Float? = values.getOrNull(values.size / 2)
}

/**
 * Finds stays in a time-ordered run of precise samples with no recording gaps.
 *
 * A stay grows from a starting sample while new samples land near its centre. Samples that wander
 * off and come back after at most [HistoryReconstructionParameters.excursionTolerance] are kept as drift.
 * A candidate only counts when it meets the standard or high-confidence quick stay duration, its
 * centre holds still, and the recorded activity is not a vehicle.
 */
internal class StayDetector(
    private val parameters: HistoryReconstructionParameters,
    savedPlaces: List<SemanticPlace> = emptyList(),
) {
    private val envelopePlaces =
        savedPlaces.filter { it.userConfirmed && it.radiusMeters > parameters.minimumCoreStayRadiusMeters }

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
        radius: Double = parameters.stayRadiusMeters,
        envelope: SemanticPlace? = matchingEnvelope(center),
    ): Boolean {
        if (envelope != null) {
            val envelopeRadius = minOf(envelope.radiusMeters, parameters.maximumSavedPlaceRadiusMeters)
            val distFromPlace = geographicDistance(envelope.latitude, envelope.longitude, sample.latitude, sample.longitude)
            if (distFromPlace <= envelopeRadius) return true
        }
        val excessAccuracy =
            sample.accuracyMeters?.let { maxOf(0.0, it.toDouble() - 15.0).coerceAtMost(parameters.accuracyAllowanceMeters) }
                ?: parameters.accuracyAllowanceMeters
        return center.distanceTo(sample) <= radius + excessAccuracy
    }

    internal fun effectiveRadius(
        anchors: List<LocationObservation>,
        center: Pair<Double, Double>,
    ): Double = radiusAround(dynamicCoreRadius(anchors, parameters), center)

    private fun effectiveRadius(
        anchorAccuracies: SortedAccuracies,
        center: Pair<Double, Double>,
    ): Double = radiusAround(coreRadiusForMedianAccuracy(anchorAccuracies.median(), parameters), center)

    private fun radiusAround(
        core: Double,
        center: Pair<Double, Double>,
    ): Double {
        val envelope = matchingEnvelope(center) ?: return core
        return maxOf(core, minOf(envelope.radiusMeters, parameters.maximumSavedPlaceRadiusMeters))
    }

    internal fun matchingEnvelope(center: Pair<Double, Double>): SemanticPlace? =
        envelopePlaces
            .filter { place ->
                val cap = minOf(place.radiusMeters, parameters.maximumSavedPlaceRadiusMeters)
                center.distanceTo(place.latitude to place.longitude) <= cap
            }.minByOrNull { place ->
                center.distanceTo(place.latitude to place.longitude)
            }

    private fun grow(
        points: List<LocationObservation>,
        start: Int,
    ): DetectedStay? {
        val anchors = mutableListOf(start)
        val anchorAccuracies = SortedAccuracies().apply { add(points[start].accuracyMeters) }
        val center = CenterAccumulator().apply { add(points[start]) }
        val seedEnvelope = matchingEnvelope(center.value)
        // Fixed once the stay first lasts long enough, so a centre that creeps with a slow drift
        // ends the stay instead of following the drift until the whole stretch is rejected.
        var settled: Pair<Double, Double>? = null
        val fits = { sample: LocationObservation ->
            val currentCenter = center.value
            val currentRadius = effectiveRadius(anchorAccuracies, currentCenter)
            val envelope = matchingEnvelope(currentCenter) ?: seedEnvelope
            belongs(currentCenter, sample, currentRadius, envelope) &&
                settled?.let { settledCenter ->
                    belongs(settledCenter, sample, effectiveRadius(anchorAccuracies, settledCenter), envelope)
                } != false
        }
        var index = start + 1
        while (index < points.size) {
            if (fits(points[index])) {
                anchors += index
                anchorAccuracies.add(points[index].accuracyMeters)
                center.add(points[index])
                if (settled == null && points[index].timestamp - points[start].timestamp >= parameters.minimumQuickStay) {
                    settled = center.value
                }
                index++
                continue
            }
            val currentRadius = effectiveRadius(anchorAccuracies, center.value)
            val maxNeighborhood = maxOf(parameters.neighborhoodMeters, currentRadius * 1.5)
            index = returnAfterDrift(points, center.value, index, maxNeighborhood, fits) ?: break
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
        maxNeighborhoodMeters: Double,
        fits: (LocationObservation) -> Boolean,
    ): Int? {
        val leftAt = points[firstAway].timestamp
        var farAway = 0
        var index = firstAway
        while (index < points.size) {
            val sample = points[index]
            if (index > firstAway && fits(sample)) return index
            if (sample.timestamp - leftAt > parameters.excursionTolerance || sample.activity in RIDING_MODES) return null
            if (center.distanceTo(sample) > maxNeighborhoodMeters && ++farAway > 1) return null
            index++
        }
        return null
    }

    /** Drops arrival and departure samples that the finished stay's centre shows were still on the way. */
    private fun trim(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): DetectedStay? {
        val anchorObs = stay.anchors.map(points::get)
        val center = stay.center(points)
        val radius = effectiveRadius(anchorObs, center)
        val envelope = matchingEnvelope(center)
        val anchors =
            stay.anchors
                .dropWhile { !belongs(center, points[it], radius, envelope) }
                .dropLastWhile { !belongs(center, points[it], radius, envelope) }
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
    ): Boolean {
        val duration = points[stay.anchors.last()].timestamp - points[stay.anchors.first()].timestamp
        if (stay.anchors.size >= 2 && duration >= parameters.minimumStay) return true
        if (duration < parameters.minimumQuickStay || stay.anchors.size < parameters.quickStayMinimumSamples) return false
        val accuracies = stay.anchors.mapNotNull { points[it].accuracyMeters }.sorted()
        val medianAcc = accuracies.getOrNull(accuracies.size / 2) ?: return false
        if (medianAcc > parameters.quickStayMaxAccuracyMeters || riding(points, stay)) return false
        val speeds = stay.anchors.mapNotNull { points[it].speedMetersPerSecond }.sorted()
        val medianSpeed = speeds.getOrNull(speeds.size / 2)
        return medianSpeed == null || medianSpeed < 0.5f
    }

    private fun riding(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): Boolean {
        val activities = stay.anchors.map { points[it].activity }.filter { it != TravelMode.UNKNOWN }
        return activities.isNotEmpty() && activities.count { it in RIDING_MODES } * 2 > activities.size
    }

    /** A slow walk keeps a "stay" centred on the walker; its early and late samples sit apart with high straightness. */
    private fun drifting(
        points: List<LocationObservation>,
        stay: DetectedStay,
    ): Boolean {
        if (stay.anchors.size < MINIMUM_SAMPLES_FOR_DRIFT) return false
        val anchorObs = stay.anchors.map(points::get)
        val center = weightedCenter(anchorObs)
        if (matchingEnvelope(center) != null && !riding(points, stay)) return false
        val labelled = anchorObs.map { it.activity }.filter { it != TravelMode.UNKNOWN }
        if (labelled.isNotEmpty() && labelled.count { it == TravelMode.STILL } * 2 > labelled.size) return false

        val third = stay.anchors.size / 3
        val early = weightedCenter(stay.anchors.take(third).map(points::get))
        val late = weightedCenter(stay.anchors.takeLast(third).map(points::get))
        val radius = effectiveRadius(anchorObs, center)
        if (early.distanceTo(late) <= radius) return false

        val totalPath = anchorObs.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
        if (totalPath <= 1.0) return false
        val netDisplacement = distanceMeters(anchorObs.first(), anchorObs.last())
        return (netDisplacement / totalPath) > parameters.driftStraightnessThreshold
    }

    private companion object {
        const val MINIMUM_SAMPLES_FOR_DRIFT = 6
    }
}
