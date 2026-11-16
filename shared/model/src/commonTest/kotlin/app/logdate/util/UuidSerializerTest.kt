package app.logdate.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tests for [UuidSerializer], ensuring that Kotlin's UUID can be properly
 * serialized and deserialized using kotlinx.serialization.
 *
 * These tests verify that:
 * 1. UUIDs are correctly serialized to JSON strings
 * 2. JSON strings with UUIDs can be deserialized back to Uuid objects
 * 3. Round-trip serialization/deserialization preserves UUID values
 */
@OptIn(ExperimentalUuidApi::class)
class UuidSerializerTest {
    /**
     * Test data class that uses the [UuidSerializer] for its ID property.
     * Used for testing serialization and deserialization.
     */
    @Serializable
    private data class TestData(
        @Serializable(with = UuidSerializer::class)
        val id: Uuid,
        val name: String,
    )

    /**
     * Tests that [UuidSerializer] correctly serializes a Uuid to a JSON string.
     *
     * Verifies that:
     * - The UUID is properly encoded as a string in the JSON output
     * - The format matches the standard UUID string representation (with hyphens)
     */
    @Test
    fun `uuid serialization`() {
        // Create a test UUID
        val testUuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val testData = TestData(testUuid, "Test Name")

        // Serialize the data
        val json = Json.Default
        val serialized = json.encodeToString(testData)

        // The JSON should contain the UUID in string format
        val expectedJson = """{"id":"550e8400-e29b-41d4-a716-446655440000","name":"Test Name"}"""
        assertEquals(expectedJson, serialized, "UUID should be serialized as string")
    }

    /**
     * Tests that [UuidSerializer] correctly deserializes a JSON string to a Uuid.
     *
     * Verifies that:
     * - The string UUID in the JSON is correctly parsed to a Uuid object
     * - The other fields in the JSON are also properly deserialized
     */
    @Test
    fun `uuid deserialization`() {
        // JSON with UUID in string format
        val jsonString = """{"id":"550e8400-e29b-41d4-a716-446655440000","name":"Test Name"}"""

        // Deserialize the data
        val json = Json.Default
        val deserialized = json.decodeFromString<TestData>(jsonString)

        // Check that the UUID was correctly deserialized
        assertEquals(
            Uuid.parse("550e8400-e29b-41d4-a716-446655440000"),
            deserialized.id,
            "UUID should be deserialized correctly",
        )
        assertEquals("Test Name", deserialized.name, "Other fields should be deserialized correctly")
    }

    /**
     * Tests round-trip serialization and deserialization with [UuidSerializer].
     *
     * Verifies that:
     * - A random UUID can be serialized to JSON and then deserialized back
     * - The resulting Uuid object is identical to the original
     * - The entire data object is preserved correctly
     *
     * This test is particularly important because it uses a random UUID rather than
     * a hardcoded one, ensuring robust serialization for any valid UUID.
     */
    @Test
    fun `round trip`() {
        // Create a random UUID
        val randomUuid = Uuid.random()
        val original = TestData(randomUuid, "Random Data")

        // Serialize then deserialize
        val json = Json.Default
        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<TestData>(serialized)

        // The round-trip should preserve the data
        assertEquals(original, deserialized, "Round-trip serialization should preserve data")
        assertEquals(
            randomUuid.toString(),
            deserialized.id.toString(),
            "UUID string representation should be identical after round-trip",
        )
    }
}
