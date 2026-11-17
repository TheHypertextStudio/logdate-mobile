package app.logdate.client.sync

import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.cloud.CloudDraftDataSource
import app.logdate.client.sync.cloud.SyncUploadResult
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.FirstSyncEnqueueStore
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.space.SpaceMediaReference
import app.logdate.client.sync.space.mappers.SpaceDraft
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.sync.DeviceId
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The draft half of [SyncUploader]: uploads or deletes each pending draft, uploading its media
 * first, and queues drafts already on this device once per signed-in owner and server.
 *
 * [SyncUploader] wraps [uploadPending] in its own failure handling, so an unexpected failure here is
 * reported the same way as for every other entity type.
 */
internal class DraftUploader(
    private val journalRepository: JournalRepository,
    private val cloudDraftDataSource: CloudDraftDataSource,
    private val syncMetadataService: SyncMetadataService,
    private val deviceIdProvider: DeviceIdProvider?,
    private val tokenRefresher: SyncTokenRefresher,
    private val mediaTransfer: SyncMediaTransfer,
    private val retryCoordinator: SyncRetryCoordinator,
    private val firstSyncEnqueueStore: FirstSyncEnqueueStore,
    private val recordProgress: (Int) -> Unit,
    private val supportsRichDrafts: () -> Boolean,
    private val draftRepairScope: () -> DownloadScope?,
) {
    suspend fun uploadPending(drafts: List<EditorDraft>): SyncResult {
        var uploadedCount = 0
        val errors = mutableListOf<SyncError>()
        val draftsById = drafts.associateBy { it.id.toString() }
        val pendingUploads = syncMetadataService.getPendingUploads(EntityType.DRAFT).dueNow(EntityType.DRAFT, retryCoordinator)
        if (pendingUploads.isEmpty()) {
            return SyncResult(success = true, uploadedItems = 0)
        }

        val deviceId = currentDeviceId()

        for (pending in pendingUploads) {
            val draftId = runCatching { Uuid.parse(pending.entityId) }.getOrNull()
            if (draftId == null) {
                errors.add(retryCoordinator.recordUnparsableOutboxEntry(EntityType.DRAFT, pending, "draft ID"))
                continue
            }

            val uploaded =
                when (pending.operation) {
                    PendingOperation.DELETE -> deleteDraft(pending, draftId, errors)

                    PendingOperation.CREATE,
                    PendingOperation.UPDATE,
                    -> uploadDraft(pending, draftId, draftsById[pending.entityId], deviceId, errors)
                }
            if (uploaded) uploadedCount++
        }

        return SyncResult(success = errors.isEmpty(), uploadedItems = uploadedCount, errors = errors)
    }

    /** Queues pre-existing drafts once per signed-in owner/server, preserving pending deletions. */
    suspend fun enqueueForRichSync(serverSupportRequired: Boolean = true) {
        val selected = draftRepairScope()?.takeIf { it.owner.isNotBlank() && it.origin.isNotBlank() } ?: return
        if ((serverSupportRequired && !supportsRichDrafts()) ||
            firstSyncEnqueueStore.hasEnqueuedDraftScope(selected.owner, selected.origin)
        ) {
            return
        }
        journalRepository.getAllDraftsForSync().forEach { draft ->
            check(draftRepairScope() == selected) { "Draft sync scope changed" }
            syncMetadataService.enqueueRepairIfAbsent(draft.id.toString(), EntityType.DRAFT, serverOrigin = selected.origin)
        }
        check(draftRepairScope() == selected) { "Draft sync scope changed" }
        firstSyncEnqueueStore.markEnqueuedDraftScope(selected.owner, selected.origin)
        Napier.i("First sync: queued drafts already on this device")
    }

    /**
     * Starts an upload of [draft] to the personal space: records the attempt and uploads its media
     * as space blobs, so the draft record can refer to them. Returns the draft to write, or null
     * when it has to wait or failed.
     */
    suspend fun prepareForSpace(
        pending: PendingUpload,
        draft: SpaceDraft,
        errors: MutableList<SyncError>,
    ): SpaceDraft? {
        if (!retryCoordinator.beginAttempt(EntityType.DRAFT, pending, errors)) return null
        val prepared =
            tokenRefresher.withFreshToken(
                { token -> mediaTransfer.uploadDraftMediaIfNeeded(token, draft.draft) { SpaceMediaReference.parse(it) != null } },
                "uploadDraftMedia(${draft.draft.id})",
                expectedScope = pending.scope,
            )
        if (prepared.isSuccess) return draft.copy(draft = prepared.getOrThrow())
        val error = prepared.exceptionOrNull() ?: Exception("Unknown draft media upload error")
        recordFailure(pending, error, "Failed to upload media for draft ${draft.draft.id}", errors)
        return null
    }

    /** Returns true when the server accepted the deletion. */
    private suspend fun deleteDraft(
        pending: PendingUpload,
        draftId: Uuid,
        errors: MutableList<SyncError>,
    ): Boolean {
        if (!retryCoordinator.beginAttempt(EntityType.DRAFT, pending, errors)) return false
        val result =
            tokenRefresher.withFreshToken(
                { token -> cloudDraftDataSource.deleteDraft(token, draftId) },
                "deleteDraft($draftId)",
                expectedScope = pending.scope,
            )
        if (result.isSuccess) {
            recordProgress(1)
            retryCoordinator.markUploadSettled(EntityType.DRAFT, pending, Clock.System.now(), 0L)
            return true
        }
        val error = result.exceptionOrNull() ?: Exception("Unknown draft delete error")
        recordFailure(pending, error, "Failed to delete draft $draftId", errors)
        return false
    }

    /** Returns true when the server accepted the draft; [draft] is null once it is gone locally. */
    private suspend fun uploadDraft(
        pending: PendingUpload,
        draftId: Uuid,
        draft: EditorDraft?,
        deviceId: DeviceId,
        errors: MutableList<SyncError>,
    ): Boolean {
        if (draft == null) {
            if (journalRepository.hasUnclaimedDraft(draftId)) return false
            Napier.w("Dropping queued draft: no longer present locally")
            retryCoordinator.markUploadSettled(EntityType.DRAFT, pending, Clock.System.now(), 0L)
            return false
        }

        if (!supportsRichDrafts()) {
            if (errors.none { it.message == RICH_DRAFTS_UNSUPPORTED }) {
                errors.add(SyncError(SyncErrorType.SERVER_ERROR, RICH_DRAFTS_UNSUPPORTED, retryable = true))
            }
            return false
        }

        if (!retryCoordinator.beginAttempt(EntityType.DRAFT, pending, errors)) return false
        val result =
            tokenRefresher.withFreshToken(
                { token -> uploadWithMedia(token, draft, deviceId) },
                "uploadDraft(${draft.id})",
                expectedScope = pending.scope,
            )
        if (result.isSuccess) {
            val upload = result.getOrThrow()
            recordProgress(1)
            retryCoordinator.markUploadSettled(EntityType.DRAFT, pending, upload.syncedAt, upload.serverVersion)
            return true
        }
        val error = result.exceptionOrNull() ?: Exception("Unknown draft upload error")
        recordFailure(pending, error, "Failed to upload draft ${draft.id}", errors)
        return false
    }

    private suspend fun uploadWithMedia(
        token: String,
        draft: EditorDraft,
        deviceId: DeviceId,
    ): Result<SyncUploadResult> {
        val prepared = mediaTransfer.uploadDraftMediaIfNeeded(token, draft)
        return if (prepared.isFailure) {
            Result.failure(requireNotNull(prepared.exceptionOrNull()))
        } else {
            cloudDraftDataSource.uploadDraft(token, prepared.getOrThrow(), deviceId)
        }
    }

    private suspend fun recordFailure(
        pending: PendingUpload,
        error: Throwable,
        message: String,
        errors: MutableList<SyncError>,
    ) {
        val movedToDeadLetter = retryCoordinator.handleRetryFailure(EntityType.DRAFT, pending, error)
        errors.add(
            SyncError(
                SyncErrorType.SERVER_ERROR,
                "$message: ${error.message}",
                error,
                retryable = !movedToDeadLetter,
            ),
        )
    }

    private fun currentDeviceId(): DeviceId =
        deviceIdProvider
            ?.getDeviceId()
            ?.value
            ?.toString()
            ?.let(::DeviceId)
            ?: DeviceId.UNKNOWN

    private companion object {
        const val RICH_DRAFTS_UNSUPPORTED = "Connected server cannot sync complete drafts yet"
    }
}
