package app.logdate.feature.core.restore

import android.content.Context
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.domain.restore.ArchiveMediaRepairProgress
import app.logdate.client.domain.restore.MediaImporter
import app.logdate.client.domain.restore.RecoveryMediaRole
import app.logdate.client.domain.restore.RepairArchiveMediaUseCase
import app.logdate.client.domain.restore.UnavailableMediaAsset
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.isRecoveredOriginal
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.sync.AutomaticMediaRecovery
import app.logdate.client.sync.AutomaticMediaRecoveryResult
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.model.diagnostics.DiagnosticReason
import org.koin.core.Koin
import kotlin.uuid.Uuid

internal class ArchiveMediaRecoveryEngine(
    private val context: Context,
    private val koin: Koin,
) {
    suspend fun repair(record: ArchiveRecoveryRecord): RestoreSummary {
        val scope = record.scope
        check(currentArchiveRecoveryScope(koin) == scope) { "Archive recovery account changed" }
        val store = context.archiveRecoveryStore()
        val media = koin.get<MediaManager>()
        val cloud = koin.getOrNull<AutomaticMediaRecovery>()
        val reasons = mutableMapOf<String, DiagnosticReason>()
        RetainedArchiveMediaSources(store, scope, record.id) { zip, root, integrity ->
            AndroidArchiveMediaImporter(context, media, zip, root, koin.get(), integrity, koin.get())
        }.use { sources ->
            val pending = record.pending.map { it.copy(archivePath = sources.path(it)) }
            store.replacePending(record.id, pending)
            val baseSummary = initialProgress(record, pending)
            store.updateProgress(record.id, pending, baseSummary)
            val importer = createImporter(scope, sources, media, cloud, reasons)
            val result =
                RepairArchiveMediaUseCase(koin.get(), koin.get()).repair(
                    pending,
                    importer,
                    onProgress = { progress ->
                        publishProgress(record.id, baseSummary, progress, store)
                    },
                    expectedNoteScope = JournalMergeScope(scope.ownerId, scope.serverOrigin),
                )
            check(currentArchiveRecoveryScope(koin) == scope) { "Archive recovery account changed" }
            val session = koin.get<SessionStorage>().getOriginBoundSession()
            val retrying =
                result.pending.any { asset ->
                    asset.archivePath != null ||
                        asset.recoveredReference != null ||
                        (reasons[asset.reference]?.shouldRetryArchiveMedia(hasSession = session != null) == true)
                }
            val summary =
                requireNotNull(record.summary).copy(
                    mediaImported = record.summary.mediaImported + result.imported,
                    recoveryId = record.id,
                    unavailableOriginals = result.pending.count { it.role == RecoveryMediaRole.ORIGINAL },
                    unavailablePreviews = result.pending.count { it.role == RecoveryMediaRole.THUMBNAIL },
                    recoveringMedia = retrying,
                    mediaRecoveryChecked = pending.size,
                    mediaRecoveryTotal = pending.size,
                )
            store.finish(record.id, summary)
            return summary
        }
    }

    private fun initialProgress(
        record: ArchiveRecoveryRecord,
        pending: List<UnavailableMediaAsset>,
    ): RestoreSummary =
        requireNotNull(record.summary).copy(
            recoveryId = record.id,
            unavailableOriginals = pending.count { it.role == RecoveryMediaRole.ORIGINAL },
            unavailablePreviews = pending.count { it.role == RecoveryMediaRole.THUMBNAIL },
            mediaRecoveryChecked = 0,
            mediaRecoveryTotal = pending.size,
            recoveringMedia = pending.isNotEmpty(),
        )

    private fun publishProgress(
        recoveryId: String,
        baseSummary: RestoreSummary,
        progress: ArchiveMediaRepairProgress,
        store: ArchiveRecoveryStore,
    ) {
        val summary =
            baseSummary.copy(
                mediaImported = baseSummary.mediaImported + progress.imported,
                unavailableOriginals = progress.pending.count { it.role == RecoveryMediaRole.ORIGINAL },
                unavailablePreviews = progress.pending.count { it.role == RecoveryMediaRole.THUMBNAIL },
                mediaRecoveryChecked = progress.checked,
                mediaRecoveryTotal = progress.total,
                recoveringMedia = progress.pending.isNotEmpty(),
            )
        store.updateProgress(recoveryId, progress.pending, summary)
        koin.get<RestoreLauncher>().updateRecovery(summary)
    }

    private fun createImporter(
        scope: ArchiveRecoveryScope,
        sources: RetainedArchiveMediaSources,
        media: MediaManager,
        cloud: AutomaticMediaRecovery?,
        reasons: MutableMap<String, DiagnosticReason>,
    ): MediaImporter =
        object : MediaImporter {
            override suspend fun isAvailable(reference: String) = media.isRecoveredOriginal(reference, koin.get<StoredMediaReferences>())

            override suspend fun importMedia(exportPath: String) = sources.importMedia(exportPath)

            override suspend fun recoverMedia(asset: UnavailableMediaAsset): String? = recoverFromCloud(scope, asset, cloud, reasons)
        }

    private suspend fun recoverFromCloud(
        scope: ArchiveRecoveryScope,
        asset: UnavailableMediaAsset,
        cloud: AutomaticMediaRecovery?,
        reasons: MutableMap<String, DiagnosticReason>,
    ): String? {
        val original = asset.sourceReference ?: return null
        val draftId = asset.blockId?.let { Uuid.parse(asset.entityId) }
        val entityId = Uuid.parse(asset.blockId ?: asset.entityId)
        val kind = if (asset.role == RecoveryMediaRole.THUMBNAIL) "thumbnail" else asset.kind ?: "image"
        return when (
            val result =
                cloud?.recover(
                    entityId,
                    draftId,
                    kind,
                    original,
                    asset.reference,
                    UploadScope(scope.ownerId, scope.serverOrigin),
                )
        ) {
            is AutomaticMediaRecoveryResult.Available -> {
                result.reference
            }

            is AutomaticMediaRecoveryResult.Pending -> {
                reasons[asset.reference] = result.reason
                null
            }

            null -> {
                null
            }
        }
    }
}
