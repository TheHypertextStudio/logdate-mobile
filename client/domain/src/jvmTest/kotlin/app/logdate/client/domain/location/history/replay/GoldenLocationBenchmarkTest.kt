package app.logdate.client.domain.location.history.replay

import app.logdate.client.domain.location.history.ReconstructLocationDay
import app.logdate.client.domain.location.history.applyActivityEvidence
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoldenLocationBenchmarkTest {
    private val json =
        Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `v3 baseline benchmark measures current deficits across B01-B10`() {
        val scenarios = GoldenLocationCorpus.allScenarios()
        val reconstruct = ReconstructLocationDay()
        val scores =
            scenarios.map { scenario ->
                val enriched = applyActivityEvidence(scenario.observations, scenario.activities)
                val items = reconstruct(enriched)
                scoreGoldenScenario(scenario, items)
            }
        val report = BenchmarkSuiteReport("v3-baseline", scores)
        println(formatBenchmarkReport(report))

        assertEquals(10, report.scenarios.size)
        // Baseline metrics prove v3 drops accuracy on burst jumps (B01), 10m activity expiry (B02),
        // quick coffee stops (B04), campus envelopes (B05), kinematic commute (B06), and mock GPS (B10).
        assertTrue(report.stayPrecision < 1.0 || report.travelModeAccuracy < 0.9 || report.totalFalseBurstSplits > 0)
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
