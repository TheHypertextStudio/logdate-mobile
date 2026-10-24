package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportDraft
import app.logdate.client.domain.export.ExportJournalNoteRelation
import app.logdate.client.domain.export.ExportLocation
import app.logdate.client.domain.export.ExportLocationHistoryItem
import app.logdate.client.domain.export.ExportMediaFile
import app.logdate.client.domain.export.ExportMediaManifest
import app.logdate.client.domain.export.ExportMetadata
import app.logdate.client.domain.export.ExportNote
import app.logdate.client.domain.export.ExportPlace
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.domain.export.LocationHistoryPayload
import app.logdate.client.domain.export.PlacesPayload
import app.logdate.client.domain.export.ProfilePayload
import app.logdate.client.domain.export.archive.ArchiveBlockType
import app.logdate.client.domain.export.archive.ArchiveDraftBlock
import app.logdate.client.domain.export.archive.ArchiveDraftFile
import app.logdate.client.domain.export.archive.ArchiveJournalFile
import app.logdate.client.domain.export.archive.ArchiveJson
import app.logdate.client.domain.export.archive.ArchiveLocation
import app.logdate.client.domain.export.archive.ArchiveLocationSample
import app.logdate.client.domain.export.archive.ArchiveManifest
import app.logdate.client.domain.export.archive.ArchiveMediaFile
import app.logdate.client.domain.export.archive.ArchiveMediaRef
import app.logdate.client.domain.export.archive.ArchiveMediaStatus
import app.logdate.client.domain.export.archive.ArchiveNoteFile
import app.logdate.client.domain.export.archive.ArchiveNoteType
import app.logdate.client.domain.export.archive.ArchivePlaceFile
import app.logdate.client.domain.export.archive.ArchiveProfileFile
import app.logdate.shared.model.Journal
import app.logdate.shared.model.PhotoPresentation
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableEntryBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The files from a 2.x archive after the platform ZIP reader resolves their manifest roles. */
data class V2RestoreBundle(
    val manifestJson: String,
    val journalsJson: String? = null,
    val notesJson: String? = null,
    val draftsJson: String? = null,
    val profileJson: String? = null,
    val placesJson: String? = null,
    val locationHistoryJsonLines: String? = null,
    val mediaInventoryJson: String? = null,
    val mediaIntegrity: Map<String, ArchiveMediaIntegrity>? = null,
)

internal data class AdaptedV2RestoreBundle(
    val legacy: RestoreBundle,
    val metadata: ExportMetadata,
    val warnings: List<String>,
)

/**
 * Converts the portable 2.x records into the canonical input already applied by restore.
 *
 * Archive location samples carry no owner or device, so they are attributed to [ownerId] and [deviceId],
 * the identity of the installation restoring them. Location history is only shown for that identity.
 */
internal fun V2RestoreBundle.adaptForRestore(
    ownerId: String,
    deviceId: String,
): AdaptedV2RestoreBundle {
    val manifest = decodeManifest()
    val metadata = buildExportMetadata(manifest)

    val journals = decodeAndMapJournals()
    val (exportNotes, journalNoteRelations) = decodeAndMapNotes()
    val exportDrafts = decodeAndMapDrafts()
    val exportPlaces = decodeAndMapPlaces()
    val exportLocationHistory = decodeAndMapLocationHistory(ownerId, deviceId)
    val mediaManifest = decodeAndMapMediaManifest()
    val exportProfile = decodeAndMapProfile()

    return AdaptedV2RestoreBundle(
        legacy =
            buildLegacyRestoreBundle(
                metadata = metadata,
                journals = journals,
                notes = exportNotes,
                journalNoteRelations = journalNoteRelations,
                drafts = exportDrafts,
                profile = exportProfile,
                places = exportPlaces,
                locationHistory = exportLocationHistory,
                mediaManifest = mediaManifest,
            ),
        metadata = metadata,
        warnings = emptyList(),
    )
}

