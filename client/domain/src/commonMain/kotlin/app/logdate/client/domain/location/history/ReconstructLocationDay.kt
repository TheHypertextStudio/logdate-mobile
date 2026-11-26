package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.HistoryGap
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rebuilds a day of visits and journeys from raw location samples.
 *
 * Each recording device is reconstructed on its own. Its samples are first cut wherever the phone
 * stopped recording for longer than [HistoryReconstructionParameters.maximumGap]; those cuts are
 * shown as gaps rather than guessed across, unless the phone only went quiet briefly and resumed
 * at the same spot. Within each recorded stretch, [SegmentAssembler] separates the time someone
 * stayed put from the time they travelled, tolerating the position noise real phones produce.
 * Fixes without a reported accuracy only shape a stretch made mostly of them.
 *
 * Item identity comes from evidence: a visit or journey is named after its first precise sample, so
 * the same recordings always produce the same ids, and edits keyed to a sample keep finding it.
 */
class ReconstructLocationDay(
    private val inference: TravelModeInferenceProvider = HybridTravelModeInferenceProvider(),
    private val parameters: HistoryReconstructionParameters = HistoryReconstructionParameters(),
) {
    operator fun invoke(observations: List<LocationObservation>): List<LocationDayItem> =
        invoke(observations, savedPlaces = emptyList(), includeMock = false)

    operator fun invoke(
        observations: List<LocationObservation>,
        savedPlaces: List<SemanticPlace> = emptyList(),
        includeMock: Boolean = false,
    ): List<LocationDayItem> {
        val assembler = SegmentAssembler(parameters, savedPlaces)
        return observations
            .filter { it.hasValidCoordinates() && (includeMock || !it.isMock) }
            .distinctBy { Triple(it.ownerId, it.deviceId, it.id) }
            .groupBy { it.ownerId to it.deviceId }
            .flatMap { (_, source) -> reconstructSource(source.sortedWith(compareBy({ it.timestamp }, { it.id })), assembler) }
            .sortedWith(compareBy({ it.start }, { it.id }))
    }

    private fun reconstructSource(
        source: List<LocationObservation>,
        assembler: SegmentAssembler,
    ): List<LocationDayItem> {
        val preciseIds = preciseSamples(source, parameters)
        val segments = splitAtGaps(source)
        return buildList {
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    val previous = segments[index - 1].last()
                    val next = segment.first()
                    add(HistoryGap("gap:${previous.id}:${next.id}", previous.timestamp, next.timestamp))
                }
                addAll(withConnectors(assembler.assemble(segment, usableSamples(segment, preciseIds))))
            }
        }
    }

    /**
     * Unrated fixes are left out of a stretch that mostly reported its accuracy, where they are the
     * odd coarse capture. When most of a stretch is unrated (recordings from before accuracy was
     * kept), they are the best evidence there is.
     */
    private fun usableSamples(
        segment: List<LocationObservation>,
        preciseIds: Set<String>,
    ): Set<String> {
        val rated = segment.count { it.accuracyMeters != null }
        return if (rated * 2 > segment.size) preciseIds else preciseSamples(segment, parameters, acceptUnrated = true)
    }

    private fun splitAtGaps(source: List<LocationObservation>): List<List<LocationObservation>> {
        val segments = mutableListOf<MutableList<LocationObservation>>()
        source.forEach { sample ->
            val current = segments.lastOrNull()
            if (current == null || parameters.isRecordingGap(current.last(), sample)) {
                segments += mutableListOf(sample)
            } else {
                current += sample
            }
        }
        return segments
    }

    private fun toItem(part: SegmentPart): LocationDayItem {
        val timed = part.timed
        val first = timed.first()
        val id = "${first.ownerId}:${first.deviceId}:${first.id}"
        val allObs = part.precise + part.vague
        val evidence = allObs.map { it.id }
        val dominantZone = dominantTimeZoneId(allObs)
        if (!part.isVisit) {
            val route = part.precise.ifEmpty { part.vague }
            val mode = inference.infer(route).firstOrNull()?.mode ?: TravelMode.UNKNOWN
            val smoothed = RouteSmoother.smooth(route, mode, parameters.routeSimplificationEpsilonMeters)
            val distance = RouteSmoother.pathLengthMeters(smoothed)
            return JourneyLeg(
                id = id,
                start = first.timestamp,
                end = timed.last().timestamp,
                evidenceIds = evidence,
                mode = mode,
                route = route,
                smoothedRoute = smoothed,
                distanceMeters = distance,
                displayZoneId = dominantZone,
                sourceDeviceId = first.deviceId,
            )
        }
        val (latitude, longitude) = weightedCenter(part.anchors)
        val uncertainty = computeUncertaintyRadiusMeters(part.anchors, latitude, longitude)
        val confidence = computeVisitConfidence(first.timestamp, timed.last().timestamp, part.anchors, allObs)
        return PlaceVisit(
            id = id,
            start = first.timestamp,
            end = timed.last().timestamp,
            evidenceIds = evidence,
            latitude = latitude,
            longitude = longitude,
            confirmedStay = part.confirmed,
            uncertaintyRadiusMeters = uncertainty,
            confidence = confidence,
            sampleCount = allObs.size,
            displayZoneId = dominantZone,
            sourceDeviceId = first.deviceId,
        )
    }

    private fun dominantTimeZoneId(observations: List<LocationObservation>): String? =
        observations
            .mapNotNull { it.timeZoneId?.takeIf { zoneId -> zoneId.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

    private fun computeUncertaintyRadiusMeters(
        anchors: List<LocationObservation>,
        centerLat: Double,
        centerLon: Double,
    ): Float {
        val accuracies = anchors.mapNotNull { it.accuracyMeters }.sorted()
        val medianAcc = accuracies.getOrNull(accuracies.size / 2) ?: 100f
        val rmsSpread =
            if (anchors.isEmpty()) {
                0f
            } else {
                val meanSq =
                    anchors.sumOf {
                        val d = geographicDistance(centerLat, centerLon, it.latitude, it.longitude)
                        d * d
                    } / anchors.size
                sqrt(meanSq).toFloat()
            }
        return maxOf(medianAcc, rmsSpread).coerceIn(10f, 1500f)
    }

    private fun computeVisitConfidence(
        start: kotlin.time.Instant,
        end: kotlin.time.Instant,
        anchors: List<LocationObservation>,
        allObs: List<LocationObservation>,
    ): Float {
        val durationMinutes = ((end - start).inWholeSeconds / 60.0).coerceAtLeast(0.0)
        val durationScore = (durationMinutes / 30.0).coerceIn(0.0, 1.0)
        val densityScore = (allObs.size / 8.0).coerceIn(0.0, 1.0)
        val accuracies = anchors.mapNotNull { it.accuracyMeters }.sorted()
        val medianAcc = accuracies.getOrNull(accuracies.size / 2)?.toDouble() ?: 100.0
        val accuracyScore = (1.0 - ((medianAcc - 10.0) / 140.0)).coerceIn(0.0, 1.0)
        val labelled = allObs.map { it.activity }.filter { it != TravelMode.UNKNOWN }
        val stillnessAgreement =
            if (labelled.isEmpty()) {
                0.5
            } else {
                labelled.count { it == TravelMode.STILL }.toDouble() / labelled.size
            }
        return (0.30 * durationScore + 0.25 * densityScore + 0.30 * accuracyScore + 0.15 * stillnessAgreement)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    /** Two visits with nothing recorded between them were still joined by some trip. */
    private fun withConnectors(parts: List<SegmentPart>): List<LocationDayItem> =
        buildList {
            parts.forEachIndexed { index, part ->
                val previous = parts.getOrNull(index - 1)
                val item = toItem(part)
                val previousItem = lastOrNull()
                if (previous != null &&
                    previous.isVisit &&
                    part.isVisit &&
                    previousItem is PlaceVisit &&
                    item is PlaceVisit &&
                    previousItem.distanceTo(item) > parameters.minimumCoreStayRadiusMeters
                ) {
                    add(connector(previous.timed.last(), part.timed.first(), previousItem, item))
                }
                add(item)
            }
        }

    private fun connector(
        previous: LocationObservation,
        next: LocationObservation,
        previousVisit: PlaceVisit,
        nextVisit: PlaceVisit,
    ): JourneyLeg {
        val connectorId = "journey:${previous.ownerId}:${previous.deviceId}:${previous.id}:${next.id}"
        // The inferred relation is editable without editing either endpoint visit.
        return JourneyLeg(
            id = connectorId,
            start = previous.timestamp,
            end = next.timestamp,
            evidenceIds = listOf("derived:$connectorId"),
            mode = TravelMode.UNKNOWN,
            route = emptyList(),
            endpointCoordinates =
                (previousVisit.latitude to previousVisit.longitude) to
                    (nextVisit.latitude to nextVisit.longitude),
            displayZoneId = previousVisit.displayZoneId ?: nextVisit.displayZoneId,
            sourceDeviceId = previous.deviceId,
        )
    }

    private fun PlaceVisit.distanceTo(other: PlaceVisit): Double = geographicDistance(latitude, longitude, other.latitude, other.longitude)

    companion object {
        const val VERSION = 4
    }
}

internal fun distanceMeters(
    a: LocationObservation,
    b: LocationObservation,
): Double = geographicDistance(a.latitude, a.longitude, b.latitude, b.longitude)

internal fun geographicDistance(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double,
): Double {
    val dLat = (lat2 - lat1) * PI / 180
    val dLon = (lon2 - lon1) * PI / 180
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
    return 6371000 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1 - a).coerceIn(0.0, 1.0)))
}
