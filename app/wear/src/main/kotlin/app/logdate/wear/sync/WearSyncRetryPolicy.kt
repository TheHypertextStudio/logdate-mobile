package app.logdate.wear.sync

import app.logdate.client.sync.SyncError
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncDeadLetterReason
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import io.github.aakira.napier.Napier
import kotlin.time.Clock

/** Retry and terminal-failure bookkeeping always stays bound to the captured operation. */
internal class WearSyncRetryPolicy(
    private val syncMetadataService: SyncMetadataService,
    private val retryScheduleStore: SyncRetryScheduleStore,
    private val deadLetterStore: SyncDeadLetterStore,
    private val clock: Clock,
) {
    suspend fun retryDeadLetter(id: String) {
        val record = deadLetterStore.list().firstOrNull { it.id == id } ?: return
        val entityType = runCatching { EntityType.valueOf(record.entityType) }.getOrNull()
        val operation = runCatching { PendingOperation.valueOf(record.operation) }.getOrNull()
        if (entityType == null || operation == null) {
            Napier.w("Cannot retry dead-letter $id with type=${record.entityType} op=${record.operation}")
            return
        }
        val pending =
            syncMetadataService.getPendingUploads(entityType).firstOrNull {
                it.entityId == record.entityId &&
                    it.operationId == record.operationId &&
                    it.scope == record.scope &&
                    it.operation == operation &&
                    it.expectedServerVersion == record.expectedServerVersion
            } ?: return
        retryScheduleStore.clear(entityType, retryKey(pending))
        deadLetterStore.remove(id)
    }

    fun retryKey(pending: PendingUpload): String {
        if (pending.operationId == null) return pending.entityId
        val owner = pending.scope?.ownerId.orEmpty()
        val origin = pending.scope?.serverOrigin.orEmpty()
        return "operation:${owner.length}:$owner:${origin.length}:$origin:${pending.operationId}"
    }

    private fun deadLetterId(
        entityType: EntityType,
        pending: PendingUpload,
    ): String = pending.operationId ?: "${entityType.name}_${pending.entityId}"

    // -----------------------------------------------------------------------
    // Retry & dead letter infrastructure
    // -----------------------------------------------------------------------

    /**
     * Checks whether a pending item is eligible for a sync attempt.
     *
     * Returns false if the item has a scheduled retry time in the future
     * (exponential backoff is still in effect).
     */
    suspend fun shouldAttempt(
        entityType: EntityType,
        pending: PendingUpload,
    ): Boolean {
        val retainedFailure =
            deadLetterStore.list().any {
                it.entityType == entityType.name &&
                    it.entityId == pending.entityId &&
                    it.scope == pending.scope &&
                    it.operationId == pending.operationId &&
                    it.operation == pending.operation.name &&
                    it.expectedServerVersion == pending.expectedServerVersion
            }
        if (retainedFailure) return false
        val nextAttemptAt = retryScheduleStore.nextAttemptAt(entityType, retryKey(pending)) ?: return true
        return clock.now().toEpochMilliseconds() >= nextAttemptAt
    }

    /**
     * Handles a failed sync attempt with exponential backoff and dead letter queue.
     *
     * After [WearDataLayerSyncManager.MAX_RETRY_ATTEMPTS] failures, the item remains pending and is retained in Sync Issues.
     *
     * @return true if the item was dead-lettered (caller should not retry), false otherwise
     */
    private suspend fun handleSyncFailure(
        pending: PendingUpload,
        entityType: EntityType,
        reason: SyncDeadLetterReason = SyncDeadLetterReason.NETWORK_UNAVAILABLE,
    ): Boolean {
        val nextRetryCount = pending.retryCount + 1
        if (!syncMetadataService.incrementRetryIfCurrent(entityType, pending)) return false

        if (nextRetryCount >= WearDataLayerSyncManager.MAX_RETRY_ATTEMPTS) {
            Napier.w("Dead-lettering ${entityType.name} ${pending.entityId} after $nextRetryCount failures")
            deadLetterStore.add(
                SyncDeadLetterRecord(
                    id = deadLetterId(entityType, pending),
                    entityType = entityType.name,
                    entityId = pending.entityId,
                    operation = pending.operation.name,
                    retryCount = nextRetryCount,
                    lastError = reason.name,
                    reason = reason,
                    scope = pending.scope,
                    operationId = pending.operationId,
                    expectedServerVersion = pending.expectedServerVersion,
                    failedAt = clock.now().toEpochMilliseconds(),
                ),
            )
            // A failed transfer is still pending; the retained failure gates automatic retries.
            retryScheduleStore.clear(entityType, retryKey(pending))
            return true
        }

        // Schedule next attempt with exponential backoff
        val backoffMs = computeBackoffMs(nextRetryCount)
        val nextAttemptAt = clock.now().toEpochMilliseconds() + backoffMs
        retryScheduleStore.setNextAttemptAt(entityType, retryKey(pending), nextAttemptAt)
        Napier.d(
            "Scheduled retry #$nextRetryCount for ${entityType.name} ${pending.entityId} " +
                "in ${backoffMs}ms",
        )
        return false
    }

    /**
     * Computes exponential backoff delay: 5s * 2^(retryCount-1), capped at 5 minutes.
     */
    private fun computeBackoffMs(retryCount: Int): Long {
        val exponent = (retryCount - 1).coerceAtLeast(0).coerceAtMost(6)
        val delay = WearDataLayerSyncManager.RETRY_BASE_DELAY_MS * (1L shl exponent)
        return delay.coerceAtMost(WearDataLayerSyncManager.RETRY_MAX_DELAY_MS)
    }

    suspend fun handleOutcome(
        outcome: WearUploadOutcome,
        pending: PendingUpload,
        entityType: EntityType,
        errors: MutableList<SyncError>,
        onSuccess: () -> Unit,
    ) {
        when (outcome) {
            WearUploadOutcome.SUCCESS -> {
                if (syncMetadataService.settleIfCurrent(entityType, pending, clock.now(), 1L)) {
                    onSuccess()
                    retryScheduleStore.clear(entityType, retryKey(pending))
                }
            }

            WearUploadOutcome.SKIPPED -> {
                val errorMsg = "Local record is unavailable; the upload remains pending"
                handleSyncFailure(pending, entityType, reason = SyncDeadLetterReason.UNKNOWN)
                errors.add(SyncError(SyncErrorType.STORAGE_ERROR, errorMsg))
            }

            WearUploadOutcome.AWAITING_ACK -> {
                retryScheduleStore.setNextAttemptAt(
                    entityType,
                    retryKey(pending),
                    clock.now().toEpochMilliseconds() + WearDataLayerSyncManager.ACK_TIMEOUT_MS,
                )
            }

            WearUploadOutcome.FAILED -> {
                val errorMsg = "Failed to sync ${entityType.name.lowercase()} ${pending.entityId}"
                val deadLettered = handleSyncFailure(pending, entityType)
                errors.add(
                    SyncError(
                        type = SyncErrorType.NETWORK_ERROR,
                        message = errorMsg,
                        retryable = !deadLettered,
                    ),
                )
            }
        }
    }
}
