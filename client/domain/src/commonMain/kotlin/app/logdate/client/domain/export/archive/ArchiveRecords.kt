package app.logdate.client.domain.export.archive

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.captureTimeZoneOrNull
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Place
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableEntryBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** How a media reference appears in the archive: the file that was included, or why there is none. */
internal class MediaReferences(
    /** Null when media was not requested at all. */
    private val resolution: MediaResolution?,
) {
    fun refFor(reference: String): ArchiveMediaRef =
        when (val resolved = resolution?.get(reference)) {
            is ResolvedMedia.Included -> ArchiveMediaRef(ArchiveMediaStatus.INCLUDED, resolved.path, resolved.type.mimeType)
            is ResolvedMedia.Omitted -> omitted(resolved.reason)
            null -> omitted(if (resolution == null) ArchiveOmissionReason.NOT_REQUESTED else ArchiveOmissionReason.UNREADABLE)
        }

    private fun omitted(reason: ArchiveOmissionReason) = ArchiveMediaRef(ArchiveMediaStatus.OMITTED, omittedReason = reason)
}

/** Every media file the snapshot refers to, in the order they should be filed. */
internal fun ArchiveSnapshot.mediaRequests(exportZone: TimeZone): List<MediaRequest> {
    val fromNotes =
        notes.mapNotNull { note ->
            val reference = note.mediaRefOrNull() ?: return@mapNotNull null
            MediaRequest(reference, note.mediaKind(), note.creationTimestamp, note.captureTimeZoneOrNull() ?: exportZone)
        }
    val fromDrafts =
        drafts.flatMap { draft ->
            draft.blocks.flatMap { block ->
                buildList {
                    block.mediaReference()?.let { add(MediaRequest(it, block.mediaKind(), block.timestamp, exportZone)) }
                    if (block is SerializableVideoBlock) {
                        block.thumbnailUri?.let { add(MediaRequest(it, MediaKind.PHOTO, block.timestamp, exportZone)) }
                    }
                }
            }
        }
    return fromNotes + fromDrafts
}

private fun JournalNote.mediaKind(): MediaKind =
    when (this) {
        is JournalNote.Video -> MediaKind.VIDEO
        is JournalNote.Audio -> MediaKind.AUDIO
        is JournalNote.Image, is JournalNote.Text, is JournalNote.Handwriting -> MediaKind.PHOTO
    }

private fun SerializableEntryBlock.mediaReference(): String? =
    when (this) {
        is SerializableImageBlock -> uri
        is SerializableVideoBlock -> uri
        is SerializableAudioBlock -> uri
        is SerializableCameraBlock -> uri
        is SerializableTextBlock, is app.logdate.shared.model.SerializableHandwritingBlock -> null
    }

private fun SerializableEntryBlock.mediaKind(): MediaKind =
    when (this) {
        is SerializableVideoBlock -> MediaKind.VIDEO
        is SerializableAudioBlock -> MediaKind.AUDIO
        else -> MediaKind.PHOTO
    }

internal fun Journal.toArchiveJournal() =
    ArchiveJournal(
        id = id.toString(),
        title = title,
        description = description,
        createdAt = created,
        updatedAt = lastUpdated,
        favorite = isFavorited,
    )

internal fun JournalNote.toArchiveNote(
    journalIds: List<Uuid>,
    media: MediaReferences,
): ArchiveNote {
    val zone = captureTimeZoneOrNull()
    val text = (this as? JournalNote.Text)?.content
    val caption =
        when (this) {
            is JournalNote.Image -> caption
            is JournalNote.Video -> caption
            is JournalNote.Audio -> caption
            else -> ""
        }
    return ArchiveNote(
        id = uid.toString(),
        type = archiveType(),
        createdAt = creationTimestamp,
        updatedAt = lastUpdated,
        timeZone = timeZoneId,
        createdAtLocal = zone?.let { creationTimestamp.toRfc3339(it) },
        text = text,
        handwriting = (this as? JournalNote.Handwriting)?.document,
        unreadableHandwriting = (this as? JournalNote.Handwriting)?.unreadableContent,
        textFormat = ArchiveTextFormat.MARKDOWN.takeIf { text != null },
        caption = caption.takeUnless { this is JournalNote.Text },
        photoPresentation = (this as? JournalNote.Image)?.presentation?.name,
        capturedOn = capturedOn?.wireName,
        capturedDuring = capturedDuring?.wireName,
        media = mediaRefOrNull()?.let(media::refFor),
        durationMs =
            when (this) {
                is JournalNote.Audio -> durationMs
                is JournalNote.Video -> durationMs
                else -> null
            },
        transcription = (this as? JournalNote.Audio)?.transcription,
        transcript = (this as? JournalNote.Audio)?.transcript,
        location = location?.toArchiveLocation(),
        locationDetails = location?.toArchiveNoteLocation(),
        journalIds = journalIds.map { it.toString() },
    )
}

private fun JournalNote.archiveType(): ArchiveNoteType =
    when (this) {
        is JournalNote.Text -> ArchiveNoteType.TEXT
        is JournalNote.Handwriting -> ArchiveNoteType.HANDWRITING
        is JournalNote.Image -> ArchiveNoteType.IMAGE
        is JournalNote.Video -> ArchiveNoteType.VIDEO
        is JournalNote.Audio -> ArchiveNoteType.AUDIO
    }

