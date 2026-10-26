package app.logdate.client.sync

import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.shared.model.diagnostics.DiagnosticAction
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent

/** Record-attempt reporting is shared by change and deletion application. */
internal class SyncDownloadRecordDiagnostics(
    private val report: (SyncDiagnosticEvent, DiagnosticSource?) -> Unit,
) {
    fun captureSource(
        provider: () -> DiagnosticSource?,
        scope: DownloadScope?,
    ): DiagnosticSource? =
        try {
            provider().takeIf { captured ->
                captured != null &&
                    (scope == null || (captured.scope.ownerId == scope.owner && captured.scope.serverOrigin == scope.origin))
            }
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            null
        }

    fun started(
        row: DownloadInboxEntity?,
        attemptId: String?,
        source: DiagnosticSource?,
    ) = emit(row, attemptId, source, DiagnosticOutcome.STARTED)

    fun interrupted(
        row: DownloadInboxEntity?,
        attemptId: String?,
        source: DiagnosticSource?,
    ) = emit(row, attemptId, source, DiagnosticOutcome.INTERRUPTED)

    fun completed(
        row: DownloadInboxEntity?,
        attemptId: String?,
        source: DiagnosticSource?,
        conflict: Boolean,
    ) = emit(
        row,
        attemptId,
        source,
        if (conflict) DiagnosticOutcome.CONFLICT else DiagnosticOutcome.SUCCEEDED,
        if (conflict) DiagnosticReason.CONFLICT else DiagnosticReason.NONE,
        if (conflict) DiagnosticAction.REVIEW_CONFLICT else DiagnosticAction.NONE,
    )

    fun failed(
        row: DownloadInboxEntity?,
        attemptId: String?,
        source: DiagnosticSource?,
    ) = emit(row, attemptId, source, DiagnosticOutcome.FAILED, DiagnosticReason.LOCAL_STORAGE, DiagnosticAction.RETRY, true)

    private fun emit(
        row: DownloadInboxEntity?,
        attemptId: String?,
        source: DiagnosticSource?,
        outcome: DiagnosticOutcome,
        reason: DiagnosticReason = DiagnosticReason.NONE,
        action: DiagnosticAction = DiagnosticAction.NONE,
        retryable: Boolean = false,
    ) {
        if (row == null) return
        report(
            SyncDiagnosticEvent(
                DiagnosticPhase.APPLY,
                outcome,
                reason = reason,
                action = action,
                operationId = row.operationId,
                attemptId = attemptId,
                attemptCount = row.attempts + 1,
                retryable = retryable,
            ),
            source,
        )
    }
}
