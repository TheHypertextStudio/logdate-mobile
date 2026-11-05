package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.diagnostics.DiagnosticConsent
import app.logdate.client.sync.diagnostics.DiagnosticReportingController
import app.logdate.client.sync.diagnostics.DiagnosticReportingSettings
import kotlinx.coroutines.flow.StateFlow

/** An opaque controller-issued capability; the ViewModel never creates or retargets consent. */
internal interface DiagnosticReportingApproval {
    val destination: String
}

internal interface DiagnosticReportingActions {
    val settings: StateFlow<DiagnosticReportingSettings>

    suspend fun refresh()

    suspend fun prepareConsent(): DiagnosticReportingApproval?

    suspend fun enable(approval: DiagnosticReportingApproval): Boolean

    suspend fun disable(): Boolean

    suspend fun delete(approval: DiagnosticReportingApproval): Boolean
}

internal class ControllerDiagnosticReportingActions(
    private val controller: DiagnosticReportingController,
) : DiagnosticReportingActions {
    override val settings get() = controller.state

    override suspend fun refresh() = controller.refresh()

    override suspend fun prepareConsent(): DiagnosticReportingApproval? = controller.prepareConsent()?.let { Approval(it) }

    override suspend fun enable(approval: DiagnosticReportingApproval): Boolean {
        val issued = approval as? Approval ?: return false
        return controller.enable(issued.consent)
    }

    override suspend fun disable() = controller.disable()

    override suspend fun delete(approval: DiagnosticReportingApproval): Boolean {
        val issued = approval as? Approval ?: return false
        return controller.deleteUploadedReports(issued.consent)
    }

    private class Approval(
        val consent: DiagnosticConsent,
    ) : DiagnosticReportingApproval {
        override val destination get() = consent.destination
    }
}
