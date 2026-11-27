package app.logdate.client.domain.location.history.replay

import app.logdate.client.domain.location.history.geographicDistance
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import kotlin.math.abs

internal fun scoreGoldenScenario(
    scenario: GoldenLocationVector,
    items: List<LocationDayItem>,
): ScenarioScore {
    val visits = items.filterIsInstance<PlaceVisit>().filter { it.confirmedStay }
    val journeys = items.filterIsInstance<JourneyLeg>()
    val expectedStays = scenario.expected.stays
    val stayMetrics = scoreStays(expectedStays, visits)
    val journeyMetrics = scoreJourneys(scenario.expected.journeys, journeys)
    val falseBurstSplits = if (expectedStays.size == 1 && scenario.scenarioId == "B01") (visits.size - 1).coerceAtLeast(0) else 0
    return ScenarioScore(
        scenarioId = scenario.scenarioId,
        title = scenario.title,
        matchedStays = stayMetrics.matched,
        expectedStays = expectedStays.size,
        predictedStays = visits.size,
        stayPrecision = if (visits.isEmpty()) (if (expectedStays.isEmpty()) 1.0 else 0.0) else stayMetrics.matched.toDouble() / visits.size,
        stayRecall = if (expectedStays.isEmpty()) 1.0 else stayMetrics.matched.toDouble() / expectedStays.size,
        boundaryErrorSeconds = stayMetrics.boundaryErrors,
        centroidErrorsMeters = stayMetrics.centroidErrors,
        falseBurstSplits = falseBurstSplits,
        matchedModes = journeyMetrics.matchedModes,
        expectedModes = scenario.expected.journeys.size,
        journeyDistanceErrorsFraction = journeyMetrics.distanceErrors,
    )
}

private data class StayMatchMetrics(
    val matched: Int,
    val boundaryErrors: List<Long>,
    val centroidErrors: List<Double>,
)

private fun scoreStays(
    expectedStays: List<ExpectedVisitVector>,
    visits: List<PlaceVisit>,
): StayMatchMetrics {
    val usedVisits = mutableSetOf<String>()
    val boundaryErrors = mutableListOf<Long>()
    val centroidErrors = mutableListOf<Double>()
    var matchedStays = 0
    expectedStays.forEach { expected ->
        val best =
            visits
                .filter { it.id !in usedVisits && it.start <= expected.end && it.end >= expected.start }
                .minByOrNull { geographicDistance(it.latitude, it.longitude, expected.latitude, expected.longitude) }
        if (best != null) {
            val distance = geographicDistance(best.latitude, best.longitude, expected.latitude, expected.longitude)
            val placeMatches = expected.placeId == null || best.place == null || best.place?.id == expected.placeId
            if (distance <= maxOf(expected.toleranceMeters, 150.0) && placeMatches) {
                matchedStays++
                usedVisits += best.id
                boundaryErrors += abs((best.start - expected.start).inWholeSeconds)
                boundaryErrors += abs((best.end - expected.end).inWholeSeconds)
                centroidErrors += distance
            }
        }
    }
    return StayMatchMetrics(matchedStays, boundaryErrors, centroidErrors)
}

private data class JourneyMatchMetrics(
    val matchedModes: Int,
    val distanceErrors: List<Double>,
)

private fun scoreJourneys(
    expectedJourneys: List<ExpectedJourneyVector>,
    journeys: List<JourneyLeg>,
): JourneyMatchMetrics {
    var matchedModes = 0
    val distanceErrors = mutableListOf<Double>()
    expectedJourneys.forEach { expected ->
        val overlapping = journeys.filter { it.start <= expected.end && it.end >= expected.start }
        val dominant = overlapping.maxByOrNull { it.end - it.start }
        if (dominant != null && dominant.mode == expected.mode) matchedModes++
        val actualDistance = overlapping.sumOf { leg -> leg.distanceMeters.takeIf { it > 0.0 } ?: pathLengthMeters(leg.smoothedRoute) }
        if (expected.expectedDistanceMeters > 0.0 && overlapping.isNotEmpty()) {
            distanceErrors += abs(actualDistance - expected.expectedDistanceMeters) / expected.expectedDistanceMeters
        }
    }
    return JourneyMatchMetrics(matchedModes, distanceErrors)
}

internal fun pathLengthMeters(points: List<LocationObservation>): Double =
    points.zipWithNext().sumOf { (a, b) ->
        geographicDistance(a.latitude, a.longitude, b.latitude, b.longitude)
    }

internal fun formatBenchmarkReport(report: BenchmarkSuiteReport): String =
    buildString {
        appendLine("Algorithm: ${report.algorithmName}")
        report.scenarios.forEach { score ->
            appendLine(
                "  ${score.scenarioId} (${score.title}): " +
                    "stays ${score.matchedStays}/${score.expectedStays} (pred=${score.predictedStays}), " +
                    "boundaryMAE=${"%.1f".format(score.meanBoundaryMaeSeconds)}s, " +
                    "centroidMed=${"%.1f".format(score.medianCentroidErrorMeters)}m, " +
                    "modes=${score.matchedModes}/${score.expectedModes}, " +
                    "burstSplits=${score.falseBurstSplits}",
            )
        }
        appendLine(
            "Totals: precision=${"%.3f".format(report.stayPrecision)} " +
                "recall=${"%.3f".format(report.stayRecall)} " +
                "boundaryMAE=${"%.1f".format(report.boundaryMaeSeconds)}s " +
                "centroidMed=${"%.1f".format(report.medianCentroidErrorMeters)}m " +
                "modeAcc=${"%.3f".format(report.travelModeAccuracy)} " +
                "distErr=${"%.3f".format(report.meanDistanceErrorFraction)} " +
                "burstSplits=${report.totalFalseBurstSplits}",
        )
    }
