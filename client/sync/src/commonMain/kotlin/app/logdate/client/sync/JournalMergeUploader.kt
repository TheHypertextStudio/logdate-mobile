package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceSync
import app.logdate.shared.model.sync.JournalMergeRequest
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Sends the journal merges made on this device. To a server, a merge is a request; to the personal
 * space, it is the merged journal's own record rewritten with `mergedInto`, so every device that
 * reads it applies the same merge.
 */
internal class JournalMergeUploader(
    private val journals: JournalRepository,
    private val api: CloudApiClient?,
    private val metadata: SyncMetadataService,
    private val tokenRefresher: SyncTokenRefresher,
    private val retries: SyncRetryCoordinator,
    private val recordProgress: (Int) -> Unit,
    private val space: SpaceSync? = null,
    private val uploadsUpdates: Boolean = true,
) {
    suspend fun uploadPending(): SyncResult {
        val sync = space
        val kind = sync?.activeKind(EntityType.JOURNAL)
        if (sync != null && kind != null) return uploadToSpace(sync, kind)
        val client = api ?: return SyncResult(success = true)
        return uploadToServer(client)
    }

    /**
     * Queues the record of every merged journal and writes them with the journals' own upload, which
     * reads each as its merge, then settles the merges whose record reached the space.
     */
    private suspend fun uploadToSpace(
        sync: SpaceSync,
        kind: SpaceKind<*>,
    ): SyncResult {
        val queued = metadata.getPendingUploads(EntityType.JOURNAL_MERGE).associateBy { it.entityId }
        val merges =
            destinationFirst(
                journals.pendingJournalMerges(),
            ).filter { !it.needsDestination && it.operationId.toString() in queued }
        if (merges.isEmpty()) return SyncResult(success = true)
        merges.forEach { metadata.enqueuePending(it.sourceId.toString(), EntityType.JOURNAL, PendingOperation.UPDATE) }
        val result = sync.uploadKind(kind, uploadsUpdates)
        val waiting = metadata.getPendingUploads(EntityType.JOURNAL).mapTo(mutableSetOf()) { it.entityId }
        var settled = 0
        for (merge in merges.filter { it.sourceId.toString() !in waiting }) {
            journals.markJournalMergeSynced(merge)
            retries.markUploadSettled(EntityType.JOURNAL_MERGE, queued.getValue(merge.operationId.toString()), Clock.System.now(), 0L)
            settled++
            recordProgress(1)
        }
        return SyncResult(success = result.errors.isEmpty(), uploadedItems = settled, errors = result.errors)
    }

    private suspend fun uploadToServer(api: CloudApiClient): SyncResult {
        val errors = mutableListOf<SyncError>()
        var uploaded = 0
        val pending = metadata.getPendingUploads(EntityType.JOURNAL_MERGE).associateBy { it.entityId }
        // Retire B into C before A into B, so a wholly offline chain needs only C uploaded.
        val operations = destinationFirst(journals.pendingJournalMerges())
        val unsettledSources = operations.map { it.sourceId }.toMutableSet()
        for (operation in operations) {
            if (operation.destinationId in unsettledSources) continue
            val queued = pending[operation.operationId.toString()] ?: continue
            if (operation.needsDestination || !retries.shouldAttempt(EntityType.JOURNAL_MERGE, queued)) continue
            if (!retries.beginAttempt(EntityType.JOURNAL_MERGE, queued, errors)) continue
            try {
                val request =
                    JournalMergeRequest(
                        operation.operationId.toString(),
                        operation.destinationId.toString(),
                        operation.contentIds
                            .map {
                                it.toString()
                            }.sorted(),
                    )
                val response =
                    tokenRefresher
                        .withFreshToken(
                            { token -> api.mergeJournals(token, operation.sourceId.toString(), request) },
                            "mergeJournals",
                            UploadScope(operation.scope.ownerId, operation.scope.serverOrigin),
                        ).getOrThrow()
                check(response.operationId == request.operationId && response.sourceId == operation.sourceId.toString()) {
                    "Unexpected journal merge response"
                }
                val destinationId = Uuid.parse(response.destinationId)
                journals.applyJournalRedirect(operation.sourceId, destinationId, operation.scope)
                journals.markJournalMergeSynced(operation)
                retries.markUploadSettled(EntityType.JOURNAL_MERGE, queued, Clock.System.now(), 0L)
                unsettledSources.remove(operation.sourceId)
                uploaded++
                recordProgress(1)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is CloudApiException && error.errorCode == "MERGE_DESTINATION_MISSING") {
                    journals.markJournalMergeNeedsDestination(operation)
                }
                retries.handleRetryFailure(EntityType.JOURNAL_MERGE, queued, error)
                errors += SyncError(SyncErrorType.CONFLICT_ERROR, "Journal merge is waiting for sync", error)
                // An earlier source may depend on this destination's unfinished merge.
                break
            }
        }
        return SyncResult(success = errors.isEmpty(), uploadedItems = uploaded, errors = errors)
    }

    private fun destinationFirst(operations: List<JournalMergeOperation>): List<JournalMergeOperation> {
        val bySource = operations.associateBy { it.sourceId }
        val visited = mutableSetOf<Uuid>()
        val active = mutableSetOf<Uuid>()
        val ordered = mutableListOf<JournalMergeOperation>()

        fun visit(operation: JournalMergeOperation) {
            if (operation.operationId in visited) return
            check(active.add(operation.operationId)) { "Journal merge cycle" }
            bySource[operation.destinationId]?.let(::visit)
            active.remove(operation.operationId)
            visited.add(operation.operationId)
            ordered += operation
        }
        operations.forEach(::visit)
        return ordered
    }
}
