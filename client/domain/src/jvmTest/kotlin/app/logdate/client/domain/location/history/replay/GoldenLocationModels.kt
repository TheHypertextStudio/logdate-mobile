package app.logdate.client.domain.location.history.replay

import app.logdate.shared.model.location.ActivityObservation
import app.logdate.shared.model.location.HistoryCorrection
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
internal data class ExpectedVisitVector(
    val id: String,
    val start: Instant,
    val end: Instant,
    val confirmedStay: Boolean,
    val placeId: String? = null,
    val latitude: Double,
    val longitude: Double,
    val toleranceMeters: Double = 25.0,
    val kind: String = "PlaceVisit",
)

@Serializable
internal data class ExpectedJourneyVector(
    val start: Instant,
    val end: Instant,
    val mode: TravelMode,
    val expectedDistanceMeters: Double,
    val kind: String = "JourneyLeg",
)

@Serializable
internal data class GoldenVectorExpected(
    val stays: List<ExpectedVisitVector> = emptyList(),
    val journeys: List<ExpectedJourneyVector> = emptyList(),
    val maxFalseBurstSplits: Int = 0,
    val expectMultiZoneDay: Boolean = false,
)

@Serializable
internal data class GoldenLocationVector(
    val schemaVersion: Int = 4,
    val scenarioId: String,
    val title: String,
    val zoneId: String = "America/Los_Angeles",
    val sourceDeviceId: String? = null,
    val places: List<SemanticPlace> = emptyList(),
    val placeRadiiMeters: Map<String, Double> = emptyMap(),
    val activities: List<ActivityObservation> = emptyList(),
    val observations: List<LocationObservation> = emptyList(),
    val corrections: List<HistoryCorrection> = emptyList(),
    val expected: GoldenVectorExpected,
)

internal data class ScenarioScore(
    val scenarioId: String,
    val title: String,
    val matchedStays: Int,
    val expectedStays: Int,
    val predictedStays: Int,
    val stayPrecision: Double,
    val stayRecall: Double,
    val boundaryErrorSeconds: List<Long>,
    val centroidErrorsMeters: List<Double>,
    val falseBurstSplits: Int,
    val matchedModes: Int,
    val expectedModes: Int,
    val journeyDistanceErrorsFraction: List<Double>,
) {
    val meanBoundaryMaeSeconds: Double
        get() = if (boundaryErrorSeconds.isEmpty()) 0.0 else boundaryErrorSeconds.average()
    val medianCentroidErrorMeters: Double
        get() =
            if (centroidErrorsMeters.isEmpty()) {
                0.0
            } else {
                centroidErrorsMeters.sorted()[centroidErrorsMeters.size / 2]
            }
}

internal data class BenchmarkSuiteReport(
    val algorithmName: String,
    val scenarios: List<ScenarioScore>,
) {
    val stayPrecision: Double
        get() {
            val totalPredicted = scenarios.sumOf { it.predictedStays }
            val totalMatched = scenarios.sumOf { it.matchedStays }
            return if (totalPredicted == 0) 1.0 else totalMatched.toDouble() / totalPredicted
        }
    val stayRecall: Double
        get() {
            val totalExpected = scenarios.sumOf { it.expectedStays }
            val totalMatched = scenarios.sumOf { it.matchedStays }
            return if (totalExpected == 0) 1.0 else totalMatched.toDouble() / totalExpected
        }
    val boundaryMaeSeconds: Double
        get() {
            val all = scenarios.flatMap { it.boundaryErrorSeconds }
            return if (all.isEmpty()) 0.0 else all.average()
        }
    val medianCentroidErrorMeters: Double
        get() {
            val all = scenarios.flatMap { it.centroidErrorsMeters }.sorted()
            return if (all.isEmpty()) 0.0 else all[all.size / 2]
        }
    val travelModeAccuracy: Double
        get() {
            val totalExpected = scenarios.sumOf { it.expectedModes }
            val totalMatched = scenarios.sumOf { it.matchedModes }
            return if (totalExpected == 0) 1.0 else totalMatched.toDouble() / totalExpected
        }
    val meanDistanceErrorFraction: Double
        get() {
            val all = scenarios.flatMap { it.journeyDistanceErrorsFraction }
            return if (all.isEmpty()) 0.0 else all.average()
        }
    val totalFalseBurstSplits: Int
        get() = scenarios.sumOf { it.falseBurstSplits }
}