private fun NoteLocation.toArchiveLocation(): ArchiveLocation? {
    val latitude = effectiveLatitude ?: return null
    val longitude = effectiveLongitude ?: return null
    return ArchiveLocation(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = coordinates?.altitude,
        accuracyMeters = coordinates?.accuracy?.toShortDouble(),
        placeName = displayName,
    )
}

private fun NoteLocation.toArchiveNoteLocation() =
    ArchiveNoteLocation(
        coordinates =
            coordinates?.let {
                ArchiveNoteCoordinates(it.latitude, it.longitude, it.altitude, it.accuracy?.toShortDouble())
            },
        place = place?.let { ArchivePlaceReference(it.id.toString(), it.name, it.latitude, it.longitude) },
    )

internal fun EditorDraft.toArchiveDraft(media: MediaReferences) =
    ArchiveDraft(
        id = id.toString(),
        journalIds = selectedJournalIds.map { it.toString() },
        createdAt = createdAt,
        updatedAt = lastModifiedAt,
        blocks = blocks.map { it.toArchiveBlock(media) },
    )

private fun SerializableEntryBlock.toArchiveBlock(media: MediaReferences): ArchiveDraftBlock {
    val latitude = locationLat
    val longitude = locationLng
    val location = if (latitude != null && longitude != null) ArchiveLocation(latitude, longitude, altitudeMeters = altitude) else null
    val base = ArchiveDraftBlock(id = id.toString(), type = ArchiveBlockType.TEXT, timestamp = timestamp, location = location)
    return when (this) {
        is SerializableTextBlock -> {
            base.copy(text = content)
        }

        is app.logdate.shared.model.SerializableHandwritingBlock -> {
            base.copy(type = ArchiveBlockType.HANDWRITING, handwriting = document)
        }

        is SerializableImageBlock -> {
            base.copy(
                type = ArchiveBlockType.IMAGE,
                media = uri?.let(media::refFor),
                caption = caption.ifEmpty { null },
                photoPresentation = presentation.name,
            )
        }

        is SerializableVideoBlock -> {
            base.copy(
                type = ArchiveBlockType.VIDEO,
                media = uri?.let(media::refFor),
                thumbnail = thumbnailUri?.let(media::refFor),
                caption = caption.ifEmpty { null },
            )
        }

        is SerializableAudioBlock -> {
            base.copy(type = ArchiveBlockType.AUDIO, media = uri?.let(media::refFor), durationMs = duration, transcription = transcription)
        }

        is SerializableCameraBlock -> {
            base.copy(type = ArchiveBlockType.CAMERA, media = uri?.let(media::refFor))
        }
    }
}

internal fun Place.UserDefined.toArchivePlace() =
    ArchivePlace(
        id = id.toString(),
        name = displayName,
        latitude = lat,
        longitude = lng,
        radiusMeters = radiusMeters,
        description = description,
    )

/** The profile, or null when the person never filled one in. Timestamps the app never set are left out. */
internal fun LogDateProfile.toArchiveProfile(): ArchiveProfile? {
    if (this == LogDateProfile()) return null
    return ArchiveProfile(
        displayName = displayName.ifBlank { null },
        birthday = birthday,
        bio = bio,
        originalBio = originalBio,
        createdAt = createdAt.takeIf { it != Instant.DISTANT_PAST },
        updatedAt = lastUpdatedAt.takeIf { it != Instant.DISTANT_PAST },
    )
}

internal fun LocationHistoryItem.toArchiveSample() =
    ArchiveLocationSample(
        timestamp = timestamp,
        loggedAt = loggedAt,
        latitude = location.latitude,
        longitude = location.longitude,
        altitudeMeters = location.altitude.value,
        accuracyMeters = accuracyMeters?.toShortDouble(),
        speedMetersPerSecond = speedMetersPerSecond?.toShortDouble(),
        bearingDegrees = bearingDegrees?.toShortDouble(),
        confidence = confidence.toShortDouble(),
        isGenuine = isGenuine,
        isMock = isMock,
        capturePipeline = capturePipeline.name,
        captureSource = captureSource.name,
    )

/** A float widened to a double without the noise (0.1f would otherwise read back as 0.10000000149011612). */
private fun Float.toShortDouble(): Double = toString().toDouble()

/** RFC 3339 local time with its UTC offset, for example `2026-09-17T21:30:05.123-06:00`. */
internal fun Instant.toRfc3339(zone: TimeZone): String {
    val local = toLocalDateTime(zone)
    val fraction =
        if (local.nanosecond == 0) {
            ""
        } else {
            "." +
                local.nanosecond
                    .toString()
                    .padStart(9, '0')
                    .trimEnd('0')
        }
    val offsetMinutes = zone.offsetAt(this).totalSeconds / 60
    val offset =
        if (offsetMinutes == 0) {
            "Z"
        } else {
            val sign = if (offsetMinutes < 0) "-" else "+"
            "$sign${(abs(offsetMinutes) / 60).pad()}:${(abs(offsetMinutes) % 60).pad()}"
        }
    return "${local.date}T${local.hour.pad()}:${local.minute.pad()}:${local.second.pad()}$fraction$offset"
}

private fun Int.pad() = toString().padStart(2, '0')
