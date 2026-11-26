package app.logdate.client.domain.location.history.replay

import app.logdate.client.domain.location.history.ReconstructLocationDay
import app.logdate.client.domain.location.history.applyActivityEvidence
import app.logdate.client.domain.location.history.fuseMultiDeviceItems
import app.logdate.client.domain.location.history.geographicDistance
import app.logdate.client.domain.location.history.resolveCaptureZone
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class GoldenLocationBenchmarkTest {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `v4 reconstruction meets accuracy and mode thresholds across B01-B10`() {
        val scenarios = GoldenLocationCorpus.allScenarios()
        val reconstruct = ReconstructLocationDay()
        val scenarioOutputs = mutableMapOf<String, List<LocationDayItem>>()
        val scores =
            scenarios.map { scenario ->
                val enriched = applyActivityEvidence(scenario.observations, scenario.activities)
                val reconstructed = reconstruct(enriched, savedPlaces = scenario.places)
                val items =
                    if (scenario.sourceDeviceId == "all") {
                        val byDevice =
                            reconstructed.groupBy { item ->
                                when (item) {
                                    is PlaceVisit -> item.sourceDeviceId ?: "phone-1"
                                    is JourneyLeg -> item.sourceDeviceId ?: "phone-1"
                                    else -> "phone-1"
                                }
                            }
                        val windowStart = scenario.observations.minOf { it.timestamp } - 1.hours
                        val windowEnd = scenario.observations.maxOf { it.timestamp } + 1.hours
                        fuseMultiDeviceItems(byDevice, "phone-1", windowStart, windowEnd)
                    } else {
                        reconstructed
                    }
                scenarioOutputs[scenario.scenarioId] = items
                scoreGoldenScenario(scenario, items)
            }
        val report = BenchmarkSuiteReport("v4-reconstruction", scores)
        println(formatBenchmarkReport(report))

        assertEquals(10, report.scenarios.size)
        assertTrue(report.stayPrecision >= 0.95, "Expected stayPrecision >= 0.95, was ${report.stayPrecision}")
        assertTrue(report.stayRecall >= 0.92, "Expected stayRecall >= 0.92, was ${report.stayRecall}")
        assertTrue(report.boundaryMaeSeconds <= 90.0, "Expected boundaryMaeSeconds <= 90.0, was ${report.boundaryMaeSeconds}")
        assertTrue(
            report.medianCentroidErrorMeters <= 25.0,
            "Expected medianCentroidErrorMeters <= 25.0, was ${report.medianCentroidErrorMeters}",
        )
        assertEquals(0, report.totalFalseBurstSplits, "Expected 0 false burst splits")
        assertTrue(report.travelModeAccuracy >= 0.90, "Expected travelModeAccuracy >= 0.90, was ${report.travelModeAccuracy}")
        assertTrue(
            report.meanDistanceErrorFraction <= 0.08,
            "Expected meanDistanceErrorFraction <= 0.08, was ${report.meanDistanceErrorFraction}",
        )

        val b01 = scores.first { it.scenarioId == "B01" }
        assertEquals(1, b01.predictedStays)
        assertTrue(b01.medianCentroidErrorMeters <= 20.0)

        val b02 = scores.first { it.scenarioId == "B02" }
        assertEquals(1, b02.matchedModes)
        assertTrue(b02.journeyDistanceErrorsFraction.single() <= 0.05)

        assertEquals(2, scores.first { it.scenarioId == "B03" }.predictedStays)

        val b04Items = scenarioOutputs.getValue("B04")
        assertEquals(1, b04Items.filterIsInstance<PlaceVisit>().count { it.confirmedStay })
        assertEquals(listOf(TravelMode.WALKING, TravelMode.WALKING), b04Items.filterIsInstance<JourneyLeg>().map { it.mode })

        assertEquals(1, scores.first { it.scenarioId == "B05" }.predictedStays)

        val b06Modes = scenarioOutputs.getValue("B06").filterIsInstance<JourneyLeg>().map { it.mode }
        assertEquals(listOf(TravelMode.WALKING, TravelMode.VEHICLE, TravelMode.WALKING), b06Modes)

        val b07Scenario = scenarios.first { it.scenarioId == "B07" }
        val (resolvedZone, multiZone) =
            resolveCaptureZone(
                b07Scenario.observations,
                Instant.parse("2026-10-12T07:00:00Z"),
                Instant.parse("2026-10-13T07:00:00Z"),
                TimeZone.UTC,
            )
        assertEquals("America/Los_Angeles", resolvedZone.id)
        assertTrue(multiZone)

        assertEquals(3, scores.first { it.scenarioId == "B08" }.matchedStays)

        val b09Visit = scenarioOutputs.getValue("B09").filterIsInstance<PlaceVisit>().single()
        assertTrue(b09Visit.confirmedStay)
        assertTrue(
            geographicDistance(b09Visit.latitude, b09Visit.longitude, 37.7749, -122.4194) <= b09Visit.uncertaintyRadiusMeters,
        )

        val b10Visits = scenarioOutputs.getValue("B10").filterIsInstance<PlaceVisit>()
        assertEquals(1, b10Visits.size)
        assertTrue(b10Visits.single().evidenceIds.none { it.contains("mock") })
    }

    @Test
    fun `shared json test vectors match golden corpus`() {
        val dir = locateVectorDir()
        if (System.getenv("UPDATE_LOCATION_TEST_VECTORS") == "1") {
            dir.mkdirs()
            GoldenLocationCorpus.allScenarios().forEach { vector ->
                val file = dir.resolve(vectorFileName(vector))
                file.writeText(json.encodeToString(vector) + "\n")
            }
        }
        val expectedVectors = GoldenLocationCorpus.allScenarios()
        expectedVectors.forEach { expected ->
            val file = dir.resolve(vectorFileName(expected))
            assertTrue(file.exists(), "Missing shared vector fixture: ${file.absolutePath}")
            val parsed = json.decodeFromString<GoldenLocationVector>(file.readText())
            assertEquals(expected.scenarioId, parsed.scenarioId)
            assertEquals(expected.observations.size, parsed.observations.size)
            assertEquals(expected.activities.size, parsed.activities.size)
            assertEquals(expected.places.size, parsed.places.size)
        }
    }

    private fun vectorFileName(vector: GoldenLocationVector): String =
        when (vector.scenarioId) {
            "B01" -> "01_desk_wifi_drift.json"
            "B02" -> "02_highway_single_enter.json"
            "B03" -> "03_adjacent_urban_stops.json"
            "B04" -> "04_quick_coffee_stop.json"
            "B05" -> "05_campus_envelope_walk.json"
            "B06" -> "06_multimodal_kinematic_commute.json"
            "B07" -> "07_cross_timezone_flight.json"
            "B08" -> "08_dual_device_fusion.json"
            "B09" -> "09_indoor_cold_start.json"
            "B10" -> "10_mock_gps_contamination.json"
            else -> "${vector.scenarioId.lowercase()}.json"
        }

    private fun locateVectorDir(): File {
        val roots = listOf(File("."), File(".."), File("../.."), File("../../.."))
        val repoRoot = roots.firstOrNull { it.resolve("shared").isDirectory && it.resolve("client").isDirectory } ?: File("../..")
        return repoRoot.resolve("shared/test-vectors/location-history-v4")
    }
}
