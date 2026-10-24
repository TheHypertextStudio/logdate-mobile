package app.logdate.client.sync.space

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The number conversions against `tools/lexicon-check/conversion-vectors.json`, the known answers
 * every LogDate client uses. A vector without an expected value is one the field is left out for,
 * which the mappers report by refusing it.
 */
class ConversionVectorsTest {
    private val vectors = Json.parseToJsonElement(ConversionVectorFixtures.text("conversion-vectors.json")).jsonObject

    @Test
    fun `rounding halves away from zero follows the shared vectors`() {
        val cases = vectors.getValue("roundHalfAwayFromZero").jsonArray.map { it.jsonObject }

        assertTrue(cases.isNotEmpty())
        cases.forEach { case ->
            assertEquals(expectedOf(case), roundHalfAwayFromZero("vector", inputOf(case)), "$case")
        }
    }

    @Test
    fun `centimeters bearings and thousandths follow the shared vectors`() {
        val cases = vectors.getValue("fields").jsonArray.map { it.jsonObject }

        assertEquals(setOf("centimeters", "bearing", "thousandths"), cases.map { it.getValue("kind").jsonPrimitive.content }.toSet())
        cases.forEach { case ->
            val kind = case.getValue("kind").jsonPrimitive.content
            val input = inputOf(case)
            val expected = expectedOf(case)
            val convert: () -> Long =
                when (kind) {
                    "centimeters" -> {
                        { input.toCentimeters("vector") }
                    }

                    "bearing" -> {
                        { input.toBearingHundredths("vector") }
                    }

                    else -> {
                        { input.toThousandths("vector") }
                    }
                }

            if (expected == null) {
                assertFailsWith<SpaceRecordMappingException>("$case") { convert() }
            } else {
                assertEquals(expected, convert(), "$case")
            }
        }
    }

    private fun inputOf(case: JsonObject): Double {
        val input = case.getValue("input").jsonPrimitive
        if (!input.isString) return input.double
        return when (input.content) {
            "NaN" -> Double.NaN
            "Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> error("Unknown vector input ${input.content}")
        }
    }

    private fun expectedOf(case: JsonObject): Long? = case.getValue("expected").let { if (it is JsonNull) null else it.jsonPrimitive.long }
}
