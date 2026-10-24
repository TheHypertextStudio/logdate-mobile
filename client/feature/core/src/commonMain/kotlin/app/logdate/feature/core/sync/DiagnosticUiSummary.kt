package app.logdate.feature.core.sync

import app.logdate.shared.model.diagnostics.DiagnosticAction
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.SyncDiagnosticReport

enum class DiagnosticRetryCondition {
    UNKNOWN,
    AUTOMATIC,
    NETWORK,
    SIGN_IN,
    KEY_RECOVERY,
    FREE_SPACE,
    MANAGE_STORAGE,
    REPLACE_MEDIA,
    UPDATE_APP,
    UPDATE_SERVER,
    REVIEW_CONFLICT,
    USER_RETRY,
}

data class DiagnosticUiSummary(
    val lastAttemptPhase: DiagnosticPhase? = null,
    val lastAttemptOutcome: DiagnosticOutcome? = null,
    val nextRetryCondition: DiagnosticRetryCondition = DiagnosticRetryCondition.UNKNOWN,
    val lastSuccessfulPhase: DiagnosticPhase? = null,
    val blockerPhase: DiagnosticPhase? = null,
    val blockerReason: DiagnosticReason? = null,
    val nextAction: DiagnosticAction = DiagnosticAction.NONE,
)

fun summarizeDiagnostics(report: SyncDiagnosticReport): DiagnosticUiSummary {
    val latestByWork = linkedMapOf<String, app.logdate.shared.model.diagnostics.SyncDiagnosticEvent>()
    report.events.forEach { event ->
        val key =
            when {
                event.operationId != null -> "operation:${event.operationId}"
                event.attemptId != null -> "attempt:${event.attemptId}"
                event.runId != null -> "run:${event.runId}:${event.phase}"
                else -> "phase:${event.phase}"
            }
        latestByWork.remove(key)
        latestByWork[key] = event
    }
    val blocker =
        latestByWork.values.lastOrNull { event ->
            event.outcome in
                setOf(
                    DiagnosticOutcome.FAILED,
                    DiagnosticOutcome.INTERRUPTED,
                    DiagnosticOutcome.CONFLICT,
                    DiagnosticOutcome.PAUSED,
                    DiagnosticOutcome.RETRY_SCHEDULED,
                )
        }
    val action =
        blocker?.let { event ->
            if (event.action != DiagnosticAction.NONE) event.action else recommendedDiagnosticAction(event.reason, event.outcome)
        } ?: DiagnosticAction.NONE
    val retryCondition = diagnosticRetryCondition(blocker, action)
    return DiagnosticUiSummary(
        lastAttemptPhase = report.events.lastOrNull()?.phase,
        lastAttemptOutcome = report.events.lastOrNull()?.outcome,
        nextRetryCondition = retryCondition,
        lastSuccessfulPhase = report.events.lastOrNull { it.outcome == DiagnosticOutcome.SUCCEEDED }?.phase,
        blockerPhase = blocker?.phase,
        blockerReason = blocker?.reason,
        nextAction = action,
    )
}

private fun diagnosticRetryCondition(
    blocker: app.logdate.shared.model.diagnostics.SyncDiagnosticEvent?,
    action: DiagnosticAction,
): DiagnosticRetryCondition =
    when {
        blocker?.reason == DiagnosticReason.NETWORK_POLICY -> {
            DiagnosticRetryCondition.AUTOMATIC
        }

        blocker?.reason == DiagnosticReason.OFFLINE -> {
            DiagnosticRetryCondition.NETWORK
        }

        action == DiagnosticAction.FREE_SPACE -> {
            DiagnosticRetryCondition.FREE_SPACE
        }

        blocker?.outcome == DiagnosticOutcome.RETRY_SCHEDULED -> {
            DiagnosticRetryCondition.AUTOMATIC
        }

        else -> {
            when (action) {
                DiagnosticAction.CONNECT -> DiagnosticRetryCondition.NETWORK
                DiagnosticAction.SIGN_IN -> DiagnosticRetryCondition.SIGN_IN
                DiagnosticAction.RECOVER_KEY -> DiagnosticRetryCondition.KEY_RECOVERY
                DiagnosticAction.FREE_SPACE -> DiagnosticRetryCondition.FREE_SPACE
                DiagnosticAction.MANAGE_STORAGE -> DiagnosticRetryCondition.MANAGE_STORAGE
                DiagnosticAction.REPLACE_MEDIA -> DiagnosticRetryCondition.REPLACE_MEDIA
                DiagnosticAction.UPDATE_APP -> DiagnosticRetryCondition.UPDATE_APP
                DiagnosticAction.UPDATE_SERVER -> DiagnosticRetryCondition.UPDATE_SERVER
                DiagnosticAction.REVIEW_CONFLICT -> DiagnosticRetryCondition.REVIEW_CONFLICT
                DiagnosticAction.RETRY -> DiagnosticRetryCondition.USER_RETRY
                else -> DiagnosticRetryCondition.UNKNOWN
            }
        }
    }

private fun recommendedDiagnosticAction(
    reason: DiagnosticReason,
    outcome: DiagnosticOutcome,
): DiagnosticAction =
    when (reason) {
        DiagnosticReason.NETWORK_POLICY -> {
            DiagnosticAction.NONE
        }

        DiagnosticReason.OFFLINE -> {
            DiagnosticAction.CONNECT
        }

        DiagnosticReason.SIGN_IN_REQUIRED -> {
            DiagnosticAction.SIGN_IN
        }

        DiagnosticReason.QUOTA_EXCEEDED -> {
            DiagnosticAction.MANAGE_STORAGE
        }

        DiagnosticReason.MEDIA_TOO_LARGE -> {
            DiagnosticAction.REPLACE_MEDIA
        }

        DiagnosticReason.LOCAL_STORAGE, DiagnosticReason.PERSISTENCE_FAILED -> {
            DiagnosticAction.RETRY
        }

        DiagnosticReason.KEY_RECOVERY_REQUIRED -> {
            DiagnosticAction.RECOVER_KEY
        }

        DiagnosticReason.INCOMPATIBLE_SERVER -> {
            DiagnosticAction.UPDATE_SERVER
        }

        DiagnosticReason.UNSUPPORTED_FORMAT -> {
            DiagnosticAction.UPDATE_APP
        }

        DiagnosticReason.CONFLICT -> {
            DiagnosticAction.REVIEW_CONFLICT
        }

        DiagnosticReason.SERVER_UNAVAILABLE, DiagnosticReason.RATE_LIMITED -> {
            DiagnosticAction.RETRY
        }

        DiagnosticReason.NONE -> {
            if (outcome == DiagnosticOutcome.INTERRUPTED || outcome == DiagnosticOutcome.RETRY_SCHEDULED) {
                DiagnosticAction.RETRY
            } else {
                DiagnosticAction.NONE
            }
        }

        else -> {
            DiagnosticAction.CONTACT_SUPPORT
        }
    }
