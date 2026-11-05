package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.diagnostics.DiagnosticReportingSettings

internal sealed interface ReportingAvailability {
    val settings: DiagnosticReportingSettings?

    data object NotLoaded : ReportingAvailability {
        override val settings: DiagnosticReportingSettings? = null
    }

    data class Loading(
        override val settings: DiagnosticReportingSettings? = null,
    ) : ReportingAvailability

    data class Available(
        override val settings: DiagnosticReportingSettings,
    ) : ReportingAvailability

    data class Unavailable(
        override val settings: DiagnosticReportingSettings? = null,
    ) : ReportingAvailability
}

internal enum class ReportingToggleFailure { PREPARE, ENABLE, DISABLE }

internal enum class ReportingDeleteFailure { PREPARE, DELETE }

internal sealed interface ReportingToggleOperation {
    data object Idle : ReportingToggleOperation

    data object Preparing : ReportingToggleOperation

    data class AwaitingConsent(
        val approval: DiagnosticReportingApproval,
    ) : ReportingToggleOperation

    data class Saving(
        val enabled: Boolean,
    ) : ReportingToggleOperation

    data class Failed(
        val requested: Boolean,
        val reason: ReportingToggleFailure,
    ) : ReportingToggleOperation
}

internal sealed interface ReportingDeleteOperation {
    data object Idle : ReportingDeleteOperation

    data object Preparing : ReportingDeleteOperation

    data class AwaitingConsent(
        val approval: DiagnosticReportingApproval,
    ) : ReportingDeleteOperation

    data object Deleting : ReportingDeleteOperation

    data object Deleted : ReportingDeleteOperation

    data class Failed(
        val reason: ReportingDeleteFailure,
    ) : ReportingDeleteOperation
}

internal data class AutomaticDiagnosticReportingState(
    val availability: ReportingAvailability = ReportingAvailability.Loading(),
    val toggle: ReportingToggleOperation = ReportingToggleOperation.Idle,
    val deletion: ReportingDeleteOperation = ReportingDeleteOperation.Idle,
) {
    val settings get() = availability.settings
    val isTogglePending get() =
        toggle is ReportingToggleOperation.Preparing ||
            toggle is ReportingToggleOperation.AwaitingConsent ||
            toggle is ReportingToggleOperation.Saving
    val isDeletePending get() =
        deletion is ReportingDeleteOperation.Preparing ||
            deletion is ReportingDeleteOperation.AwaitingConsent ||
            deletion is ReportingDeleteOperation.Deleting

    // Both mutate consent or operate under its privacy epoch; do not run conflicting requests together.
    val isBusy get() = availability is ReportingAvailability.Loading || isTogglePending || isDeletePending
}