/** Decodes and validates the archive manifest, rejecting formats/versions restore can't read. */
private fun V2RestoreBundle.decodeManifest(): ArchiveManifest {
    val manifest = ArchiveJson.document.decodeFromString(ArchiveManifest.serializer(), manifestJson)
    require(manifest.format == ArchiveManifest.FORMAT) { "Unsupported archive format: ${manifest.format}" }
    if (manifest.schemaVersion.major != ExportSchemaVersion.V2_0.major) {
        throw UnsupportedExportVersionException(manifest.schemaVersion)
    }
    return manifest
}

private fun buildExportMetadata(manifest: ArchiveManifest): ExportMetadata =
    ExportMetadata(
        version = manifest.schemaVersion,
        exportDate = manifest.exportedAt,
        userId = "",
        deviceId = "",
        appVersion = manifest.generator.version,
        stats =
            ExportStats(
                journalCount = manifest.counts.journals,
                noteCount = manifest.counts.notes,
                draftCount = manifest.counts.drafts,
                mediaCount = manifest.counts.media,
                placeCount = manifest.counts.places,
                locationHistoryCount = manifest.counts.locationSamples,
                hasProfile = manifest.counts.hasProfile,
            ),
    )

private fun V2RestoreBundle.decodeAndMapJournals(): List<Journal> =
    journalsJson
        ?.let { ArchiveJson.document.decodeFromString(ArchiveJournalFile.serializer(), it).journals }
        .orEmpty()
        .map {
            Journal(
                id = Uuid.parse(it.id),
                title = it.title,
                description = it.description,
                created = it.createdAt,
                lastUpdated = it.updatedAt,
                isFavorited = it.favorite,
            )
        }

private fun V2RestoreBundle.decodeAndMapNotes(): Pair<List<ExportNote>, List<ExportJournalNoteRelation>> {
    val notes = notesJson?.let { ArchiveJson.document.decodeFromString(ArchiveNoteFile.serializer(), it).notes }.orEmpty()
    val exportNotes =
        notes.map { note ->
            ExportNote(
                id = note.id,
                type = note.type.name.lowercase(),
                content = note.text,
                handwriting = note.handwriting,
                unreadableHandwriting = note.unreadableHandwriting,
                caption = note.caption,
                photoPresentation = note.photoPresentation,
                mediaPath =
                    note.media?.toRestoreReference(note.id) ?: unavailableArchiveReference(note.id).takeUnless {
                        note.type == ArchiveNoteType.TEXT || note.type == ArchiveNoteType.HANDWRITING
                    },
                durationMs = note.durationMs,
                transcription = note.transcription,
                transcript = note.transcript,
                createdAt = note.createdAt,
                updatedAt = note.updatedAt,
                timeZone = note.timeZone,
                capturedOn = note.capturedOn,
                capturedDuring = note.capturedDuring,
                location = note.location?.toExportLocation(),
                locationDetails = note.locationDetails,
            )
        }
    val relations = notes.flatMap { note -> note.journalIds.map { ExportJournalNoteRelation(it, note.id, note.createdAt) } }
    return exportNotes to relations
}

private fun V2RestoreBundle.decodeAndMapDrafts(): List<ExportDraft> {
    val drafts = draftsJson?.let { ArchiveJson.document.decodeFromString(ArchiveDraftFile.serializer(), it).drafts }.orEmpty()
    return drafts.map { draft ->
        ExportDraft(
            id = draft.id,
            journalIds = draft.journalIds,
            content = "",
            createdAt = draft.createdAt,
            updatedAt = draft.updatedAt,
            blocks = draft.blocks.map { toSerializableBlock(it, draft.id) },
        )
    }
}

private fun V2RestoreBundle.decodeAndMapPlaces(): List<ExportPlace> {
    val places = placesJson?.let { ArchiveJson.document.decodeFromString(ArchivePlaceFile.serializer(), it).places }.orEmpty()
    return places.map { ExportPlace(it.id, it.name, it.latitude, it.longitude, it.radiusMeters, it.description) }
}

