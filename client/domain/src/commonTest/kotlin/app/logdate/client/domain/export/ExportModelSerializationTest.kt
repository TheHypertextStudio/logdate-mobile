package app.logdate.client.domain.export

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Unit tests proving that every [Export*] model survives a JSON encode → decode
 * cycle without field loss, type coercion, or precision degradation.
 *
 * These tests sit below the use-case layer: they exercise only the serialization
 * contracts that archive restores through [RestoreUserDataUseCase] implicitly
 * rely on. If a field annotation is wrong, a default is misapplied, or a custom
 * serializer loses precision, these tests fail before any database or ZIP is involved.
 */
class ExportModelSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }

    private inline fun <reified T> roundTrip(value: T): T = json.decodeFromString(json.encodeToString(value))

    // ── ExportNote ───────────────────────────────────────────────────────

    @Test
    fun `export note text type all fields preserved`() {
        val original =
            ExportNote(
                id = "note-text-001",
                type = "text",
                content = "Hello, world! 🌍",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_123L),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_060_456L),
                syncVersion = 7L,
            )
        val restored = roundTrip(original)

        assertEquals(original.id, restored.id)
        assertEquals(original.type, restored.type)
        assertEquals(original.content, restored.content)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
        assertEquals(original.syncVersion, restored.syncVersion)
        assertNull(restored.mediaPath)
        assertNull(restored.caption)
        assertNull(restored.durationMs)
        assertNull(restored.location)
    }

    @Test
    fun `export note image type media ref and caption preserved`() {
        val original =
            ExportNote(
                id = "note-img-001",
                type = "image",
                mediaPath = "media/note-img-001.jpg",
                caption = "Sunrise over the hills",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            )
        val restored = roundTrip(original)

        assertEquals(original.mediaPath, restored.mediaPath)
        assertEquals(original.caption, restored.caption)
        assertNull(restored.content)
        assertNull(restored.durationMs)
    }

    @Test
    fun `export note audio type duration preserved`() {
        val original =
            ExportNote(
                id = "note-audio-001",
                type = "audio",
                mediaPath = "media/note-audio-001.m4a",
                durationMs = 93_500L,
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            )
        val restored = roundTrip(original)

        assertEquals(original.durationMs, restored.durationMs)
        assertEquals(original.mediaPath, restored.mediaPath)
    }

    @Test
    fun `export note video type caption preserved`() {
        val original =
            ExportNote(
                id = "note-video-001",
                type = "video",
                mediaPath = "media/note-video-001.mp4",
                caption = "Dogs playing fetch",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
            )
        val restored = roundTrip(original)

        assertEquals(original.caption, restored.caption)
        assertEquals(original.mediaPath, restored.mediaPath)
    }

    @Test
    fun `export note with location all location fields preserved`() {
        val location =
            ExportLocation(
                latitude = 51.5074,
                longitude = -0.1278,
                placeName = "London",
                altitude = 11.3,
                accuracy = 4.5f,
            )
        val original =
            ExportNote(
                id = "note-geo-001",
                type = "text",
                content = "In London",
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                updatedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                location = location,
            )
        val restored = roundTrip(original)

        val rLoc = checkNotNull(restored.location) { "location was null after round-trip" }
        assertEquals(location.latitude, rLoc.latitude, 1e-9)
        assertEquals(location.longitude, rLoc.longitude, 1e-9)
        assertEquals(location.placeName, rLoc.placeName)
        assertEquals(location.altitude, rLoc.altitude)
        assertEquals(location.accuracy, rLoc.accuracy)
    }

    @Test
    fun `export note sync version zero default preserved`() {
        val original =
            ExportNote(
                id = "note-001",
                type = "text",
                content = "default sync version",
                createdAt = Instant.fromEpochMilliseconds(0L),
                updatedAt = Instant.fromEpochMilliseconds(0L),
                syncVersion = 0L,
            )
        assertEquals(0L, roundTrip(original).syncVersion)
    }

    @Test
    fun `export note unicode content preserved exactly`() {
        val original =
            ExportNote(
                id = "note-unicode",
                type = "text",
                content = "日記 📖 \"quoted\" \\ newline\ntab\tend",
                createdAt = Instant.fromEpochMilliseconds(0L),
                updatedAt = Instant.fromEpochMilliseconds(0L),
            )
        assertEquals(original.content, roundTrip(original).content)
    }

    // ── ExportMetadata ───────────────────────────────────────────────────

    @Test
    fun `export metadata all fields preserved`() {
        val original =
            ExportMetadata(
                version = ExportSchemaVersion.CURRENT,
                exportDate = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                userId = "user-abc",
                deviceId = "device-xyz",
                appVersion = "3.1.4",
                stats =
                    ExportStats(
                        journalCount = 5,
                        noteCount = 42,
                        draftCount = 3,
                        mediaCount = 18,
                        placeCount = 2,
                        locationHistoryCount = 100,
                        hasProfile = true,
                    ),
            )
        val restored = roundTrip(original)

        assertEquals(original.version, restored.version)
        assertEquals(original.exportDate, restored.exportDate)
        assertEquals(original.userId, restored.userId)
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.appVersion, restored.appVersion)
        assertEquals(original.stats.journalCount, restored.stats.journalCount)
        assertEquals(original.stats.noteCount, restored.stats.noteCount)
        assertEquals(original.stats.draftCount, restored.stats.draftCount)
        assertEquals(original.stats.mediaCount, restored.stats.mediaCount)
        assertEquals(original.stats.placeCount, restored.stats.placeCount)
        assertEquals(original.stats.locationHistoryCount, restored.stats.locationHistoryCount)
        assertEquals(original.stats.hasProfile, restored.stats.hasProfile)
    }

    // ── ExportJournalNoteRelation ─────────────────────────────────────────

    @Test
    fun `export journal note relation all fields preserved`() {
        val original =
            ExportJournalNoteRelation(
                journalId = "journal-001",
                noteId = "note-001",
                addedAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                syncVersion = 3L,
            )
        val restored = roundTrip(original)

        assertEquals(original.journalId, restored.journalId)
        assertEquals(original.noteId, restored.noteId)
        assertEquals(original.addedAt, restored.addedAt)
        assertEquals(original.syncVersion, restored.syncVersion)
    }

    // ── ExportLocation ────────────────────────────────────────────────────

    @Test
    fun `export location with null optionals preserved`() {
        val original = ExportLocation(latitude = 37.7749, longitude = -122.4194)
        val restored = roundTrip(original)

        assertEquals(original.latitude, restored.latitude, 1e-9)
        assertEquals(original.longitude, restored.longitude, 1e-9)
        assertNull(restored.placeName)
        assertNull(restored.altitude)
        assertNull(restored.accuracy)
    }

    @Test
    fun `export location with all optionals preserved`() {
        val original =
            ExportLocation(
                latitude = -33.8688,
                longitude = 151.2093,
                placeName = "Sydney",
                altitude = 39.0,
                accuracy = 8.0f,
            )
        val restored = roundTrip(original)

        assertEquals(original.placeName, restored.placeName)
        assertEquals(original.altitude, restored.altitude)
        assertEquals(original.accuracy, restored.accuracy)
    }

    // ── ExportPlace ───────────────────────────────────────────────────────

    @Test
    fun `export place all fields preserved`() {
        val original =
            ExportPlace(
                id = "place-001",
                displayName = "Home",
                latitude = 40.7128,
                longitude = -74.0060,
                radiusMeters = 50.0,
                description = "Where I live",
            )
        val restored = roundTrip(original)

        assertEquals(original.id, restored.id)
        assertEquals(original.displayName, restored.displayName)
        assertEquals(original.latitude, restored.latitude, 1e-9)
        assertEquals(original.longitude, restored.longitude, 1e-9)
        assertEquals(original.radiusMeters, restored.radiusMeters, 1e-9)
        assertEquals(original.description, restored.description)
    }

    // ── ExportLocationHistoryItem ─────────────────────────────────────────

    @Test
    fun `export location history item all fields preserved`() {
        val original =
            ExportLocationHistoryItem(
                sampleId = "sample-001",
                userId = "user-001",
                deviceId = "device-001",
                timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                loggedAt = Instant.fromEpochMilliseconds(1_700_000_001_000L),
                latitude = 48.8566,
                longitude = 2.3522,
                altitudeMeters = 35.0,
                confidence = 0.92f,
                isGenuine = true,
                capturePipeline = "PASSIVE",
                captureSource = "FUSED",
                accuracyMeters = 5.0f,
                speedMetersPerSecond = 1.2f,
                bearingDegrees = 270.0f,
                isMock = false,
            )
        val restored = roundTrip(original)

        assertEquals(original.sampleId, restored.sampleId)
        assertEquals(original.userId, restored.userId)
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.timestamp, restored.timestamp)
        assertEquals(original.loggedAt, restored.loggedAt)
        assertEquals(original.latitude, restored.latitude, 1e-9)
        assertEquals(original.longitude, restored.longitude, 1e-9)
        assertEquals(original.altitudeMeters, restored.altitudeMeters, 1e-9)
        assertEquals(original.confidence, restored.confidence)
        assertEquals(original.isGenuine, restored.isGenuine)
        assertEquals(original.capturePipeline, restored.capturePipeline)
        assertEquals(original.captureSource, restored.captureSource)
        assertEquals(original.accuracyMeters, restored.accuracyMeters)
        assertEquals(original.speedMetersPerSecond, restored.speedMetersPerSecond)
        assertEquals(original.bearingDegrees, restored.bearingDegrees)
        assertEquals(original.isMock, restored.isMock)
    }

    // ── ExportMediaFile ───────────────────────────────────────────────────

    @Test
    fun `export media file paths preserved`() {
        val original =
            ExportMediaFile(
                exportPath = "media/note-img-001.jpg",
                sourceUri = "content://media/external/images/1234",
            )
        val restored = roundTrip(original)

        assertEquals(original.exportPath, restored.exportPath)
        assertEquals(original.sourceUri, restored.sourceUri)
    }

    // ── ExportSchemaVersion ───────────────────────────────────────────────

    @Test
    fun `export schema version serializes as string`() {
        val version = ExportSchemaVersion(1, 2)
        val json = Json.encodeToString(version)
        assertEquals("\"1.2\"", json)
        assertEquals(version, Json.decodeFromString(json))
    }

    @Test
    fun `export schema version current survives round trip`() {
        val restored = roundTrip(ExportSchemaVersion.CURRENT)
        assertEquals(ExportSchemaVersion.CURRENT, restored)
    }

    // ── Timestamp precision ───────────────────────────────────────────────

    @Test
    fun `instant millisecond precision preserved in json`() {
        val t = Instant.fromEpochMilliseconds(1_700_000_000_123L)
        val note =
            ExportNote(
                id = "ts-test",
                type = "text",
                createdAt = t,
                updatedAt = t,
            )
        val restored = roundTrip(note)
        assertEquals(t, restored.createdAt)
        assertEquals(t, restored.updatedAt)
    }
}
