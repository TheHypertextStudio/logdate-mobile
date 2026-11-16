package app.logdate.client.sync.space.mappers

import app.logdate.client.repository.journals.CaptureActivity
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.shared.model.PhotoPresentation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

abstract class NoteEntryMapperFixture {
    protected val created = Instant.parse("2026-10-12T07:41:09.512Z")
    protected val updated = Instant.parse("2026-10-13T21:02:00.000000001Z")
    protected val home = NotePlace(Uuid.parse("0b8f3c1e-6a2d-4e9f-8c7b-1d2e3f4a5b6c"), "Casa da Avó 🏡", 38.7223, -9.1393)
    protected val mapper = NoteEntryMapper()
    protected val textId = Uuid.random()
    protected val imageId = Uuid.random()
    protected val videoId = Uuid.random()
    protected val audioId = Uuid.random()
    protected val photoRef = uploadedRef(imageId, "aa", "image/jpeg")
    protected val clipRef = uploadedRef(videoId, "bb", "video/mp4", size = null)
    protected val voiceRef = uploadedRef(audioId, "cc", "audio/mp4")

    protected val fullLocation =
        NoteLocation(
            coordinates = NoteCoordinates(latitude = -33.868820, longitude = 151.209295, altitude = -12.5, accuracy = 4.75f),
            place = home,
        )

    protected val fullNotes: List<JournalNote> =
        listOf(
            JournalNote.Text(
                uid = textId,
                creationTimestamp = created,
                lastUpdated = updated,
                content = "# A walk\n\nBy the river, with **Ana**. 日本語も。👨‍👩‍👧",
                location = fullLocation,
                timeZoneId = "Australia/Sydney",
                capturedOn = CaptureDevice.PHONE,
                capturedDuring = CaptureActivity.MOVING,
            ),
            JournalNote.Image(
                uid = imageId,
                creationTimestamp = created,
                lastUpdated = updated,
                mediaRef = photoRef,
                caption = "The view from the *top*",
                presentation = PhotoPresentation.Framed,
                location = fullLocation,
                timeZoneId = "Asia/Kolkata",
                capturedOn = CaptureDevice.WATCH,
                capturedDuring = CaptureActivity.IN_VEHICLE,
            ),
            JournalNote.Video(
                uid = videoId,
                creationTimestamp = created,
                lastUpdated = updated,
                mediaRef = clipRef,
                caption = "Waves",
                durationMs = 61_250,
                location = fullLocation,
                timeZoneId = "America/St_Johns",
                capturedOn = CaptureDevice.PHONE,
                capturedDuring = CaptureActivity.MOVING,
            ),
            JournalNote.Audio(
                uid = audioId,
                creationTimestamp = created,
                lastUpdated = updated,
                mediaRef = voiceRef,
                durationMs = 8_200,
                caption = "Morning thoughts",
                location = fullLocation,
                timeZoneId = "Europe/Lisbon",
                capturedOn = CaptureDevice.WATCH,
                capturedDuring = CaptureActivity.IN_VEHICLE,
            ),
        )

    protected fun roundTrip(note: JournalNote): JournalNote {
        val record = mapper.toRecord(note)
        assertValidRecord(mapper.collection, record)
        return mapper.fromRecord(mapper.rkeyOf(note), record)
    }

    protected fun writtenText(note: JournalNote.Text): String =
        mapper
            .toRecord(note)
            .getValue("blocks")
            .jsonArray[0]
            .jsonObject
            .getValue("text")
            .jsonPrimitive.content

    /** The text Markdown shows for [markdown] made only of escapes and hard breaks, as the CommonMark spec reads them. */
    protected fun readMarkdownEscapes(markdown: String): String {
        val punctuation = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        val out = StringBuilder()
        var index = 0
        while (index < markdown.length) {
            val char = markdown[index]
            val next = markdown.getOrNull(index + 1)
            when {
                char == '\\' && next != null && next in punctuation -> {
                    out.append(next)
                    index += 2
                }

                char == '\\' && next == '\n' -> {
                    out.append('\n')
                    index += 2
                }

                else -> {
                    out.append(char)
                    index++
                }
            }
        }
        return out.toString()
    }

    protected fun assertLocation(note: JournalNote): NoteLocation = note.location ?: error("no location")

    protected fun JournalNote.withLocation(location: NoteLocation): JournalNote =
        when (this) {
            is JournalNote.Handwriting -> copy(location = location)
            is JournalNote.Text -> copy(location = location)
            is JournalNote.Image -> copy(location = location)
            is JournalNote.Video -> copy(location = location)
            is JournalNote.Audio -> copy(location = location)
        }

    protected fun JournalNote.withMediaRef(mediaRef: String): JournalNote =
        when (this) {
            is JournalNote.Handwriting -> this
            is JournalNote.Text -> this
            is JournalNote.Image -> copy(mediaRef = mediaRef)
            is JournalNote.Video -> copy(mediaRef = mediaRef)
            is JournalNote.Audio -> copy(mediaRef = mediaRef)
        }

    protected fun JournalNote.withoutTranscript(): JournalNote = if (this is JournalNote.Audio) copy(transcript = null) else this

    protected fun JournalNote.withTranscript(): JournalNote = (this as JournalNote.Audio).copy(transcript = TranscriptDocument())

    protected fun JsonObject.withBlock(change: (JsonObject) -> Map<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        val block = getValue("blocks").jsonArray[0].jsonObject
        return JsonObject(this + ("blocks" to JsonArray(listOf(JsonObject(change(block))))))
    }

    protected fun JsonObject.withLocation(change: (JsonObject) -> Map<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        JsonObject(this + ("location" to JsonObject(change(getValue("location").jsonObject))))

    protected fun ref(
        collection: String,
        rkey: String,
    ) = buildJsonObject {
        put("collection", JsonPrimitive(collection))
        put("rkey", JsonPrimitive(rkey))
    }
}