private fun V2RestoreBundle.decodeAndMapLocationHistory(
    ownerId: String,
    deviceId: String,
): List<ExportLocationHistoryItem> =
    decodeLocationSamples(locationHistoryJsonLines).map {
        toExportLocationHistoryItem(it, ownerId, deviceId)
    }

private fun V2RestoreBundle.decodeAndMapMediaManifest(): ExportMediaManifest? =
    mediaInventoryJson
        ?.let { ArchiveJson.document.decodeFromString(ArchiveMediaFile.serializer(), it) }
        ?.let { inventory ->
            ExportMediaManifest(inventory.files.map { ExportMediaFile(exportPath = it.path.value, sourceUri = it.path.value) })
        }

private fun V2RestoreBundle.decodeAndMapProfile(): LogDateProfile? =
    profileJson
        ?.let { ArchiveJson.document.decodeFromString(ArchiveProfileFile.serializer(), it).profile }
        ?.let {
            LogDateProfile(
                displayName = it.displayName.orEmpty(),
                birthday = it.birthday,
                bio = it.bio,
                originalBio = it.originalBio,
                createdAt = it.createdAt ?: Instant.DISTANT_PAST,
                lastUpdatedAt = it.updatedAt ?: Instant.DISTANT_PAST,
            )
        }

/** Assembles the legacy [RestoreBundle] payloads that the existing restore pipeline consumes. */
private fun buildLegacyRestoreBundle(
    metadata: ExportMetadata,
    journals: List<Journal>,
    notes: List<ExportNote>,
    journalNoteRelations: List<ExportJournalNoteRelation>,
    drafts: List<ExportDraft>,
    profile: LogDateProfile?,
    places: List<ExportPlace>,
    locationHistory: List<ExportLocationHistoryItem>,
    mediaManifest: ExportMediaManifest?,
): RestoreBundle =
    RestoreBundle(
        metadataJson =
            ArchiveJson.document.encodeToString(
                ExportMetadata.serializer(),
                metadata.copy(version = ExportSchemaVersion.V1_2),
            ),
        journalsJson =
            ArchiveJson.document.encodeToString(LegacyJournalsPayload.serializer(), LegacyJournalsPayload(journals)),
        notesJson = ArchiveJson.document.encodeToString(LegacyNotesPayload.serializer(), LegacyNotesPayload(notes)),
        journalNotesJson =
            ArchiveJson.document.encodeToString(LegacyJournalNotesPayload.serializer(), LegacyJournalNotesPayload(journalNoteRelations)),
        draftsJson = ArchiveJson.document.encodeToString(LegacyDraftsPayload.serializer(), LegacyDraftsPayload(drafts)),
        profileJson = profile?.let { ArchiveJson.document.encodeToString(ProfilePayload.serializer(), ProfilePayload(it)) },
        placesJson =
            places
                .takeIf { it.isNotEmpty() }
                ?.let { ArchiveJson.document.encodeToString(PlacesPayload.serializer(), PlacesPayload(it)) },
        locationHistoryJson =
            locationHistory
                .takeIf { it.isNotEmpty() }
                ?.let { ArchiveJson.document.encodeToString(LocationHistoryPayload.serializer(), LocationHistoryPayload(it)) },
        mediaManifestJson = mediaManifest?.let { ArchiveJson.document.encodeToString(ExportMediaManifest.serializer(), it) },
    )

private fun ArchiveLocation.toExportLocation() = ExportLocation(latitude, longitude, placeName, altitudeMeters, accuracyMeters?.toFloat())

private fun ArchiveDraftBlock.toHandwritingBlock(id: Uuid) =
    app.logdate.shared.model.SerializableHandwritingBlock(
        id,
        timestamp,
        location?.latitude,
        location?.longitude,
        location?.altitudeMeters,
        requireNotNull(handwriting),
    )

