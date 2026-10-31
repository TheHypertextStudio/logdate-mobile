package app.logdate.client.data.notes

import app.logdate.client.data.LenientJson
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.GenericNoteData
import app.logdate.client.database.entities.ImageNoteEntity
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.entities.VideoNoteEntity
import app.logdate.client.repository.journals.CaptureActivity
import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.NoteCoordinates
import app.logdate.client.repository.journals.NoteLocation
import app.logdate.client.repository.journals.NotePlace
import app.logdate.shared.model.HandwritingDocument
import app.logdate.shared.model.PhotoPresentation
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

private val locationJson = LenientJson

/**
 * Converts entity location fields to a NoteLocation domain model.
 * Returns null if no location data is present.
 */
private fun mapLocation(
    latitude: Double?,
    longitude: Double?,
    altitude: Double?,
    accuracy: Float?,
    place: NotePlace?,
    placeId: Uuid?,
    locationMetadata: String?,
): NoteLocation? {
    locationMetadata?.decodeLocation()?.let { stored ->
        val source = stored.place
        val resolved = place?.takeIf { it.id == source?.id }
        val projected =
            when {
                resolved == null -> source
                !stored.preserveSourcePlace -> resolved
                else ->
                    source?.let { snapshot ->
                        snapshot.copy(
                            name = snapshot.name ?: resolved.name,
                            latitude = snapshot.latitude ?: resolved.latitude,
                            longitude = snapshot.longitude ?: resolved.longitude,
                        )
                    }
            }
        return NoteLocation(stored.coordinates, projected)
    }
    val knownPlace = place ?: placeId?.let { NotePlace(it) }
    // If we have no coordinates and no place reference, return null
    if (latitude == null && longitude == null && knownPlace == null) {
        return null
    }

    val coordinates =
        if (latitude != null && longitude != null) {
            NoteCoordinates(
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                accuracy = accuracy,
            )
        } else {
            null
        }

    return NoteLocation(
        coordinates = coordinates,
        place = knownPlace,
    )
}

internal fun GenericNoteData.referencedPlaceId(): Uuid? {
    val stored = locationMetadata?.decodeLocation()
    return if (stored != null) stored.place?.id else placeId
}

private fun String.decodeLocation(): StoredNoteLocation? =
    try {
        locationJson.decodeFromString(StoredNoteLocation.serializer(), this)
    } catch (failure: IllegalArgumentException) {
        Napier.w("Could not decode stored note location metadata; retaining legacy location fields", failure)
        null
    }

@Serializable
private data class StoredNoteLocation(
    val coordinates: NoteCoordinates? = null,
    val place: NotePlace? = null,
    val preserveSourcePlace: Boolean = false,
)

private fun NoteLocation.storedLocation(preserveSourcePlace: Boolean): String =
    locationJson.encodeToString(StoredNoteLocation.serializer(), StoredNoteLocation(coordinates, place, preserveSourcePlace))

fun TextNoteEntity.toModel(place: NotePlace? = null): JournalNote =
    if (contentFormat == "handwriting") {
        val decoded = runCatching { HandwritingDocument.decode(content) }.getOrNull()
        JournalNote.Handwriting(
            uid = uid,
            document = decoded ?: HandwritingDocument(),
            unreadableContent = if (decoded == null) content else null,
            creationTimestamp = created,
            lastUpdated = lastUpdated,
            syncVersion = syncVersion,
            location = mapLocation(latitude, longitude, altitude, locationAccuracy, place, placeId, locationMetadata),
            timeZoneId = timeZoneId,
            capturedOn = CaptureDevice.fromWireName(capturedOn),
            capturedDuring = CaptureActivity.fromWireName(capturedDuring),
        )
    } else {
        JournalNote.Text(
            uid = uid,
            content = content,
            creationTimestamp = created,
            lastUpdated = lastUpdated,
            syncVersion = syncVersion,
            location = mapLocation(latitude, longitude, altitude, locationAccuracy, place, placeId, locationMetadata),
            timeZoneId = timeZoneId,
            capturedOn = CaptureDevice.fromWireName(capturedOn),
            capturedDuring = CaptureActivity.fromWireName(capturedDuring),
        )
    }

fun JournalNote.Text.toEntity(
    linkedPlaceId: Uuid? = null,
    preserveSourcePlace: Boolean = false,
    sourceLocation: GenericNoteData? = null,
) = TextNoteEntity(
    uid = uid,
    content = content,
    created = creationTimestamp,
    lastUpdated = lastUpdated,
    syncVersion = syncVersion,
    latitude = location?.coordinates?.latitude,
    longitude = location?.coordinates?.longitude,
    altitude = location?.coordinates?.altitude,
    locationAccuracy = location?.coordinates?.accuracy,
    placeId = linkedPlaceId,
    locationMetadata = if (sourceLocation != null) sourceLocation.locationMetadata else location?.storedLocation(preserveSourcePlace),
    timeZoneId = timeZoneId,
    capturedOn = capturedOn?.wireName,
    capturedDuring = capturedDuring?.wireName,
)

