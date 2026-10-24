package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportNote
import app.logdate.client.domain.export.archive.ArchiveNoteLocation
import app.logdate.client.repository.journals.CaptureActivity
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.client.repository.journals.withCapturedDuring
import app.logdate.client.repository.journals.withCapturedOn
import app.logdate.shared.model.PhotoPresentation
import kotlin.uuid.Uuid

internal fun ExportNote.toJournalNote(
    id: Uuid,
    mediaResolution: MediaResolution,
): JournalNote? {
    val location = locationDetails?.toNoteLocation() ?: location?.toNoteLocation()
    val resolvedMediaRef =
        mediaResolution.uri ?: mediaPath?.takeIf { mediaResolution.allowSourceFallback && it.isNotBlank() }
    val note =
        when (type.lowercase()) {
            "handwriting" -> {
                toHandwritingNote(id, location)
            }

            "text" -> {
                toTextNote(id, location)
            }

            "image" -> {
                JournalNote.Image(
                    uid = id,
                    creationTimestamp = createdAt,
                    lastUpdated = updatedAt,
                    mediaRef = resolvedMediaRef ?: return null,
                    caption = caption.orEmpty(),
                    presentation = PhotoPresentation.entries.firstOrNull { it.name == photoPresentation } ?: PhotoPresentation.EdgeToEdge,
                    timeZoneId = timeZone,
                    syncVersion = syncVersion,
                    location = location,
                )
            }

            "video" -> {
                JournalNote.Video(
                    uid = id,
                    creationTimestamp = createdAt,
                    lastUpdated = updatedAt,
                    mediaRef = resolvedMediaRef ?: return null,
                    caption = caption.orEmpty(),
                    timeZoneId = timeZone,
                    syncVersion = syncVersion,
                    location = location,
                    durationMs = durationMs,
                )
            }

            "audio" -> {
                JournalNote.Audio(
                    uid = id,
                    creationTimestamp = createdAt,
                    lastUpdated = updatedAt,
                    mediaRef = resolvedMediaRef ?: return null,
                    durationMs = this.durationMs ?: 0,
                    transcription = transcription.orEmpty(),
                    transcript = transcript,
                    caption = caption.orEmpty(),
                    timeZoneId = timeZone,
                    syncVersion = syncVersion,
                    location = location,
                )
            }

            else -> {
                null
            }
        } ?: return null
    return note.withCapturedOn(CaptureDevice.fromWireName(capturedOn)).withCapturedDuring(CaptureActivity.fromWireName(capturedDuring))
}

private fun ExportNote.toTextNote(
    id: Uuid,
    location: NoteLocation?,
): JournalNote.Text =
    JournalNote.Text(
        uid = id,
        creationTimestamp = createdAt,
        lastUpdated = updatedAt,
        content = content.orEmpty(),
        timeZoneId = timeZone,
        syncVersion = syncVersion,
        location = location,
    )

private fun ExportNote.toHandwritingNote(
    id: Uuid,
    location: NoteLocation?,
): JournalNote.Handwriting =
    JournalNote.Handwriting(
        uid = id,
        creationTimestamp = createdAt,
        lastUpdated = updatedAt,
        document = requireNotNull(handwriting) { "Missing original handwriting document" },
        unreadableContent = unreadableHandwriting,
        timeZoneId = timeZone,
        syncVersion = syncVersion,
        location = location,
    )

private fun ArchiveNoteLocation.toNoteLocation() =
    NoteLocation(
        coordinates =
            coordinates?.let { NoteCoordinates(it.latitude, it.longitude, it.altitudeMeters, it.accuracyMeters?.toFloat()) },
        place = place?.let { NotePlace(Uuid.parse(it.id), it.name, it.latitude, it.longitude) },
    )

private fun app.logdate.client.domain.export.ExportLocation.toNoteLocation(): NoteLocation {
    val coords =
        NoteCoordinates(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            accuracy = accuracy,
        )
    val place =
        placeName?.let {
            NotePlace(
                id = Uuid.random(),
                name = it,
                latitude = latitude,
                longitude = longitude,
            )
        }
    return NoteLocation(coordinates = coords, place = place)
}

internal fun JournalNote.withExistingCaptureMetadata(existing: JournalNote?): JournalNote {
    val zone = timeZoneId ?: existing?.timeZoneId
    val device = existing?.capturedOn ?: capturedOn
    val activity = existing?.capturedDuring ?: capturedDuring
    return when (this) {
        is JournalNote.Handwriting -> copy(timeZoneId = zone, capturedOn = device, capturedDuring = activity)
        is JournalNote.Text -> copy(timeZoneId = zone, capturedOn = device, capturedDuring = activity)
        is JournalNote.Image -> copy(timeZoneId = zone, capturedOn = device, capturedDuring = activity)
        is JournalNote.Audio -> copy(timeZoneId = zone, capturedOn = device, capturedDuring = activity)
        is JournalNote.Video -> copy(timeZoneId = zone, capturedOn = device, capturedDuring = activity)
    }
}