private fun toSerializableBlock(
    block: ArchiveDraftBlock,
    draftId: String,
): SerializableEntryBlock {
    val id = Uuid.parse(block.id)
    val location = block.location
    val mediaPath = block.media?.toRestoreReference(draftId, block.id)
    return when (block.type) {
        ArchiveBlockType.HANDWRITING -> {
            block.toHandwritingBlock(id)
        }

        ArchiveBlockType.TEXT -> {
            SerializableTextBlock(
                id,
                block.timestamp,
                location?.latitude,
                location?.longitude,
                location?.altitudeMeters,
                block.text.orEmpty(),
            )
        }

        ArchiveBlockType.IMAGE -> {
            SerializableImageBlock(
                id,
                block.timestamp,
                location?.latitude,
                location?.longitude,
                location?.altitudeMeters,
                mediaPath,
                block.caption.orEmpty(),
                PhotoPresentation.entries.firstOrNull { it.name == block.photoPresentation } ?: PhotoPresentation.EdgeToEdge,
            )
        }

        ArchiveBlockType.VIDEO -> {
            SerializableVideoBlock(
                id,
                block.timestamp,
                location?.latitude,
                location?.longitude,
                location?.altitudeMeters,
                mediaPath,
                thumbnailUri = block.thumbnail?.toRestoreReference(draftId, block.id, RecoveryMediaRole.THUMBNAIL),
                caption = block.caption.orEmpty(),
            )
        }

        ArchiveBlockType.AUDIO -> {
            SerializableAudioBlock(
                id,
                block.timestamp,
                location?.latitude,
                location?.longitude,
                location?.altitudeMeters,
                mediaPath,
                block.durationMs ?: 0,
                block.transcription,
            )
        }

        ArchiveBlockType.CAMERA -> {
            SerializableCameraBlock(id, block.timestamp, location?.latitude, location?.longitude, location?.altitudeMeters, mediaPath)
        }
    }
}

private fun decodeLocationSamples(jsonLines: String?): List<ArchiveLocationSample> =
    jsonLines
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.map { ArchiveJson.line.decodeFromString(ArchiveLocationSample.serializer(), it) }
        ?.toList()
        .orEmpty()

private fun toExportLocationHistoryItem(
    sample: ArchiveLocationSample,
    ownerId: String,
    deviceId: String,
): ExportLocationHistoryItem =
    ExportLocationHistoryItem(
        sampleId = "v2:${sample.timestamp}:${sample.latitude}:${sample.longitude}",
        userId = ownerId,
        deviceId = deviceId,
        timestamp = sample.timestamp,
        loggedAt = sample.loggedAt,
        latitude = sample.latitude,
        longitude = sample.longitude,
        altitudeMeters = sample.altitudeMeters,
        confidence = sample.confidence.toFloat(),
        isGenuine = sample.isGenuine,
        capturePipeline = sample.capturePipeline,
        captureSource = sample.captureSource,
        accuracyMeters = sample.accuracyMeters?.toFloat(),
        speedMetersPerSecond = sample.speedMetersPerSecond?.toFloat(),
        bearingDegrees = sample.bearingDegrees?.toFloat(),
        isMock = sample.isMock,
    )

@Serializable
private data class LegacyJournalsPayload(
    val journals: List<Journal>,
)

@Serializable
private data class LegacyNotesPayload(
    val notes: List<ExportNote>,
)

@Serializable
private data class LegacyDraftsPayload(
    val drafts: List<ExportDraft>,
)

@Serializable
private data class LegacyJournalNotesPayload(
    @SerialName("journal_notes") val journalNotes: List<ExportJournalNoteRelation>,
)

private fun ArchiveMediaRef.toRestoreReference(
    entityId: String,
    blockId: String? = null,
    role: RecoveryMediaRole = RecoveryMediaRole.ORIGINAL,
): String = path?.value?.takeIf { status == ArchiveMediaStatus.INCLUDED } ?: unavailableArchiveReference(entityId, blockId, role)
