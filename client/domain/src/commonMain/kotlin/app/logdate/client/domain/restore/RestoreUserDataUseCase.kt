package app.logdate.client.domain.restore

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.domain.export.ExportLocationHistoryItem
import app.logdate.client.domain.export.ExportMediaManifest
import app.logdate.client.domain.export.ExportMetadata
import app.logdate.client.domain.export.ExportNote
import app.logdate.client.domain.export.ExportPlace
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.domain.export.LocationHistoryPayload
import app.logdate.client.domain.export.PlacesPayload
import app.logdate.client.domain.export.ProfilePayload
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.sync.SyncTransactionManager
import io.github.aakira.napier.Napier
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class RestoreUserDataUseCase(
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val journalContentRepository: JournalContentRepository,
    private val profileRepository: ProfileRepository,
    private val userPlacesRepository: UserPlacesRepository,
    private val locationHistoryRepository: LocationHistoryRepository,
    private val canonicalOwnerProvider: CanonicalOwnerProvider,
    private val deviceIdProvider: DeviceIdProvider,
    private val transactions: SyncTransactionManager = DirectRestoreTransactions,
) {
    private val migrationRunner = ExportMigrationRunner(exportMigrations)
    private val profileRestorer = ProfileRestorer(profileRepository)
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun restore(
        bundle: V2RestoreBundle,
        options: RestoreOptions = RestoreOptions(),
        mediaImporter: MediaImporter? = null,
        onProgress: (suspend (RestoreProgressPhase) -> Unit)? = null,
        onPendingMedia: ((UnavailableMediaAsset) -> Unit)? = null,
        mediaRecoveryId: String = Uuid.random().toString(),
        expectedNoteScope: app.logdate.client.repository.journals.JournalMergeScope? = null,
    ): RestoreResult {
        val adapted = bundle.adaptForRestore(canonicalOwnerProvider.getCanonicalOwnerId(), deviceIdProvider.getDeviceId().value.toString())
        val restored = restore(adapted.legacy, options, mediaImporter, onProgress, onPendingMedia, mediaRecoveryId, expectedNoteScope)
        return restored.copy(
            metadata = adapted.metadata,
            warnings = adapted.warnings + restored.warnings,
        )
    }

    suspend fun restore(
        archive: RestoreArchiveBundle,
        options: RestoreOptions = RestoreOptions(),
        mediaImporter: MediaImporter? = null,
        onProgress: (suspend (RestoreProgressPhase) -> Unit)? = null,
        onPendingMedia: ((UnavailableMediaAsset) -> Unit)? = null,
        mediaRecoveryId: String = Uuid.random().toString(),
        expectedNoteScope: app.logdate.client.repository.journals.JournalMergeScope? = null,
    ): RestoreResult =
        when (archive) {
            is RestoreArchiveBundle.V1 -> {
                restore(
                    archive.bundle,
                    options,
                    mediaImporter,
                    onProgress,
                    onPendingMedia,
                    mediaRecoveryId,
                    expectedNoteScope,
                )
            }

            is RestoreArchiveBundle.V2 -> {
                restore(
                    archive.bundle,
                    options,
                    mediaImporter,
                    onProgress,
                    onPendingMedia,
                    mediaRecoveryId,
                    expectedNoteScope,
                )
            }
        }

    suspend fun restore(
        bundle: RestoreBundle,
        options: RestoreOptions = RestoreOptions(),
        mediaImporter: MediaImporter? = null,
        onProgress: (suspend (RestoreProgressPhase) -> Unit)? = null,
        onPendingMedia: ((UnavailableMediaAsset) -> Unit)? = null,
        mediaRecoveryId: String = Uuid.random().toString(),
        expectedNoteScope: app.logdate.client.repository.journals.JournalMergeScope? = null,
    ): RestoreResult {
        val noteRecoveryScope = journalNotesRepository.getNoteRecoveryScope()
        val journalRecoveryScope = journalRepository.getJournalRecoveryScope()
        check(expectedNoteScope == null || (noteRecoveryScope == expectedNoteScope && journalRecoveryScope == expectedNoteScope)) {
            "Archive recovery account changed"
        }
        val metadata = json.decodeFromString<ExportMetadata>(bundle.metadataJson)

        if (metadata.version.major != ExportSchemaVersion.V1_0.major || metadata.version > ExportSchemaVersion.V1_2) {
            throw UnsupportedExportVersionException(metadata.version)
        }

        val journalsPayload = json.decodeFromString<JournalsPayload>(bundle.journalsJson)
        val notesPayload = json.decodeFromString<NotesPayload>(bundle.notesJson)
        notesPayload.notes.forEach { it.locationDetails?.validateForRestore() }
        val draftsPayload = json.decodeFromString<DraftsPayload>(bundle.draftsJson)
        val journalNotesPayload = json.decodeFromString<JournalNotesPayload>(bundle.journalNotesJson)
        val profilePayload = bundle.profileJson?.let { json.decodeFromString<ProfilePayload>(it) }
        val placesPayload = bundle.placesJson?.let { json.decodeFromString<PlacesPayload>(it) }
        val locationHistoryPayload = bundle.locationHistoryJson?.let { json.decodeFromString<LocationHistoryPayload>(it) }
        val manifest = bundle.mediaManifestJson?.let { json.decodeFromString<ExportMediaManifest>(it) }
        val manifestIndex = manifest?.files?.associateBy { it.sourceUri }

        val migrated =
            migrationRunner.run(
                sourceVersion = metadata.version,
                bundle =
                    ParsedExportBundle(
                        journals = journalsPayload.journals,
                        notes = notesPayload.notes,
                        drafts = draftsPayload.drafts,
                    ),
            )

        var journalsImported = 0
        var notesImported = 0
        var draftsImported = 0
        var linksImported = 0
        var mediaImported = 0
        val warnings = mutableListOf<String>()
        val unavailableMedia = mutableListOf<UnavailableMediaAsset>()
        val mediaResolver = RestoreMediaResolver(manifestIndex, mediaImporter, options.includeMedia)

        fun recordUnavailable(asset: UnavailableMediaAsset) {
            unavailableMedia += asset
            val message =
                if (asset.role == RecoveryMediaRole.THUMBNAIL) {
                    "Some media previews are unavailable. Their entries were kept."
                } else {
                    "Some media originals are unavailable. Their entries were kept."
                }
            if (message !in warnings) warnings += message
        }

        // Verify metadata.json stats match actual archive contents
        val integrityMismatches =
            validateArchiveIntegrity(
                stats = metadata.stats,
                actualJournals = journalsPayload.journals.size,
                actualNotes = notesPayload.notes.size,
                actualDrafts = draftsPayload.drafts.size,
                actualMedia = manifest?.files?.size ?: 0,
                actualPlaces = placesPayload?.places?.size ?: 0,
                actualLocationHistory = locationHistoryPayload?.locationHistory?.size ?: 0,
                hasProfile = profilePayload != null,
            )

        // Restored entries *and their journal links* are written through the ordinary
        // create/update path, not the *FromSync variants. Those exist so data arriving from the server is not echoed straight
        // back, and they leave nothing queued -- an archive restored that way was invisible to
        // sync for ever, however many times Sync Now was pressed. An archive is local data the
        // server has never seen, so it has to be queued like anything else written on the device.
        val draftRestorer =
            RestoreDraftReconciler(
                journalRepository,
                warnings,
                RestoreDraftDecoder(
                    resolveMediaReference = mediaResolver::resolve,
                    recoveryId = mediaRecoveryId,
                ),
                onPendingMedia = { onPendingMedia?.invoke(it) },
                expectedScope = expectedNoteScope ?: noteRecoveryScope,
                recoveryId = mediaRecoveryId,
                onApplied = { decoded ->
                    mediaImported += decoded.mediaImported
                    decoded.unavailableMedia.forEach(::recordUnavailable)
                },
            )

        // Reading existing records and importing media happen before the transaction: media import is
        // file I/O that must not hold the database writer, and batch reads avoid a query per record.
        val existingJournals = journalRepository.getJournalsByIds(migrated.journals.map { it.id }.toSet())
        val parsedNotes = migrated.notes.mapNotNull { note -> parseUuid(note.id, warnings)?.let { note to it } }
        val existingNotes = journalNotesRepository.getNotesByIds(parsedNotes.map { it.second }.toSet())
        val notePlans =
            parsedNotes.map { (note, id) ->
                planNote(note, id, existingNotes[id], mediaResolver, mediaImporter, manifestIndex, mediaRecoveryId)
            }
        val availableNoteIds = mutableSetOf<Uuid>()

        // Every database record is applied in one transaction: a failure or cancellation rolls back
        // the whole archive instead of leaving part of it. Drafts and the profile live outside the
        // database and are applied only after the transaction commits.
        transactions.withTransaction {
            onProgress?.invoke(RestoreProgressPhase.RESTORING_JOURNALS)
            Napier.i("Restore: importing ${migrated.journals.size} journals")
            for (journal in migrated.journals) {
                val existing = existingJournals[journal.id]
                val shouldWrite = shouldOverwrite(existing?.lastUpdated, journal.lastUpdated, options.strategy)
                if (existing == null || shouldWrite) {
                    if (journalRepository.compareAndSetJournalForRestore(existing, journal, journalRecoveryScope, mediaRecoveryId)) {
                        journalsImported++
                    } else {
                        warnings.add("Kept a journal changed during archive recovery")
                    }
                } else {
                    journalRepository.compareAndSetJournalForRestore(existing, existing, journalRecoveryScope, mediaRecoveryId)
                }
            }

            onProgress?.invoke(RestoreProgressPhase.RESTORING_NOTES)
            Napier.i("Restore: importing ${migrated.notes.size} notes")
            for (plan in notePlans) {
                check(journalNotesRepository.getNoteRecoveryScope() == noteRecoveryScope) { "Note recovery account changed" }
                val existing = plan.existing
                if (plan.mediaResolution.imported) {
                    mediaImported++
                }
                val restored = plan.restored
                if (restored == null) {
                    Napier.w(plan.skipMessage)
                    warnings.add(plan.skipMessage)
                    if (existing != null) availableNoteIds += plan.id
                    continue
                }

                availableNoteIds += plan.id
                val shouldWrite = shouldOverwrite(existing?.lastUpdated, restored.lastUpdated, options.strategy)
                if (existing == null || shouldWrite) {
                    val replacement = restored.withExistingCaptureMetadata(existing)
                    val unavailable = plan.mediaResolution.unavailableAsset(plan.note.id, generation = mediaRecoveryId, kind = plan.kind)
                    unavailable?.let { onPendingMedia?.invoke(it) }
                    if (journalNotesRepository.compareAndSetNoteForRestore(existing, replacement, noteRecoveryScope, mediaRecoveryId)) {
                        unavailable?.let(::recordUnavailable)
                        notesImported++
                    } else {
                        warnings.add("Kept a note changed during archive recovery")
                    }
                } else {
                    journalNotesRepository.compareAndSetNoteForRestore(existing, existing, noteRecoveryScope, mediaRecoveryId)
                }
            }

            onProgress?.invoke(RestoreProgressPhase.RESTORING_LINKS)
            Napier.i("Restore: importing ${journalNotesPayload.journalNotes.size} journal links")
            val relations =
                journalNotesPayload.journalNotes.mapNotNull { relation ->
                    val journalId = parseUuid(relation.journalId, warnings) ?: return@mapNotNull null
                    val noteId = parseUuid(relation.noteId, warnings) ?: return@mapNotNull null
                    if (noteId !in availableNoteIds) {
                        val message = "Skipped journal link for note $noteId because the note was not restored"
                        Napier.w(message)
                        warnings.add(message)
                        return@mapNotNull null
                    }
                    noteId to journalId
                }
            val linkedNotes = journalNotesRepository.getNotesByIds(relations.map { it.first }.toSet()).keys
            for ((noteId, journalId) in relations) {
                if (noteId in linkedNotes &&
                    journalContentRepository.addContentToJournalForRestore(noteId, journalId, noteRecoveryScope, mediaRecoveryId)
                ) {
                    linksImported++
                }
            }

            restorePlaces(placesPayload?.places.orEmpty(), noteRecoveryScope, mediaRecoveryId, onProgress)
            restoreLocationHistory(locationHistoryPayload?.locationHistory.orEmpty(), noteRecoveryScope, mediaRecoveryId, onProgress)
        }

        if (options.includeDrafts) {
            onProgress?.invoke(RestoreProgressPhase.RESTORING_DRAFTS)
            Napier.i("Restore: importing ${migrated.drafts.size} drafts")
            draftsImported = draftRestorer.restore(migrated.drafts, options.strategy)
        }

        profileRestorer.restore(profilePayload?.profile, options, onProgress, noteRecoveryScope, mediaRecoveryId)

        onProgress?.invoke(RestoreProgressPhase.COMPLETED)

        return RestoreResult(
            metadata = metadata,
            journalsImported = journalsImported,
            notesImported = notesImported,
            draftsImported = draftsImported,
            journalLinksImported = linksImported,
            mediaImported = mediaImported,
            warnings = warnings,
            integrityMismatches = integrityMismatches,
            unavailableMedia = unavailableMedia.distinctBy { Triple(it.entityId, it.blockId, it.role) },
        )
    }

    /** What restoring one archived note will write, resolved before the database transaction. */
    private class NotePlan(
        val note: ExportNote,
        val id: Uuid,
        val existing: JournalNote?,
        val mediaResolution: MediaResolution,
        val restored: JournalNote?,
        val skipMessage: String,
    ) {
        val kind: String = note.type.lowercase()
    }

    private suspend fun planNote(
        note: ExportNote,
        id: Uuid,
        existing: JournalNote?,
        mediaResolver: RestoreMediaResolver,
        mediaImporter: MediaImporter?,
        manifestIndex: Map<String, *>?,
        mediaRecoveryId: String,
    ): NotePlan {
        val kind = note.type.lowercase()
        val isMedia = kind in setOf("image", "video", "audio")
        val source = note.mediaPath?.takeIf { it.isNotBlank() } ?: if (isMedia) unavailableArchiveReference(note.id) else null
        var mediaResolution = mediaResolver.resolve(source)
        val localOriginal = existing?.mediaRefOrNull()
        if (mediaResolution.unavailableReason != null &&
            existing?.type?.name?.lowercase() == kind &&
            localOriginal != null &&
            mediaImporter?.isAvailable(localOriginal) == true
        ) {
            mediaResolution = MediaResolution(localOriginal, false, false)
        }
        val restored =
            note.toJournalNote(
                id,
                mediaResolution.copy(uri = mediaResolution.retainedReference(note.id, generation = mediaRecoveryId)),
            )
        val skipMessage =
            if (isMedia) {
                val mediaPath = note.mediaPath ?: "unknown"
                val status =
                    if (mediaImporter != null && manifestIndex?.containsKey(mediaPath) == true) {
                        "could not be imported (file missing or corrupted)"
                    } else {
                        "not found in archive"
                    }
                "Skipped $kind note (ID: ${note.id}) - media file $status: $mediaPath"
            } else {
                "Skipped unsupported note type: ${note.type}"
            }
        return NotePlan(note, id, existing, mediaResolution, restored, skipMessage)
    }

    private fun validateArchiveIntegrity(
        stats: ExportStats,
        actualJournals: Int,
        actualNotes: Int,
        actualDrafts: Int,
        actualMedia: Int,
        actualPlaces: Int,
        actualLocationHistory: Int,
        hasProfile: Boolean,
    ): List<IntegrityMismatch> =
        buildList {
            if (stats.journalCount != actualJournals) {
                add(IntegrityMismatch(IntegrityCategory.JOURNALS, stats.journalCount, actualJournals))
            }
            if (stats.noteCount != actualNotes) {
                add(IntegrityMismatch(IntegrityCategory.NOTES, stats.noteCount, actualNotes))
            }
            if (stats.draftCount != actualDrafts) {
                add(IntegrityMismatch(IntegrityCategory.DRAFTS, stats.draftCount, actualDrafts))
            }
            if (stats.mediaCount != actualMedia) {
                add(IntegrityMismatch(IntegrityCategory.MEDIA, stats.mediaCount, actualMedia))
            }
            if (stats.placeCount != actualPlaces) {
                add(IntegrityMismatch(IntegrityCategory.PLACES, stats.placeCount, actualPlaces))
            }
            if (stats.locationHistoryCount != actualLocationHistory) {
                add(IntegrityMismatch(IntegrityCategory.LOCATION_HISTORY, stats.locationHistoryCount, actualLocationHistory))
            }
            if (stats.hasProfile != hasProfile) {
                add(IntegrityMismatch(IntegrityCategory.PROFILE, if (stats.hasProfile) 1 else 0, if (hasProfile) 1 else 0))
            }
        }

    private suspend fun restorePlaces(
        places: List<ExportPlace>,
        scope: app.logdate.client.repository.journals.JournalMergeScope?,
        recoveryId: String,
        onProgress: (suspend (RestoreProgressPhase) -> Unit)?,
    ) {
        if (places.isEmpty()) {
            return
        }

        onProgress?.invoke(RestoreProgressPhase.RESTORING_PLACES)
        Napier.i("Restore: importing ${places.size} places")
        for (place in places) {
            userPlacesRepository.restorePlaceForArchive(place.toPlace(), scope, recoveryId).getOrThrow()
        }
    }

    private suspend fun restoreLocationHistory(
        entries: List<ExportLocationHistoryItem>,
        scope: app.logdate.client.repository.journals.JournalMergeScope?,
        recoveryId: String,
        onProgress: (suspend (RestoreProgressPhase) -> Unit)?,
    ) {
        if (entries.isEmpty()) {
            return
        }

        onProgress?.invoke(RestoreProgressPhase.RESTORING_LOCATION_HISTORY)
        Napier.i("Restore: importing ${entries.size} location history samples")
        val seenSampleIds = mutableSetOf<String>()
        for (entry in entries) {
            if (!seenSampleIds.add(entry.sampleId)) {
                continue
            }
            locationHistoryRepository.restoreLocationForArchive(entry.toLocationLogRecord(), scope, recoveryId).getOrThrow()
        }
    }

    private fun parseUuid(
        value: String,
        warnings: MutableList<String>,
    ): Uuid? =
        runCatching { Uuid.parse(value) }
            .onFailure { warnings.add("Invalid UUID in restore payload: $value") }
            .getOrNull()
}

/** Applies restore writes directly, for callers without a database transaction. */
private object DirectRestoreTransactions : SyncTransactionManager {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
}
