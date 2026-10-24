package app.logdate.client.domain.export

import app.logdate.client.device.AppInfo
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.util.runCatchingUnlessCancelled
import app.logdate.shared.model.profile.LogDateProfile
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Use case for exporting all user data to JSON format according to the LogDate export specification.
 */
class ExportUserDataUseCase(
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val profileRepository: ProfileRepository,
    private val userPlacesRepository: UserPlacesRepository,
    private val locationHistoryRepository: LocationHistoryRepository,
    private val userStateRepository: UserStateRepository,
    private val deviceIdProvider: DeviceIdProvider,
    private val appInfoProvider: AppInfoProvider,
) {
    private val json =
        Json {
            // Compact (not pretty-printed): a machine-readable export doesn't need
            // whitespace, and pretty-printing multiplied the in-memory size of large
            // categories (location history) enough to exhaust the heap.
            prettyPrint = false
            encodeDefaults = true
        }

    /**
     * Exports user data as a Flow that emits progress updates.
     *
     * @param includeJournals Whether to include journals in the export
     * @param includeNotes Whether to include notes in the export
     * @param includeDrafts Whether to include drafts in the export
     * @param includeMedia Whether to include media files in the export
     * @param dateRangeCutoff Optional cutoff instant — notes and drafts created before this are excluded.
     *                        Journals are always fully included when [includeJournals] is true.
     * @return Flow of ExportProgress containing current status and completion percentage
     */
    fun exportUserData(
        includeJournals: Boolean = true,
        includeNotes: Boolean = true,
        includeDrafts: Boolean = true,
        includeMedia: Boolean = true,
        dateRangeCutoff: Instant? = null,
    ): Flow<ExportProgress> =
        flow {
            emit(ExportProgress.Starting)

            try {
                val issues = mutableListOf<ExportIssue>()

                emit(ExportProgress.InProgress(0.1f, ExportStage.COLLECTING_JOURNALS))
                val journals =
                    if (includeJournals) {
                        runCatchingUnlessCancelled { journalRepository.allJournalsObserved.first() }
                            .onFailure { issues.record(ExportIssueCode.JOURNALS_UNAVAILABLE, it) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                emit(ExportProgress.InProgress(0.3f, ExportStage.COLLECTING_NOTES))
                val notes =
                    if (includeNotes) {
                        val allNotes =
                            runCatchingUnlessCancelled { journalNotesRepository.allNotesObserved.first() }
                                .onFailure { issues.record(ExportIssueCode.NOTES_UNAVAILABLE, it) }
                                .getOrDefault(emptyList())
                        if (dateRangeCutoff != null) {
                            allNotes.filter { it.creationTimestamp >= dateRangeCutoff }
                        } else {
                            allNotes
                        }
                    } else {
                        emptyList()
                    }

                emit(ExportProgress.InProgress(0.5f, ExportStage.COLLECTING_DRAFTS))
                val drafts =
                    if (includeDrafts) {
                        val allDrafts =
                            runCatchingUnlessCancelled { journalRepository.getAllDrafts() }
                                .onFailure { issues.record(ExportIssueCode.DRAFTS_UNAVAILABLE, it) }
                                .getOrDefault(emptyList())
                        if (dateRangeCutoff != null) {
                            allDrafts.filter { it.createdAt >= dateRangeCutoff }
                        } else {
                            allDrafts
                        }
                    } else {
                        emptyList()
                    }

                val appInfo =
                    runCatchingUnlessCancelled { appInfoProvider.getAppInfo() }
                        .onFailure { issues.record(ExportIssueCode.APP_INFO_UNAVAILABLE, it) }
                        .getOrDefault(
                            AppInfo(
                                versionName = "unknown",
                                versionCode = 0,
                                packageName = "unknown",
                            ),
                        )
                val deviceId =
                    runCatchingUnlessCancelled { deviceIdProvider.getDeviceId().value.toString() }
                        .onFailure { issues.record(ExportIssueCode.DEVICE_ID_UNAVAILABLE, it) }
                        .getOrDefault("unknown-device")
                val userId =
                    runCatchingUnlessCancelled {
                        userStateRepository
                            .userData
                            .first()
                            .displayName
                            .trim()
                            .ifBlank { "local-user" }
                    }.onFailure { issues.record(ExportIssueCode.USER_ID_UNAVAILABLE, it) }
                        .getOrDefault("local-user")
                val profile =
                    runCatchingUnlessCancelled { profileRepository.getCurrentProfile() }
                        .onFailure { issues.record(ExportIssueCode.PROFILE_UNAVAILABLE, it) }
                        .getOrDefault(LogDateProfile())
                val places =
                    runCatchingUnlessCancelled { userPlacesRepository.getAllPlaces() }
                        .onFailure { issues.record(ExportIssueCode.PLACES_UNAVAILABLE, it) }
                        .getOrDefault(emptyList())
                val locationHistory =
                    runCatchingUnlessCancelled {
                        locationHistoryRepository
                            .getAllLocationHistory()
                            .filter { item -> dateRangeCutoff == null || item.timestamp >= dateRangeCutoff }
                    }.onFailure { issues.record(ExportIssueCode.LOCATION_HISTORY_UNAVAILABLE, it) }
                        .getOrDefault(emptyList())

                emit(ExportProgress.InProgress(0.7f, ExportStage.PREPARING_DATA))

                val exportNotes =
                    notes.map { note ->
                        when (note) {
                            is JournalNote.Handwriting -> {
                                ExportNote(
                                    id = note.uid.toString(),
                                    type = "handwriting",
                                    handwriting = note.document,
                                    unreadableHandwriting = note.unreadableContent,
                                    createdAt = note.creationTimestamp,
                                    updatedAt = note.lastUpdated,
                                    location = note.location?.toExportLocation(),
                                    syncVersion = note.syncVersion,
                                    capturedOn = note.capturedOn?.wireName,
                                    capturedDuring = note.capturedDuring?.wireName,
                                    timeZone = note.timeZoneId,
                                )
                            }

                            is JournalNote.Text -> {
                                ExportNote(
                                    id = note.uid.toString(),
                                    type = "text",
                                    content = note.content,
                                    createdAt = note.creationTimestamp,
                                    updatedAt = note.lastUpdated,
                                    location = note.location?.toExportLocation(),
                                    syncVersion = note.syncVersion,
                                    capturedOn = note.capturedOn?.wireName,
                                    capturedDuring = note.capturedDuring?.wireName,
                                )
                            }

                            is JournalNote.Image -> {
                                ExportNote(
                                    id = note.uid.toString(),
                                    type = "image",
                                    mediaPath = note.mediaRef,
                                    caption = note.caption.takeIf { it.isNotEmpty() },
                                    photoPresentation = note.presentation.name,
                                    createdAt = note.creationTimestamp,
                                    updatedAt = note.lastUpdated,
                                    location = note.location?.toExportLocation(),
                                    syncVersion = note.syncVersion,
                                    capturedOn = note.capturedOn?.wireName,
                                    capturedDuring = note.capturedDuring?.wireName,
                                )
                            }

                            is JournalNote.Audio -> {
                                ExportNote(
                                    id = note.uid.toString(),
                                    type = "audio",
                                    mediaPath = note.mediaRef,
                                    caption = note.caption.takeIf { it.isNotEmpty() },
                                    durationMs = note.durationMs,
                                    transcript = note.transcript,
                                    createdAt = note.creationTimestamp,
                                    updatedAt = note.lastUpdated,
                                    location = note.location?.toExportLocation(),
                                    syncVersion = note.syncVersion,
                                    capturedOn = note.capturedOn?.wireName,
                                    capturedDuring = note.capturedDuring?.wireName,
                                )
                            }

                            is JournalNote.Video -> {
                                ExportNote(
                                    id = note.uid.toString(),
                                    type = "video",
                                    mediaPath = note.mediaRef,
                                    caption = note.caption.takeIf { it.isNotEmpty() },
                                    createdAt = note.creationTimestamp,
                                    updatedAt = note.lastUpdated,
                                    location = note.location?.toExportLocation(),
                                    syncVersion = note.syncVersion,
                                    capturedOn = note.capturedOn?.wireName,
                                    capturedDuring = note.capturedDuring?.wireName,
                                )
                            }
                        }
                    }

                val exportDrafts = drafts.map { it.toExportDraft() }
                val exportPlaces = places.mapNotNull { it.toExportPlaceOrNull() }
                val exportLocationHistory = locationHistory.map { it.toExportLocationHistoryItem() }
                val exportRelations =
                    if (includeJournals && includeNotes) {
                        val journalIds = journals.map { it.id }.toSet()
                        val notesByUid = notes.associateBy { it.uid }
                        runCatchingUnlessCancelled {
                            journalNotesRepository
                                .getAllJournalNoteLinks()
                                .filter { (journalId, noteId) ->
                                    journalId in journalIds && noteId in notesByUid
                                }.mapNotNull { (journalId, noteId) ->
                                    val note = notesByUid[noteId] ?: return@mapNotNull null
                                    ExportJournalNoteRelation(
                                        journalId = journalId.toString(),
                                        noteId = noteId.toString(),
                                        addedAt = note.creationTimestamp,
                                        syncVersion = note.syncVersion,
                                    )
                                }
                        }.onFailure { issues.record(ExportIssueCode.ASSOCIATIONS_UNAVAILABLE, it) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                val mediaFiles =
                    if (includeMedia) {
                        runCatchingUnlessCancelled { getMediaFilesToExport(exportNotes, exportDrafts) }
                            .onFailure { issues.record(ExportIssueCode.MEDIA_MANIFEST_UNAVAILABLE, it) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }

                val stats =
                    ExportStats(
                        journalCount = journals.size,
                        noteCount = notes.size,
                        draftCount = exportDrafts.size,
                        mediaCount = mediaFiles.size,
                        placeCount = exportPlaces.size,
                        locationHistoryCount = exportLocationHistory.size,
                        hasProfile = profile != LogDateProfile(),
                    )

                val exportMetadata =
                    ExportMetadata(
                        version = ExportSchemaVersion.V1_2,
                        exportDate = Clock.System.now(),
                        userId = userId,
                        deviceId = deviceId,
                        appVersion = appInfo.versionName,
                        stats = stats,
                    )

                emit(
                    ExportProgress.Completed(
                        ExportResult(
                            json = json,
                            exportMetadata = exportMetadata,
                            journals = journals,
                            exportNotes = exportNotes,
                            exportRelations = exportRelations,
                            exportDrafts = exportDrafts,
                            profilePayload =
                                profile
                                    .takeIf { it != LogDateProfile() }
                                    ?.let { ProfilePayload(profile = it) },
                            placesPayload =
                                exportPlaces
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { PlacesPayload(places = it) },
                            locationHistoryPayload =
                                exportLocationHistory
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { LocationHistoryPayload(locationHistory = it) },
                            mediaFiles = mediaFiles,
                            stats = stats,
                            issues = issues.toList(),
                        ),
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                Napier.e("Export failed", exception)
                emit(ExportProgress.Failed(ExportError.UNKNOWN))
            }
        }
}
