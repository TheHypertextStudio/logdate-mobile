package app.logdate.client.domain.location.history

import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.TravelMode
import kotlin.time.Duration

/**
 * One visit or journey inside a recorded stretch, before it becomes a [app.logdate.shared.model.location.LocationDayItem].
 *
 * [precise] samples decide when the part starts and ends (and, for a visit, [anchors] decide where
 * it is). [vague] samples were recorded during the part but are too imprecise to place it.
 */
internal data class SegmentPart(
    val isVisit: Boolean,
    val precise: List<LocationObservation>,
    val anchors: List<LocationObservation> = precise,
    val confirmed: Boolean = false,
    val vague: List<LocationObservation> = emptyList(),
) {
    val timed: List<LocationObservation> get() = precise.ifEmpty { vague }
}

/**
 * Turns one uninterrupted stretch of samples into visits and journeys: stays are found among the
 * precise samples, stays at the same spot are joined across drift, and whatever lies between stays
 * becomes a journey. Imprecise samples are then attached to whichever part they were recorded in.
 */
internal class SegmentAssembler(
    private val parameters: HistoryReconstructionParameters,
) {
    private val detector = StayDetector(parameters)

    fun assemble(
        segment: List<LocationObservation>,
        preciseIds: Set<String>,
    ): List<SegmentPart> {
        val points = segment.filter { it.id in preciseIds }
        val vague = segment.filterNot { it.id in preciseIds }
        if (points.isEmpty()) return listOf(island(vague, precise = false))
        val stays = joinSameSpot(points, detector.detect(points))
        val parts = if (stays.isEmpty()) listOf(island(points, precise = true)) else around(points, stays)
        return attach(parts.flatMap(::splitJourney), vague)
    }

    private fun island(
        samples: List<LocationObservation>,
        precise: Boolean,
    ): SegmentPart {
        val center = weightedCenter(samples)
        val compact = samples.all { detector.belongs(center, it) || (!precise && center.distanceTo(it) <= (it.accuracyMeters ?: 0f)) }
        val lasting = samples.size >= 2 && samples.last().timestamp - samples.first().timestamp >= parameters.minimumStay
        val isVisit = compact && !showsMovement(samples)
        return when {
            precise -> SegmentPart(isVisit, samples, confirmed = isVisit && lasting)
            else -> SegmentPart(isVisit, emptyList(), anchors = samples, vague = samples)
        }
    }

    /** Joins stays at the same spot when everything recorded between them stayed in the neighbourhood. */
    private fun joinSameSpot(
        points: List<LocationObservation>,
        stays: List<DetectedStay>,
    ): List<DetectedStay> {
        val joined = mutableListOf<DetectedStay>()
        var first = 0
        while (first < stays.size) {
            val center = stays[first].center(points)
            val sameSpot = mutableListOf(stays[first])
            for (index in first + 1 until stays.size) {
                val between = points.subList(sameSpot.last().range.last + 1, stays[index].range.first)
                if (between.any { center.distanceTo(it) > parameters.neighborhoodMeters }) break
                if (center.distanceTo(stays[index].center(points)) <= parameters.stayRadiusMeters) sameSpot += stays[index]
            }
            joined += DetectedStay(sameSpot.first().range.first..sameSpot.last().range.last, sameSpot.flatMap { it.anchors })
            first = stays.indexOf(sameSpot.last()) + 1
        }
        return joined
    }

    /** Lays out the stays and the samples around them, which become journeys unless they never left. */
    private fun around(
        points: List<LocationObservation>,
        stays: List<DetectedStay>,
    ): List<SegmentPart> {
        val visits = stays.map { SegmentPart(true, points.slice(it.range), it.anchors.map(points::get), confirmed = true) }.toMutableList()
        val travel = mutableListOf<List<LocationObservation>>()
        val (first, beforeFirst) = withEdge(visits.first(), points.subList(0, stays.first().range.first), before = true)
        visits[0] = first
        travel += beforeFirst
        stays.zipWithNext().forEachIndexed { index, (previous, next) ->
            val between = points.subList(previous.range.last + 1, next.range.first)
            val (keptByPrevious, rest) = peel(between, visits[index], fromStart = true)
            val (keptByNext, journey) = peel(rest, visits[index + 1], fromStart = false)
            visits[index] = visits[index].copy(precise = visits[index].precise + keptByPrevious)
            visits[index + 1] = visits[index + 1].copy(precise = keptByNext + visits[index + 1].precise)
            travel += journey
        }
        val (last, afterLast) = withEdge(visits.last(), points.subList(stays.last().range.last + 1, points.size), before = false)
        visits[visits.lastIndex] = last
        travel += afterLast
        val journeys = travel.filter { it.isNotEmpty() }.map { SegmentPart(false, it) }
        return (visits + journeys).sortedBy { it.precise.first().timestamp }
    }

    private fun peel(
        samples: List<LocationObservation>,
        visit: SegmentPart,
        fromStart: Boolean,
    ): Pair<List<LocationObservation>, List<LocationObservation>> {
        val center = weightedCenter(visit.anchors)
        val ordered = if (fromStart) samples else samples.asReversed()
        val kept = ordered.takeWhile { detector.belongs(center, it) }
        val rest = ordered.drop(kept.size)
        return if (fromStart) kept to rest else kept.asReversed() to rest.asReversed()
    }

    /**
     * Samples before the first stay or after the last one join it when they never left its
     * neighbourhood; otherwise only those right at the spot join, and the rest is travel. A single
     * stray fix with nothing showing movement is kept as evidence of the stay, not as a trip.
     */
    private fun withEdge(
        visit: SegmentPart,
        edge: List<LocationObservation>,
        before: Boolean,
    ): Pair<SegmentPart, List<LocationObservation>> {
        val center = weightedCenter(visit.anchors)
        val (kept, rest) = peel(edge, visit, fromStart = !before)
        val stayedNearby = rest.all { center.distanceTo(it) <= parameters.neighborhoodMeters } && !showsMovement(rest)
        val stray = rest.size == 1 && !showsMovement(rest)
        val joined = if (stayedNearby) edge else kept
        val precise = if (before) joined + visit.precise else visit.precise + joined
        val joinedVisit = visit.copy(precise = precise, vague = if (stray && !stayedNearby) visit.vague + rest else visit.vague)
        return joinedVisit to if (stayedNearby || stray) emptyList() else rest
    }

    /** Splits a journey where the recorded way of travelling changes, such as walking to the car. */
    private fun splitJourney(part: SegmentPart): List<SegmentPart> {
        if (part.isVisit || part.precise.isEmpty()) return listOf(part)
        val legs = mutableListOf(mutableListOf<LocationObservation>())
        var mode: TravelMode? = null
        part.precise.forEach { sample ->
            val moving = sample.activity.takeIf { it in MOVING_MODES }
            if (moving != null && mode != null && moving != mode && legs.last().isNotEmpty()) legs += mutableListOf<LocationObservation>()
            if (moving != null) mode = moving
            legs.last() += sample
        }
        return legs.map { SegmentPart(false, it) }
    }

    /** Hands each imprecise sample to the part it was recorded during, or the nearest one in time. */
    private fun attach(
        parts: List<SegmentPart>,
        vague: List<LocationObservation>,
    ): List<SegmentPart> {
        if (vague.isEmpty()) return parts
        val attached = parts.map { it.vague.toMutableList() }
        vague.forEach { sample ->
            val index =
                parts.indices.minBy { index ->
                    val timed = parts[index].timed
                    val start = timed.first().timestamp
                    val end = timed.last().timestamp
                    when {
                        sample.timestamp < start -> start - sample.timestamp
                        sample.timestamp > end -> sample.timestamp - end
                        else -> Duration.ZERO
                    }
                }
            attached[index] += sample
        }
        return parts.mapIndexed { index, part -> part.copy(vague = attached[index].sortedBy { it.timestamp }) }
    }
}
