package app.logdate.client.intelligence.rewind.local

import app.logdate.client.intelligence.rewind.strategy.AudioEntryWithTranscript
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LocalStoryBeatDetectorTest {
    private val detector = LocalStoryBeatDetector()
    private val day0 = Instant.fromEpochMilliseconds(0L)

    private fun textEntry(
        content: String,
        at: Instant = day0,
    ): JournalNote.Text =
        JournalNote.Text(
            creationTimestamp = at,
            lastUpdated = at,
            content = content,
        )

    private fun audioEntry(
        at: Instant = day0,
        transcriptionText: String? = null,
    ): AudioEntryWithTranscript =
        AudioEntryWithTranscript(
            audio =
                JournalNote.Audio(
                    mediaRef = "file://recording.m4a",
                    durationMs = 30_000,
                    creationTimestamp = at,
                    lastUpdated = at,
                ),
            transcriptionText = transcriptionText,
        )

    private fun locationPoint(
        at: Instant,
        latitude: Double,
        longitude: Double,
    ): LocationHistoryItem =
        LocationHistoryItem(
            userId = "test-user",
            deviceId = "test-device",
            timestamp = at,
            location =
                Location(
                    latitude = latitude,
                    longitude = longitude,
                    altitude = LocationAltitude(0.0, AltitudeUnit.METERS),
                ),
            confidence = 1f,
            isGenuine = true,
            capturePipeline = LocationCapturePipeline.LEGACY,
            captureSource = LocationCaptureSource.BACKGROUND_PERIODIC,
        )

    @Test
    fun `returns empty list when there are no entries`() {
        val beats = detector.detect(emptyList())
        assertEquals(emptyList(), beats)
    }

    @Test
    fun `produces one beat per distinct day with content`() {
        val entries =
            listOf(
                textEntry("had a great dinner with friends tonight.", at = day0 + 1.hours),
                textEntry("project deadline finally done — relief.", at = day0 + 1.days + 2.hours),
                textEntry("quiet evening, just reading.", at = day0 + 3.days + 4.hours),
            )
        val beats = detector.detect(entries)
        assertEquals(3, beats.size)
    }

    @Test
    fun `beat carries the day's entries as evidence ids`() {
        val mondayEntry = textEntry("had a great dinner with friends.", at = day0 + 1.hours)
        val wednesdayEntry = textEntry("project deadline crunch.", at = day0 + 2.days + 4.hours)
        val beats = detector.detect(listOf(mondayEntry, wednesdayEntry))
        assertEquals(2, beats.size)
        // Each beat references only its day's entry uid.
        assertTrue(beats[0].evidenceIds.contains(mondayEntry.uid.toString()))
        assertTrue(beats[1].evidenceIds.contains(wednesdayEntry.uid.toString()))
        assertEquals(
            emptyList(),
            beats[0].evidenceIds.intersect(beats[1].evidenceIds.toSet()).toList(),
        )
    }

    @Test
    fun `caps at six beats by collapsing oldest days into a phase`() {
        val entries =
            (0..9).map { dayIdx ->
                textEntry("entry on day $dayIdx", at = day0 + dayIdx.days + 1.hours)
            }
        val beats = detector.detect(entries)
        assertTrue(beats.size <= 6, "expected ≤6 beats, got ${beats.size}")
    }

    @Test
    fun `keeps one beat per day when all GPS points stay close`() {
        val day = day0 + 1.hours
        val entries =
            listOf(
                textEntry("morning at home with coffee.", at = day),
                textEntry("afternoon, kept reading.", at = day + 6.hours),
            )
        // Two points within 500m — same neighborhood.
        val locations =
            listOf(
                locationPoint(at = day, latitude = 37.7749, longitude = -122.4194),
                locationPoint(at = day + 6.hours, latitude = 37.7755, longitude = -122.4200),
            )
        val beats =
            detector.detect(
                textEntries = entries,
                locationHistory = locations,
            )
        assertEquals(1, beats.size)
    }

    @Test
    fun `splits a single day into two beats when the user travels far`() {
        val day = day0 + 1.hours
        val morningEntry = textEntry("morning at home.", at = day)
        val afternoonEntry = textEntry("hit the coast in the afternoon.", at = day + 6.hours)
        val locations =
            listOf(
                // Morning cluster — San Francisco
                locationPoint(at = day, latitude = 37.7749, longitude = -122.4194),
                locationPoint(at = day + 30.minutes, latitude = 37.7755, longitude = -122.4200),
                // Afternoon cluster — Pacifica, ~13km south-west
                locationPoint(at = day + 6.hours, latitude = 37.6038, longitude = -122.4995),
                locationPoint(at = day + 7.hours, latitude = 37.6040, longitude = -122.5000),
            )
        val beats =
            detector.detect(
                textEntries = listOf(morningEntry, afternoonEntry),
                locationHistory = locations,
            )
        assertEquals(2, beats.size)
        // First beat carries the morning entry; second carries the afternoon entry.
        assertTrue(beats[0].evidenceIds.contains(morningEntry.uid.toString()))
        assertTrue(beats[1].evidenceIds.contains(afternoonEntry.uid.toString()))
    }

    @Test
    fun `location split retains every journal entry between and outside GPS samples`() {
        val early = textEntry("Early morning before location capture.", at = day0 + 1.hours)
        val between = textEntry("The journey between the two places.", at = day0 + 4.hours)
        val late = textEntry("Late evening after location capture.", at = day0 + 9.hours)
        val locations =
            listOf(
                locationPoint(day0 + 2.hours, 37.7749, -122.4194),
                locationPoint(day0 + 3.hours, 37.7755, -122.4200),
                locationPoint(day0 + 6.hours, 37.6038, -122.4995),
                locationPoint(day0 + 7.hours, 37.6040, -122.5000),
            )

        val beats = detector.detect(listOf(early, between, late), locationHistory = locations)

        assertEquals(3, beats.flatMap { it.evidenceIds }.distinct().size)
        assertEquals(
            setOf(early.uid.toString(), between.uid.toString(), late.uid.toString()),
            beats.flatMap { it.evidenceIds }.toSet(),
        )
    }

    @Test
    fun `respects the six-beat cap even with location subdivision`() {
        val entries =
            (0..4).map { dayIdx ->
                textEntry("entry on day $dayIdx", at = day0 + dayIdx.days + 1.hours)
            }
        // Day 4 has three location clusters spread far apart.
        val locations =
            listOf(
                locationPoint(at = day0 + 4.days, latitude = 37.7749, longitude = -122.4194),
                locationPoint(at = day0 + 4.days + 1.hours, latitude = 37.7755, longitude = -122.4200),
                locationPoint(at = day0 + 4.days + 4.hours, latitude = 37.6038, longitude = -122.4995),
                locationPoint(at = day0 + 4.days + 5.hours, latitude = 37.6040, longitude = -122.5000),
                locationPoint(at = day0 + 4.days + 8.hours, latitude = 37.4419, longitude = -122.1430),
                locationPoint(at = day0 + 4.days + 9.hours, latitude = 37.4420, longitude = -122.1432),
            )
        val beats =
            detector.detect(
                textEntries = entries,
                locationHistory = locations,
            )
        assertTrue(beats.size <= 6, "expected ≤6 beats, got ${beats.size}")
    }

    @Test
    fun `tags emotional weight from content words`() {
        val happy =
            textEntry(
                "I felt thrilled and grateful — what a wonderful, joyful day with friends.",
                at = day0 + 1.hours,
            )
        val rough =
            textEntry(
                "I felt completely overwhelmed and exhausted; everything went wrong.",
                at = day0 + 2.days + 1.hours,
            )
        val beats = detector.detect(listOf(happy, rough))
        assertEquals(2, beats.size)
        assertEquals("joyful", beats[0].emotionalWeight)
        assertEquals("heavy", beats[1].emotionalWeight)
    }

    @Test
    fun `produces a beat for an audio-only day with no transcript`() {
        val audio = audioEntry(at = day0 + 1.hours, transcriptionText = null)
        val beats =
            detector.detect(
                textEntries = emptyList(),
                audio = listOf(audio),
            )
        // An audio-only week must not vanish — it's still a real moment, transcript or not.
        assertEquals(1, beats.size)
        assertTrue(beats[0].evidenceIds.contains(audio.audio.uid.toString()))
    }

    @Test
    fun `picks the moment headline from an audio transcript when there's no text`() {
        val audio =
            audioEntry(
                at = day0 + 1.hours,
                transcriptionText = "I finally finished the project I've been putting off for weeks.",
            )
        val beats =
            detector.detect(
                textEntries = emptyList(),
                audio = listOf(audio),
            )
        assertEquals(1, beats.size)
        assertEquals("I finally finished the project I've been putting off for weeks.", beats[0].moment)
    }
}
