package app.logdate.client.sync

import app.logdate.client.sync.SyncRetryPolicy.DEAD_LETTER_RETRY_INTERVAL_MS
import app.logdate.client.sync.SyncRetryPolicy.MAX_RETRY_ATTEMPTS
import app.logdate.client.sync.SyncRetryPolicy.MAX_UNFINISHED_ATTEMPTS
import app.logdate.client.sync.SyncRetryPolicy.TRANSIENT_REASONS
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncBackoff
import app.logdate.client.sync.metadata.SyncDeadLetterReason
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.metadata.effectiveReason
import app.logdate.client.sync.metadata.retryKey
import app.logdate.shared.model.diagnostics.DiagnosticAction
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import io.github.aakira.napier.Napier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Every upload function's shared retry/dead-letter/settlement bookkeeping: whether an entity is
 * due for another attempt, what happens when one fails (backoff vs. permanent dead-letter),
 * marking an attempt settled (success or a lost conflict, both terminal), and the two special-case
 * settlements (an unparsable outbox entry, a 409 conflict) that only differ from a plain success in
 * what error they report.
 *
 * @param recordConflict [SyncDownloadEngine.recordConflict] -- reused rather than duplicated so a
 *   conflict detected during upload is recorded identically to one detected during download.
 */
internal class SyncRetryCoordinator(
    private val retryScheduleStore: SyncRetryScheduleStore,
    private val syncMetadataService: SyncMetadataService,
    private val deadLetterStore: SyncDeadLetterStore,
    private val backoff: SyncBackoff,
    private val recordConflict: suspend (
        entityType: EntityType,
        entityId: String,
        reason: String,
        localVersion: Long?,
        remoteVersion: Long?,
        localUpdatedAt: Instant?,
        remoteUpdatedAt: Instant?,
    ) -> Unit,
    private val diagnosticSource: () -> DiagnosticSource? = { null },
    private val diagnostics: (SyncDiagnosticEvent, DiagnosticSource?) -> Unit = { _, _ -> },
    private val preserveDeletedEdit: suspend (EntityType, String) -> Unit = { _, _ -> error("Recovery storage unavailable") },
) {
    /**
     * Reconsiders transient failures when connectivity returns. A new build also reconsiders
     * retained failures, including interrupted uploads, once after its durable upgrade check.
     */
    suspend fun releaseBackoff(
        afterUpgrade: Boolean = false,
        expectedScope: UploadScope? = null,
    ) {
        val setAside = deadLetterStore.list()
        for (entityType in EntityType.entries) {
            for (pending in syncMetadataService.getPendingUploads(entityType)) {
                if (expectedScope != null && pending.scope != expectedScope) continue
                val retained = setAside.firstOrNull { it.entityType == entityType.name && it.matches(pending) }
                if (!afterUpgrade && retained != null && retained.effectiveReason() !in TRANSIENT_REASONS) continue
                if (!syncMetadataService.isCurrentOperation(entityType, pending)) continue
                if (afterUpgrade) {
                    retryScheduleStore.clear(entityType, pending.retryKey())
                    if (retained?.effectiveReason() == SyncDeadLetterReason.APP_CLOSED) deadLetterStore.remove(retained.id)
                    clearFailureKind(entityType, pending.retryKey())
                    continue
                }
                if (retryScheduleStore.nextAttemptAt(entityType, pending.retryKey()) != null) {
                    retryScheduleStore.setNextAttemptAt(entityType, pending.retryKey(), 0L)
                }
            }
        }
    }

    suspend fun shouldAttempt(
        entityType: EntityType,
        entityId: String,
    ): Boolean {
        val pending = syncMetadataService.getPendingUploads(entityType).firstOrNull { it.entityId == entityId } ?: return true
        return shouldAttempt(entityType, pending)
    }

    suspend fun shouldAttempt(
        entityType: EntityType,
        pending: PendingUpload,
    ): Boolean {
        val retained = deadLetterStore.list().firstOrNull { it.entityType == entityType.name && it.matches(pending) }
        if (retained != null && retained.effectiveReason() !in TRANSIENT_REASONS) return false
        val nextAttemptAt = retryScheduleStore.nextAttemptAt(entityType, pending.retryKey()) ?: return true
        return Clock.System.now().toEpochMilliseconds() >= nextAttemptAt
    }

    /**
     * Records that an upload of [pending] is about to start, before anything that could take the app
     * down with it. Returns `null` to go ahead, or the error to report instead of attempting it.
     *
     * An upload that crashes the app never gets as far as [handleRetryFailure], so without this the
     * same entry is first in the queue every time the app comes back and crashes it again, for ever.
     * One unfinished attempt is not proof of that -- the system or the user may have closed the app
     * -- so it is tried again; a second one sets the entry aside in Sync Issues.
     */
    suspend fun beginUpload(
        entityType: EntityType,
        pending: PendingUpload,
    ): SyncError? {
        if (!syncMetadataService.isCurrentOperation(entityType, pending)) {
            return SyncError(
                SyncErrorType.UNKNOWN_ERROR,
                "Queued operation changed",
                retryable = true,
            )
        }
        val unfinished = retryScheduleStore.beginAttempt(entityType, pending.retryKey())
        val key = PendingEntityKey(entityType, pending.retryKey())
        val source =
            try {
                diagnosticSource()?.takeIf { it.scope == pending.scope }
            } catch (_: Exception) {
                null
            }
        attemptsInFlight[key] = UploadAttempt(pending.operationId, Uuid.random().toString(), source)
        if (unfinished < MAX_UNFINISHED_ATTEMPTS) {
            if (unfinished > 0) {
                Napier.w("The last upload of never finished; trying it again")
            }
            report(DiagnosticOutcome.STARTED, attemptsInFlight[key])
            return null
        }

        val error = InterruptedUploadException(unfinished)
        handleRetryFailure(entityType, pending, error, permanent = true)
        Napier.e("Set aside")
        return SyncError(
            SyncErrorType.UNKNOWN_ERROR,
            "Upload set aside after repeated interruption",
            retryable = false,
        )
    }

    /**
     * Lets go of every attempt started by [beginUpload] that has not finished, for a sync that was
     * stopped or failed rather than crashed. Neither is the app closing, so neither may count
     * towards setting the entry aside as one.
     */
    suspend fun abandonAttemptsInFlight() {
        withContext(NonCancellable) {
            attemptsInFlight.forEach { (key, attempt) ->
                retryScheduleStore.endAttempt(key.entityType, key.entityId)
                report(DiagnosticOutcome.INTERRUPTED, attempt)
            }
            attemptsInFlight.clear()
        }
    }

    private suspend fun endAttempt(
        entityType: EntityType,
        entityId: String,
    ): UploadAttempt? {
        retryScheduleStore.endAttempt(entityType, entityId)
        return attemptsInFlight.remove(PendingEntityKey(entityType, entityId))
    }

    suspend fun handleRetryFailure(
        entityType: EntityType,
        pending: PendingUpload,
        error: Throwable,
        permanent: Boolean = false,
    ): Boolean {
        val attempt = endAttempt(entityType, pending.retryKey())
        throwIfDeviceBlocked(error)
        if (!syncMetadataService.incrementRetryIfCurrent(entityType, pending)) {
            report(DiagnosticOutcome.INTERRUPTED, attempt)
            return false
        }
        val nextRetryCount = pending.retryCount + 1
        // Record *after* the caller has already read the prior state to compute [permanent] --
        // this call is what the *next* attempt for this entity will see as "the previous failure".
        recordFailureKind(entityType, pending.retryKey(), error)

        // A permanent failure will fail identically every time, so spending the retry budget on it
        // only keeps the queue blocked for longer.
        if (permanent || nextRetryCount >= MAX_RETRY_ATTEMPTS) {
            deadLetterStore.add(
                SyncDeadLetterRecord(
                    id = pending.operationId ?: "${entityType.name}:${pending.entityId}",
                    entityType = entityType.name,
                    entityId = pending.entityId,
                    operation = pending.operation.name,
                    retryCount = nextRetryCount,
                    lastError = classifySyncFailure(error).name,
                    scope = pending.scope,
                    operationId = pending.operationId,
                    expectedServerVersion = pending.expectedServerVersion,
                    failedAt = Clock.System.now().toEpochMilliseconds(),
                    reason = classifySyncFailure(error),
                ),
            )
            // Deliberately left in the pending queue. Removing it here would report an entry that
            // never reached the server as synced to every count the user can see, and it would
            // never be attempted again. Queued behind a long backoff it stays visibly unsynced and
            // recovers on its own once whatever broke it is fixed.
            retryScheduleStore.setNextAttemptAt(
                entityType,
                pending.retryKey(),
                Clock.System.now().toEpochMilliseconds() + DEAD_LETTER_RETRY_INTERVAL_MS,
            )
            clearFailureKind(entityType, pending.retryKey())
            report(DiagnosticOutcome.PAUSED, attempt, error)
            return true
        }

        val delayMs = computeBackoffMs(nextRetryCount)
        retryScheduleStore.setNextAttemptAt(
            entityType,
            pending.retryKey(),
            Clock.System.now().toEpochMilliseconds() + delayMs,
        )
        report(DiagnosticOutcome.RETRY_SCHEDULED, attempt, error, nextRetryCount)
        return false
    }

    private data class UploadAttempt(
        val operationId: String?,
        val attemptId: String,
        val source: DiagnosticSource?,
    )

    private fun report(
        outcome: DiagnosticOutcome,
        attempt: UploadAttempt?,
        error: Throwable? = null,
        attemptCount: Int = 0,
    ) {
        if (attempt == null) return
        if (error == null) {
            safelyReport(
                SyncDiagnosticEvent(
                    DiagnosticPhase.UPLOAD,
                    outcome,
                    operationId = attempt.operationId,
                    attemptId = attempt.attemptId,
                ),
                attempt.source,
            )
            return
        }
        val reason =
            when (classifySyncFailure(error)) {
                SyncDeadLetterReason.MISSING_FILE -> DiagnosticReason.MISSING_MEDIA
                SyncDeadLetterReason.APP_CLOSED -> DiagnosticReason.UNKNOWN
                SyncDeadLetterReason.SERVER_UNAVAILABLE -> DiagnosticReason.SERVER_UNAVAILABLE
                SyncDeadLetterReason.SIGN_IN_REQUIRED -> DiagnosticReason.SIGN_IN_REQUIRED
                SyncDeadLetterReason.NETWORK_UNAVAILABLE -> DiagnosticReason.OFFLINE
                SyncDeadLetterReason.FILE_TOO_LARGE -> DiagnosticReason.QUOTA_EXCEEDED
                SyncDeadLetterReason.UNKNOWN -> DiagnosticReason.UNKNOWN
            }
        val action =
            when (reason) {
                DiagnosticReason.OFFLINE -> DiagnosticAction.CONNECT
                DiagnosticReason.SIGN_IN_REQUIRED -> DiagnosticAction.SIGN_IN
                DiagnosticReason.MISSING_MEDIA -> DiagnosticAction.RETRY
                else -> DiagnosticAction.RETRY
            }
        safelyReport(
            SyncDiagnosticEvent(
                DiagnosticPhase.UPLOAD,
                outcome,
                reason,
                action,
                operationId = attempt.operationId,
                attemptId = attempt.attemptId,
                attemptCount = attemptCount,
                retryable = outcome == DiagnosticOutcome.RETRY_SCHEDULED,
            ),
            attempt.source,
        )
    }

    private fun safelyReport(
        event: SyncDiagnosticEvent,
        source: DiagnosticSource?,
    ) {
        try {
            diagnostics(event, source)
        } catch (_: Exception) {
            // Diagnostics cannot change sync.
        }
    }

    private data class PendingEntityKey(
        val entityType: EntityType,
        val entityId: String,
    )

    /** A missing-file miss becomes permanent only after consecutive misses for the same operation. */
    private val entitiesLastFailedOnMissingMedia = mutableSetOf<PendingEntityKey>()

    /** Attempts [beginUpload] started in this process that have not finished yet. */
    private val attemptsInFlight = mutableMapOf<PendingEntityKey, UploadAttempt>()

    /** Whether the failure immediately preceding this one for [entityId] was [MissingMediaException]. */
    fun previousFailureWasMissingMedia(
        entityType: EntityType,
        entityId: String,
    ): Boolean = PendingEntityKey(entityType, entityId) in entitiesLastFailedOnMissingMedia

    fun previousFailureWasMissingMedia(
        entityType: EntityType,
        pending: PendingUpload,
    ): Boolean = previousFailureWasMissingMedia(entityType, pending.retryKey())

    private fun recordFailureKind(
        entityType: EntityType,
        entityId: String,
        error: Throwable,
    ) {
        val key = PendingEntityKey(entityType, entityId)
        if (error is MissingMediaException) {
            entitiesLastFailedOnMissingMedia.add(key)
        } else {
            entitiesLastFailedOnMissingMedia.remove(key)
        }
    }

    /** Forgets the tracked failure kind for an entity that has left the pending queue. */
    private fun clearFailureKind(
        entityType: EntityType,
        entityId: String,
    ) {
        entitiesLastFailedOnMissingMedia.remove(PendingEntityKey(entityType, entityId))
    }

    private fun computeBackoffMs(retryCount: Int): Long = backoff.nextDelayMs(retryCount)

    /**
     * An upload attempt for [pending] is done and should not be retried: marks it synced and
     * forgets whatever retry/failure-kind state it had accumulated. Used identically whether the
     * item actually succeeded or a conflict was recorded in its place -- both are terminal outcomes
     * for this attempt, differing only in what [syncedAt]/[version] the caller has to report.
     */
    suspend fun markUploadSettled(
        entityType: EntityType,
        pending: PendingUpload,
        syncedAt: Instant,
        version: Long,
        outcome: DiagnosticOutcome = DiagnosticOutcome.SUCCEEDED,
    ) {
        val settled = syncMetadataService.settleIfCurrent(entityType, pending, syncedAt, version)
        retryScheduleStore.clear(entityType, pending.retryKey())
        deadLetterStore.remove(pending.operationId ?: "${entityType.name}:${pending.entityId}")
        val attempt = attemptsInFlight.remove(PendingEntityKey(entityType, pending.retryKey()))
        clearFailureKind(entityType, pending.retryKey())
        if (settled) {
            val reason = if (outcome == DiagnosticOutcome.CONFLICT) DiagnosticReason.CONFLICT else DiagnosticReason.NONE
            val action = if (outcome == DiagnosticOutcome.CONFLICT) DiagnosticAction.REVIEW_CONFLICT else DiagnosticAction.NONE
            if (attempt != null) {
                safelyReport(
                    SyncDiagnosticEvent(
                        DiagnosticPhase.UPLOAD,
                        outcome,
                        reason,
                        action,
                        operationId = attempt.operationId,
                        attemptId = attempt.attemptId,
                    ),
                    attempt.source,
                )
            }
        }
    }

    /** Compatibility for local callers that have not started any asynchronous operation. */
    suspend fun markUploadSettled(
        entityType: EntityType,
        entityId: String,
        syncedAt: Instant,
        version: Long,
    ) {
        val pending = syncMetadataService.getPendingUploads(entityType).firstOrNull { it.entityId == entityId } ?: return
        markUploadSettled(entityType, pending, syncedAt, version)
    }

    /** Retain unsupported legacy identifiers so a later compatibility repair can recover them. */
    suspend fun recordUnparsableOutboxEntry(
        entityType: EntityType,
        pending: PendingUpload,
        description: String,
    ): SyncError {
        handleRetryFailure(entityType, pending, IllegalArgumentException("Invalid $description"), permanent = true)
        return SyncError(SyncErrorType.UNKNOWN_ERROR, "Invalid queued upload", retryable = false)
    }

    suspend fun recordUnavailableOutboxEntry(
        entityType: EntityType,
        pending: PendingUpload,
    ): SyncError {
        handleRetryFailure(entityType, pending, IllegalStateException("Local upload record is unavailable"), permanent = true)
        return SyncError(SyncErrorType.UNKNOWN_ERROR, "Local upload record is unavailable", retryable = false)
    }

    /**
     * The server rejected an upload with a 409: the remote side moved since this device last saw
     * it. Queues a conflict record for later review, settles this attempt at the version that lost
     * (so it stops retrying a write that will only 409 again), and returns the [SyncError] to
     * report for it.
     */
    suspend fun handleUploadConflict(
        entityType: EntityType,
        pending: PendingUpload,
        itemLabel: String,
        conflictLabel: String,
        error: Throwable,
        localVersion: Long,
        localUpdatedAt: Instant,
    ): SyncError {
        if (!syncMetadataService.isCurrentOperation(entityType, pending)) {
            return SyncError(
                SyncErrorType.CONFLICT_ERROR,
                "Queued operation changed",
                retryable = true,
            )
        }
        if (error is CloudApiException && error.errorCode == "DELETION_CONFLICT") {
            preserveDeletedEdit(entityType, pending.entityId)
            markUploadSettled(entityType, pending, Clock.System.now(), localVersion, DiagnosticOutcome.CONFLICT)
            return SyncError(SyncErrorType.CONFLICT_ERROR, "An edit to a deleted item was saved for review", retryable = false)
        }
        recordConflict(
            entityType,
            pending.entityId,
            "Queued upload conflict",
            localVersion,
            null,
            localUpdatedAt,
            null,
        )
        markUploadSettled(entityType, pending, Clock.System.now(), localVersion, DiagnosticOutcome.CONFLICT)
        Napier.w("Queued conflict for")
        return SyncError(
            SyncErrorType.CONFLICT_ERROR,
            "Queued upload conflict",
            retryable = false,
        )
    }

    /** Legacy global issues cannot be attached to new scoped operations by entity ID. */
    suspend fun retryDeadLetter(id: String) {
        val record = deadLetterStore.list().firstOrNull { it.id == id } ?: return
        val type = EntityType.entries.firstOrNull { it.name == record.entityType } ?: return
        val pending = syncMetadataService.getPendingUploads(type).firstOrNull { record.matches(it) } ?: return
        retryScheduleStore.clear(type, pending.retryKey())
        deadLetterStore.remove(id)
    }

    suspend fun discardDeadLetter(id: String) {
        val record = deadLetterStore.list().firstOrNull { it.id == id } ?: return
        val type = EntityType.entries.firstOrNull { it.name == record.entityType } ?: return
        val pending = syncMetadataService.getPendingUploads(type).firstOrNull { record.matches(it) } ?: return
        if (!syncMetadataService.settleIfCurrent(type, pending, Clock.System.now(), 0L)) return
        retryScheduleStore.clear(type, pending.retryKey())
        deadLetterStore.remove(id)
    }

    private fun SyncDeadLetterRecord.matches(pending: PendingUpload): Boolean =
        entityId == pending.entityId &&
            scope == pending.scope &&
            operationId == pending.operationId &&
            operation == pending.operation.name &&
            expectedServerVersion == pending.expectedServerVersion
}
