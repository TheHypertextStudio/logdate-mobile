package app.logdate.client.sync.datalayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Unit tests for [HealthSnapshotDataMapper].
 *
 * Validates the serialization and deserialization of health snapshots between
 * local models and Wear OS Data Layer maps, ensuring data integrity for heart
 * rate, steps, and other health metrics.
 */
class HealthSnapshotDataMapperTest {
    private val mapper = HealthSnapshotDataMapper()

    private val fixedTime = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val fixedUuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
    private val fixedNoteId = Uuid.parse("660e8400-e29b-41d4-a716-446655440000")

    // =======================================================================
    // Round-trip serialization
    // =======================================================================

    @Test
    fun `full snapshot round trip`() {
        val snapshot =
            HealthSnapshotSyncData(
                id = fixedUuid,
                noteId = fixedNoteId,
                heartRateBpm = 72,
                heartRateVariabilityMs = 45.5f,
                stepCount = 8432,
                stressLevel = 0.3f,
                cumulativeCalories = 1250.5f,
                timestamp = fixedTime,
                source = "wear_health_services",
            )

        val map = mapper.toDataMap(snapshot)
        val restored = mapper.fromDataMap(map)

        assertEquals(snapshot, restored)
    }

    @Test
    fun `snapshot with null fields round trips`() {
        val snapshot =
            HealthSnapshotSyncData(
                id = fixedUuid,
                noteId = null,
                heartRateBpm = null,
                heartRateVariabilityMs = null,
                stepCount = null,
                stressLevel = null,
                cumulativeCalories = null,
                timestamp = fixedTime,
                source = "wear_health_services",
            )

        val map = mapper.toDataMap(snapshot)
        val restored = mapper.fromDataMap(map)

        assertEquals(snapshot, restored)
        assertNull(restored.noteId)
        assertNull(restored.heartRateBpm)
        assertNull(restored.stepCount)
    }

    @Test
    fun `snapshot with only heart rate round trips`() {
        val snapshot =
            HealthSnapshotSyncData(
                id = fixedUuid,
                noteId = fixedNoteId,
                heartRateBpm = 85,
                timestamp = fixedTime,
                source = "wear_health_services",
            )

        val map = mapper.toDataMap(snapshot)
        val restored = mapper.fromDataMap(map)

        assertEquals(85, restored.heartRateBpm)
        assertNull(restored.stepCount)
        assertNull(restored.stressLevel)
    }

    // =======================================================================
    // Data map key validation
    // =======================================================================

    @Test
    fun `data map contains required keys`() {
        val snapshot =
            HealthSnapshotSyncData(
                id = fixedUuid,
                noteId = fixedNoteId,
                heartRateBpm = 72,
                timestamp = fixedTime,
                source = "wear_health_services",
            )

        val map = mapper.toDataMap(snapshot)

        assertEquals(fixedUuid.toString(), map[HealthSnapshotDataMapper.KEY_UID])
        assertTrue(map.containsKey(HealthSnapshotDataMapper.KEY_JSON_PAYLOAD))
    }

    // =======================================================================
    // Error handling
    // =======================================================================

    @Test
    fun `from data map throws on missing payload`() {
        val map = mapOf(HealthSnapshotDataMapper.KEY_UID to fixedUuid.toString())

        assertFailsWith<IllegalArgumentException> {
            mapper.fromDataMap(map)
        }
    }

    @Test
    fun `from data map throws on empty map`() {
        assertFailsWith<IllegalArgumentException> {
            mapper.fromDataMap(emptyMap())
        }
    }

    @Test
    fun `from data map throws on invalid json`() {
        val map =
            mapOf(
                HealthSnapshotDataMapper.KEY_UID to fixedUuid.toString(),
                HealthSnapshotDataMapper.KEY_JSON_PAYLOAD to "not valid json",
            )

        assertFailsWith<Exception> {
            mapper.fromDataMap(map)
        }
    }

    // =======================================================================
    // Path generation and parsing
    // =======================================================================

    @Test
    fun `health path uses id`() {
        val path = HealthSnapshotDataMapper.healthPath(fixedUuid)
        assertEquals("/logdate/health/550e8400-e29b-41d4-a716-446655440000", path)
    }

    @Test
    fun `is health path returns true for health data paths`() {
        assertTrue(HealthSnapshotDataMapper.isHealthPath("/logdate/health/550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `is health path returns false for unrelated paths`() {
        assertFalse(HealthSnapshotDataMapper.isHealthPath("/logdate/notes/some-id"))
        assertFalse(HealthSnapshotDataMapper.isHealthPath("/logdate/journals/some-id"))
    }

    @Test
    fun `health id from path extracts correct uuid`() {
        val path = "/logdate/health/550e8400-e29b-41d4-a716-446655440000"
        val extracted = HealthSnapshotDataMapper.healthIdFromPath(path)
        assertEquals(fixedUuid, extracted)
    }

    // =======================================================================
    // Batch serialization
    // =======================================================================

    @Test
    fun `multiple snapshots serialize independently`() {
        val snapshots =
            listOf(
                HealthSnapshotSyncData(
                    id = Uuid.random(),
                    noteId = Uuid.random(),
                    heartRateBpm = 70,
                    timestamp = fixedTime,
                    source = "watch",
                ),
                HealthSnapshotSyncData(
                    id = Uuid.random(),
                    noteId = null,
                    stepCount = 5000,
                    timestamp = fixedTime,
                    source = "watch",
                ),
            )

        val maps = snapshots.map { mapper.toDataMap(it) }
        val restored = maps.map { mapper.fromDataMap(it) }

        assertEquals(snapshots.size, restored.size)
        for (i in snapshots.indices) {
            assertEquals(snapshots[i], restored[i])
        }
    }
}
