package app.logdate.client.domain.export.archive

import app.logdate.client.device.AppInfoProvider
import app.logdate.client.domain.export.ExportStage
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.sync.deviceBlockReason
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.Journal
import app.logdate.shared.model.Place
import app.logdate.shared.model.profile.LogDateProfile
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Everything an archive is built from, read once so the archive describes one moment. */
internal class ArchiveSnapshot(
    val journals: List<Journal>,
    val notes: List<JournalNote>,
    /** The exported journals each exported note appears in. */
    val journalIdsByNote: Map<Uuid, List<Uuid>>,
    val drafts: List<EditorDraft>,
    val profile: LogDateProfile,
    val places: List<Place>,
    val locationHistory: List<LocationHistoryItem>,
    val ownerName: String?,
    val appVersion: String,
    /** Categories that were asked for but could not be read. */
    val unreadable: Set<ArchiveCategory>,
)

internal class ArchiveSnapshotReader(
    private val journalRepository: JournalRepository,
    private val journalNotesRepository: JournalNotesRepository,
    private val profileRepository: ProfileRepository,
    private val userPlacesRepository: UserPlacesRepository,
    private val locationHistoryRepository: LocationHistoryRepository,
    private val userStateRepository: UserStateRepository,
    private val appInfoProvider: AppInfoProvider,
) {
    suspend fun read(
        options: ArchiveExportOptions,
        onStage: suspend (ExportStage, Float) -> Unit,
    ): ArchiveSnapshot {
        val reads = Reads()

        onStage(ExportStage.COLLECTING_JOURNALS, 0.05f)
        val journals =
            if (options.includeJournals) {
                reads.orEmpty(ArchiveCategory.JOURNALS) { journalRepository.allJournalsObserved.first() }
            } else {
                emptyList()
            }

        onStage(ExportStage.COLLECTING_NOTES, 0.15f)
        val notes =
            if (options.includeNotes) {
                reads
                    .orEmpty(ArchiveCategory.NOTES) { journalNotesRepository.allNotesObserved.first() }
                    .filter { options.includes(it.creationTimestamp) }
            } else {
                emptyList()
            }
        val journalIdsByNote = if (journals.isEmpty() || notes.isEmpty()) emptyMap() else readMemberships(reads, journals, notes)

        onStage(ExportStage.COLLECTING_DRAFTS, 0.25f)
        val drafts =
            if (options.includeDrafts) {
                reads
                    .orEmpty(ArchiveCategory.DRAFTS) { journalRepository.getAllDrafts() }
                    .filter { options.includes(it.createdAt) }
            } else {
                emptyList()
            }

        return ArchiveSnapshot(
            journals = journals,
            notes = notes,
            journalIdsByNote = journalIdsByNote,
            drafts = drafts,
            profile = reads.orDefault(ArchiveCategory.PROFILE, LogDateProfile()) { profileRepository.getCurrentProfile() },
            places = reads.orEmpty(ArchiveCategory.PLACES) { userPlacesRepository.getAllPlaces() },
            locationHistory =
                reads.orEmpty(ArchiveCategory.LOCATION_HISTORY) {
                    locationHistoryRepository.getAllLocationHistory().filter { options.includes(it.timestamp) }
                },
            ownerName = readOwnerName(),
            appVersion = readAppVersion(),
            unreadable = reads.unreadable,
        )
    }

    private suspend fun readMemberships(
        reads: Reads,
        journals: List<Journal>,
        notes: List<JournalNote>,
    ): Map<Uuid, List<Uuid>> {
        val journalIds = journals.map { it.id }.toSet()
        val noteIds = notes.map { it.uid }.toSet()
        val links = reads.orEmpty(ArchiveCategory.JOURNALS) { journalNotesRepository.getAllJournalNoteLinks() }
        return links
            .filter { (journalId, noteId) -> journalId in journalIds && noteId in noteIds }
            .groupBy({ it.second }, { it.first })
    }

    private suspend fun readOwnerName(): String? =
        try {
            userStateRepository.userData
                .first()
                .displayName
                .trim()
                .ifBlank { null }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (deviceBlockReason(failure) != null) throw failure
            null
        }

    private fun readAppVersion(): String =
        try {
            appInfoProvider.getAppInfo().versionName
        } catch (failure: Exception) {
            if (failure is CancellationException || deviceBlockReason(failure) != null) throw failure
            "unknown"
        }

    /** Reads that may fail without failing the export: a failed read leaves the category out and says so. */
    private class Reads {
        val unreadable = mutableSetOf<ArchiveCategory>()

        suspend fun <T> orEmpty(
            category: ArchiveCategory,
            read: suspend () -> List<T>,
        ): List<T> = orDefault(category, emptyList(), read)

        suspend fun <T> orDefault(
            category: ArchiveCategory,
            default: T,
            read: suspend () -> T,
        ): T =
            try {
                read()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (deviceBlockReason(failure) != null) throw failure
                Napier.w("Could not read $category for export", failure)
                unreadable += category
                default
            }
    }
}