fun JournalNote.Handwriting.toEntity(
    linkedPlaceId: Uuid? = null,
    preserveSourcePlace: Boolean = false,
    sourceLocation: GenericNoteData? = null,
) = TextNoteEntity(
    uid = uid,
    content = unreadableContent ?: document.encode(),
    contentFormat = "handwriting",
    created = creationTimestamp,
    lastUpdated = lastUpdated,
    syncVersion = syncVersion,
    latitude = location?.coordinates?.latitude,
    longitude = location?.coordinates?.longitude,
    altitude = location?.coordinates?.altitude,
    locationAccuracy = location?.coordinates?.accuracy,
    placeId = linkedPlaceId,
    locationMetadata = if (sourceLocation != null) sourceLocation.locationMetadata else location?.storedLocation(preserveSourcePlace),
    timeZoneId = timeZoneId,
    capturedOn = capturedOn?.wireName,
    capturedDuring = capturedDuring?.wireName,
)

fun ImageNoteEntity.toModel(
    place: NotePlace? = null,
    caption: String = "",
) = JournalNote.Image(
    uid = uid,
    mediaRef = contentUri,
    caption = caption,
    presentation = PhotoPresentation.entries.firstOrNull { it.name == presentation } ?: PhotoPresentation.EdgeToEdge,
    creationTimestamp = created,
    lastUpdated = lastUpdated,
    syncVersion = syncVersion,
    location = mapLocation(latitude, longitude, altitude, locationAccuracy, place, placeId, locationMetadata),
    timeZoneId = timeZoneId,
    capturedOn = CaptureDevice.fromWireName(capturedOn),
    capturedDuring = CaptureActivity.fromWireName(capturedDuring),
)

fun JournalNote.Image.toEntity(
    linkedPlaceId: Uuid? = null,
    preserveSourcePlace: Boolean = false,
    sourceLocation: GenericNoteData? = null,
) = ImageNoteEntity(
    uid = uid,
    contentUri = mediaRef,
    presentation = presentation.name,
    created = creationTimestamp,
    lastUpdated = lastUpdated,
    syncVersion = syncVersion,
    latitude = location?.coordinates?.latitude,
    longitude = location?.coordinates?.longitude,
    altitude = location?.coordinates?.altitude,
    locationAccuracy = location?.coordinates?.accuracy,
    placeId = linkedPlaceId,
    locationMetadata = if (sourceLocation != null) sourceLocation.locationMetadata else location?.storedLocation(preserveSourcePlace),
    timeZoneId = timeZoneId,
    capturedOn = capturedOn?.wireName,
    capturedDuring = capturedDuring?.wireName,
)

fun VideoNoteEntity.toModel(
    place: NotePlace? = null,
    caption: String = "",
) = JournalNote.Video(
    uid = uid,
    mediaRef = contentUri,
    caption = caption,
    durationMs = durationMs,
    creationTimestamp = created,
    lastUpdated = lastUpdated,
    syncVersion = syncVersion,
    location = mapLocation(latitude, longitude, altitude, locationAccuracy, place, placeId, locationMetadata),
    timeZoneId = timeZoneId,
    capturedOn = CaptureDevice.fromWireName(capturedOn),
    capturedDuring = CaptureActivity.fromWireName(capturedDuring),
)

fun JournalNote.Video.toEntity(
    linkedPlaceId: Uuid? = null,
    preserveSourcePlace: Boolean = false,
    sourceLocation: GenericNoteData? = null,
) = VideoNoteEntity(
    uid = uid,
    contentUri = mediaRef,
    durationMs = durationMs,
    created = creationTimestamp,
    lastUpdated = lastUpdated,
    syncVersion = syncVersion,
    latitude = location?.coordinates?.latitude,
    longitude = location?.coordinates?.longitude,
    altitude = location?.coordinates?.altitude,
    locationAccuracy = location?.coordinates?.accuracy,
    placeId = linkedPlaceId,
    locationMetadata = if (sourceLocation != null) sourceLocation.locationMetadata else location?.storedLocation(preserveSourcePlace),
    timeZoneId = timeZoneId,
    capturedOn = capturedOn?.wireName,
    capturedDuring = capturedDuring?.wireName,
)

fun AudioNoteEntity.toModel(
    place: NotePlace? = null,
    caption: String = "",
): JournalNote.Audio =
    JournalNote.Audio(
        uid = uid,
        mediaRef = contentUri,
        durationMs = durationMs,
        transcription = transcription,
        creationTimestamp = created,
        lastUpdated = lastUpdated,
        syncVersion = syncVersion,
        location = mapLocation(latitude, longitude, altitude, locationAccuracy, place, placeId, locationMetadata),
        timeZoneId = timeZoneId,
        capturedOn = CaptureDevice.fromWireName(capturedOn),
        capturedDuring = CaptureActivity.fromWireName(capturedDuring),
        caption = caption,
    )

fun JournalNote.Audio.toEntity(
    linkedPlaceId: Uuid? = null,
    preserveSourcePlace: Boolean = false,
    sourceLocation: GenericNoteData? = null,
) = AudioNoteEntity(
    uid = uid,
    contentUri = mediaRef,
    created = creationTimestamp,
    lastUpdated = lastUpdated,
    syncVersion = syncVersion,
    durationMs = durationMs,
    transcription = transcription,
    latitude = location?.coordinates?.latitude,
    longitude = location?.coordinates?.longitude,
    altitude = location?.coordinates?.altitude,
    locationAccuracy = location?.coordinates?.accuracy,
    placeId = linkedPlaceId,
    locationMetadata = if (sourceLocation != null) sourceLocation.locationMetadata else location?.storedLocation(preserveSourcePlace),
    timeZoneId = timeZoneId,
    capturedOn = capturedOn?.wireName,
    capturedDuring = capturedDuring?.wireName,
)

internal fun PlaceEntity.toNotePlace() =
    NotePlace(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
    )
