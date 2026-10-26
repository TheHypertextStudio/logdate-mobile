package app.logdate.client.domain.export.archive

import app.logdate.client.device.AppInfoProvider
import app.logdate.client.domain.export.ExportError
import app.logdate.client.domain.export.ExportStage
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.sync.deviceBlockReason
import app.logdate.client.util.platformIODispatcher
import app.logdate.shared.model.Place
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import okio.blackholeSink
import okio.use
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * Builds a 2.0 export archive: a self-describing, checksummed copy of the person's data that needs
 * nothing but a file browser to make sense of.
 *
 * Everything is read and every media file is resolved first, so each file has its path and type.
 * The media is then copied into [ArchiveContainer], and only after that are the README, the manifest,
 * the data files and `SHA256SUMS` (last) written. A file that cannot be opened when its turn comes is
 * dropped from the plan and reported as omitted, so the files that describe the archive state exactly
 * what it holds. Nothing the device stored for a file, such as a content URI or a path, is ever written
 * into it.
 *
 * The work runs on [ioDispatcher], because zip writes, hashing and media reads block. Cancellation stops
 * the export where it is; the caller owns cleaning up a partly written container.
 */
class ExportArchiveUseCase(
    journalRepository: JournalRepository,
    journalNotesRepository: JournalNotesRepository,
    profileRepository: ProfileRepository,
    userPlacesRepository: UserPlacesRepository,
    locationHistoryRepository: LocationHistoryRepository,
    userStateRepository: UserStateRepository,
    appInfoProvider: AppInfoProvider,
    private val mediaSourceOpener: MediaSourceOpener,
    private val clock: Clock = Clock.System,
    private val exportZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val ioDispatcher: CoroutineDispatcher = platformIODispatcher,
) {
    private val reader =
        ArchiveSnapshotReader(
            journalRepository,
            journalNotesRepository,
            profileRepository,
            userPlacesRepository,
            locationHistoryRepository,
            userStateRepository,
            appInfoProvider,
        )

    fun export(
        options: ArchiveExportOptions,
        container: ArchiveContainer,
    ): Flow<ArchiveExportProgress> =
        flow {
            emit(ArchiveExportProgress.Starting)
            try {
                emit(ArchiveExportProgress.Completed(build(options, container)))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (deviceBlockReason(failure) != null) throw failure
                Napier.e("Archive export failed", failure)
                emit(ArchiveExportProgress.Failed(ExportError.UNKNOWN))
            }
        }.flowOn(ioDispatcher)

    /** Reads and hashes current exportable content without allocating a second archive. */
    suspend fun sourceFingerprint(options: ArchiveExportOptions = ArchiveExportOptions()): String? {
        val discarded =
            object : ArchiveContainer {
                override fun entry(
                    path: ArchivePath,
                    compress: Boolean,
                    write: (okio.Sink) -> Unit,
                ) {
                    write(blackholeSink())
                }
            }
        val completed =
            export(options, discarded).firstOrNull {
                it is ArchiveExportProgress.Completed || it is ArchiveExportProgress.Failed
            } as? ArchiveExportProgress.Completed ?: return null
        if (completed.summary.scope.omitted
                .any { it.reason == ArchiveOmissionReason.UNREADABLE }
        ) {
            return null
        }
        return completed.summary.sourceFingerprint
    }

    private suspend fun FlowCollector<ArchiveExportProgress>.build(
        options: ArchiveExportOptions,
        container: ArchiveContainer,
    ): ArchiveExportSummary {
        val exportedAt = clock.now()
        val zone = exportZone()

        val snapshot = reader.read(options) { stage, fraction -> emit(ArchiveExportProgress.InProgress(fraction, stage)) }
        emit(ArchiveExportProgress.InProgress(0.3f, ExportStage.PREPARING_DATA))
        val planned =
            if (options.includeMedia) {
                MediaResolver(
                    mediaSourceOpener,
                    MediaFileNamer(ArchivePathAllocator()),
                ).resolve(snapshot.mediaRequests(zone))
            } else {
                null
            }

        val writer = ArchiveWriter(container)
        emit(ArchiveExportProgress.InProgress(0.4f, ExportStage.WRITING_ARCHIVE))
        val resolution = planned?.let { copyMedia(writer, it) }

        val builder = ArchiveManifestBuilder(options, snapshot, resolution)
        val profile = snapshot.profile.toArchiveProfile()
        val manifest = builder.manifest(exportedAt, zone.id, contents(options, snapshot, profile != null))
        writer.text(ArchiveLayout.README, ReadmeTemplate.render(manifest, readableCopies = emptyList()))
        writer.document(ArchiveLayout.MANIFEST, ArchiveManifest.serializer(), manifest)
        ArchiveSchemas.files.forEach { (path, schema) -> writer.text(path, schema) }
        writeData(writer, options, snapshot, profile, MediaReferences(resolution))
        if (options.includeMedia) writeMediaInventory(writer, resolution)
        writer.text(ArchiveLayout.CHECKSUMS, ChecksumFile.render(writer.ledger.entries))

        return ArchiveExportSummary(builder.counts(), manifest.scope, archiveSourceFingerprint(options, writer.ledger.entries))
    }

    private fun writeData(
        writer: ArchiveWriter,
        options: ArchiveExportOptions,
        snapshot: ArchiveSnapshot,
        profile: ArchiveProfile?,
        media: MediaReferences,
    ) {
        if (options.includeJournals) {
            writer.document(
                ArchiveLayout.JOURNALS,
                ArchiveJournalFile.serializer(),
                ArchiveJournalFile(
                    snapshot.journals.map {
                        it.toArchiveJournal()
                    },
                ),
            )
        }
        if (options.includeNotes) {
            val notes = snapshot.notes.map { it.toArchiveNote(snapshot.journalIdsByNote[it.uid].orEmpty(), media) }
            writer.document(ArchiveLayout.NOTES, ArchiveNoteFile.serializer(), ArchiveNoteFile(notes))
        }
        if (options.includeDrafts) {
            writer.document(
                ArchiveLayout.DRAFTS,
                ArchiveDraftFile.serializer(),
                ArchiveDraftFile(snapshot.drafts.map { it.toArchiveDraft(media) }),
            )
        }
        val places = snapshot.places.filterIsInstance<Place.UserDefined>().map { it.toArchivePlace() }
        if (places.isNotEmpty()) writer.document(ArchiveLayout.PLACES, ArchivePlaceFile.serializer(), ArchivePlaceFile(places))
        if (profile != null) writer.document(ArchiveLayout.PROFILE, ArchiveProfileFile.serializer(), ArchiveProfileFile(profile))
        if (snapshot.locationHistory.isNotEmpty()) {
            writer.lines(
                ArchiveLayout.LOCATION_HISTORY,
                ArchiveLocationSample.serializer(),
                snapshot.locationHistory.asSequence().map {
                    it.toArchiveSample()
                },
            )
        }
    }

    /** Copies each planned file and returns the plan without the files that could not be opened when their turn came. */
    private suspend fun FlowCollector<ArchiveExportProgress>.copyMedia(
        writer: ArchiveWriter,
        planned: MediaResolution,
    ): MediaResolution {
        val job: Job? = currentCoroutineContext()[Job]
        val unavailable = mutableSetOf<String>()
        planned.files.forEachIndexed { index, file ->
            val source = mediaSourceOpener.openOrNull(file.reference)
            if (source == null) unavailable += file.reference else source.use { writer.media(file.path, it, job) }
            emit(ArchiveExportProgress.InProgress(0.4f + 0.55f * (index + 1) / planned.files.size, ExportStage.WRITING_ARCHIVE))
        }
        return planned.withUnreadable(unavailable)
    }

    private fun writeMediaInventory(
        writer: ArchiveWriter,
        resolution: MediaResolution?,
    ) {
        val entries =
            resolution?.files.orEmpty().map { file ->
                val recorded = checkNotNull(writer.ledger[file.path]) { "Media was not written" }
                ArchiveMediaInventoryEntry(file.path, file.type.mimeType, recorded.bytes, recorded.sha256)
            }
        writer.document(ArchiveLayout.MEDIA_INVENTORY, ArchiveMediaFile.serializer(), ArchiveMediaFile(entries))
    }

    private fun contents(
        options: ArchiveExportOptions,
        snapshot: ArchiveSnapshot,
        hasProfile: Boolean,
    ): List<ArchiveContent> =
        buildList {
            add(ArchiveContent(ArchiveRole.README, ArchiveLayout.README, ArchiveLayout.TEXT_MEDIA_TYPE))
            if (options.includeJournals) add(json(ArchiveRole.JOURNALS, ArchiveLayout.JOURNALS, ArchiveLayout.SCHEMA_JOURNALS))
            if (options.includeNotes) add(json(ArchiveRole.NOTES, ArchiveLayout.NOTES, ArchiveLayout.SCHEMA_NOTES))
            if (options.includeDrafts) add(json(ArchiveRole.DRAFTS, ArchiveLayout.DRAFTS, ArchiveLayout.SCHEMA_DRAFTS))
            if (snapshot.places.any { it is Place.UserDefined }) {
                add(
                    json(ArchiveRole.PLACES, ArchiveLayout.PLACES, ArchiveLayout.SCHEMA_PLACES),
                )
            }
            if (hasProfile) add(json(ArchiveRole.PROFILE, ArchiveLayout.PROFILE, ArchiveLayout.SCHEMA_PROFILE))
            if (snapshot.locationHistory.isNotEmpty()) {
                add(
                    ArchiveContent(
                        ArchiveRole.LOCATION_HISTORY,
                        ArchiveLayout.LOCATION_HISTORY,
                        ArchiveLayout.JSON_LINES_MEDIA_TYPE,
                        ArchiveLayout.SCHEMA_LOCATION_SAMPLE,
                    ),
                )
            }
            if (options.includeMedia) add(json(ArchiveRole.MEDIA_INVENTORY, ArchiveLayout.MEDIA_INVENTORY, ArchiveLayout.SCHEMA_MEDIA))
            add(ArchiveContent(ArchiveRole.CHECKSUMS, ArchiveLayout.CHECKSUMS, ArchiveLayout.TEXT_MEDIA_TYPE))
        }

    private fun json(
        role: ArchiveRole,
        path: ArchivePath,
        schema: ArchivePath,
    ) = ArchiveContent(role, path, ArchiveLayout.JSON_MEDIA_TYPE, schema)
}
