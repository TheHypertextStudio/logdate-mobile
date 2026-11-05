@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import app.logdate.ui.common.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.platform.rememberSystemReduceMotion
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.diagnostic_reporting_consent_body
import logdate.client.feature.core.generated.resources.diagnostic_reporting_consent_title
import logdate.client.feature.core.generated.resources.diagnostic_reporting_delete
import logdate.client.feature.core.generated.resources.diagnostic_reporting_delete_body
import logdate.client.feature.core.generated.resources.diagnostic_reporting_delete_title
import logdate.client.feature.core.generated.resources.diagnostic_reporting_deleted
import logdate.client.feature.core.generated.resources.diagnostic_reporting_description
import logdate.client.feature.core.generated.resources.diagnostic_reporting_destination
import logdate.client.feature.core.generated.resources.diagnostic_reporting_logs_notice
import logdate.client.feature.core.generated.resources.diagnostic_reporting_pending
import logdate.client.feature.core.generated.resources.diagnostic_reporting_title
import logdate.client.feature.core.generated.resources.diagnostic_reporting_unavailable
import logdate.client.feature.core.generated.resources.diagnostic_state_check
import logdate.client.feature.core.generated.resources.diagnostic_state_delete_failed
import logdate.client.feature.core.generated.resources.diagnostic_state_delete_prepare_failed
import logdate.client.feature.core.generated.resources.diagnostic_state_delete_preparing
import logdate.client.feature.core.generated.resources.diagnostic_state_deleting
import logdate.client.feature.core.generated.resources.diagnostic_state_disable_failed
import logdate.client.feature.core.generated.resources.diagnostic_state_enable_failed
import logdate.client.feature.core.generated.resources.diagnostic_state_loading
import logdate.client.feature.core.generated.resources.diagnostic_state_read_failed
import logdate.client.feature.core.generated.resources.diagnostic_state_toggle_prepare_failed
import logdate.client.feature.core.generated.resources.diagnostic_state_toggle_preparing
import logdate.client.feature.core.generated.resources.diagnostic_state_toggle_saving
import logdate.client.feature.core.generated.resources.settings_retry_action
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_confirm
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun AutomaticDiagnosticReportingSection(viewModel: AutomaticDiagnosticReportingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AutomaticDiagnosticReportingContent(
        state,
        viewModel::refresh,
        viewModel::setEnabled,
        viewModel::retryToggle,
        viewModel::requestDelete,
        viewModel::retryDelete,
        viewModel::confirmToggle,
        viewModel::cancelToggleConsent,
        viewModel::confirmDelete,
        viewModel::cancelDeleteConsent,
    )
}

@Composable
internal fun AutomaticDiagnosticReportingContent(
    state: AutomaticDiagnosticReportingState,
    onRefresh: () -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onRetryToggle: () -> Unit,
    onRequestDelete: () -> Unit,
    onRetryDelete: () -> Unit,
    onConfirmToggle: () -> Unit,
    onCancelToggle: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    val reduceMotion by rememberSystemReduceMotion()
    SettingsSection(title = stringResource(Res.string.diagnostic_reporting_title)) {
        Column(
            Modifier.padding(16.dp).animateContentSize(tween(if (reduceMotion) 0 else 160)),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.availability) {
                is ReportingAvailability.Loading -> Text(stringResource(Res.string.diagnostic_state_loading))
                is ReportingAvailability.Unavailable ->
                    ReportingFailure(
                        stringResource(Res.string.diagnostic_state_read_failed),
                        !state.isBusy,
                        onRefresh,
                    )
                ReportingAvailability.NotLoaded ->
                    TextButton(
                        onClick = onRefresh,
                    ) { Text(stringResource(Res.string.diagnostic_state_check)) }
                is ReportingAvailability.Available -> Unit
            }
            state.settings?.let { settings ->
                settings.destination?.let { Text(stringResource(Res.string.diagnostic_reporting_destination, it)) }
                if (settings.available || settings.enabled) {
                    ToggleSettingsItem(
                        title = stringResource(Res.string.diagnostic_reporting_title),
                        description = stringResource(Res.string.diagnostic_reporting_description),
                        checked = settings.enabled,
                        enabled = !state.isBusy,
                        onCheckedChange = onSetEnabled,
                    )
                    when (val toggle = state.toggle) {
                        ReportingToggleOperation.Preparing -> Text(stringResource(Res.string.diagnostic_state_toggle_preparing))
                        is ReportingToggleOperation.Saving -> Text(stringResource(Res.string.diagnostic_state_toggle_saving))
                        is ReportingToggleOperation.Failed -> {
                            val message =
                                when (toggle.reason) {
                                    ReportingToggleFailure.PREPARE -> Res.string.diagnostic_state_toggle_prepare_failed
                                    ReportingToggleFailure.ENABLE -> Res.string.diagnostic_state_enable_failed
                                    ReportingToggleFailure.DISABLE -> Res.string.diagnostic_state_disable_failed
                                }
                            ReportingFailure(stringResource(message), !state.isBusy, onRetryToggle)
                        }
                        else -> Unit
                    }
                    if (settings.pendingReports > 0) Text(stringResource(Res.string.diagnostic_reporting_pending, settings.pendingReports))
                    TextButton(enabled = settings.available && !state.isBusy, onClick = onRequestDelete) {
                        val label =
                            when (state.deletion) {
                                ReportingDeleteOperation.Preparing -> Res.string.diagnostic_state_delete_preparing
                                ReportingDeleteOperation.Deleting -> Res.string.diagnostic_state_deleting
                                else -> Res.string.diagnostic_reporting_delete
                            }
                        Text(stringResource(label))
                    }
                    when (val deletion = state.deletion) {
                        is ReportingDeleteOperation.Failed ->
                            ReportingFailure(
                                stringResource(
                                    if (deletion.reason ==
                                        ReportingDeleteFailure.PREPARE
                                    ) {
                                        Res.string.diagnostic_state_delete_prepare_failed
                                    } else {
                                        Res.string.diagnostic_state_delete_failed
                                    },
                                ),
                                !state.isBusy,
                                onRetryDelete,
                            )
                        ReportingDeleteOperation.Deleted -> Text(stringResource(Res.string.diagnostic_reporting_deleted))
                        else -> Unit
                    }
                } else {
                    Text(stringResource(Res.string.diagnostic_reporting_unavailable))
                }
            }
            Text(stringResource(Res.string.diagnostic_reporting_logs_notice))
        }
    }
    val toggle = state.toggle as? ReportingToggleOperation.AwaitingConsent
    val deletion = state.deletion as? ReportingDeleteOperation.AwaitingConsent
    when {
        toggle != null -> ReportingConsentDialog(toggle.approval.destination, false, onConfirmToggle, onCancelToggle)
        deletion != null -> ReportingConsentDialog(deletion.approval.destination, true, onConfirmDelete, onCancelDelete)
    }
}

@Composable
private fun ReportingFailure(
    message: String,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry, enabled = enabled) { Text(stringResource(Res.string.settings_retry_action)) }
    }
}

@Composable
private fun ReportingConsentDialog(
    destination: String,
    delete: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (delete) Res.string.diagnostic_reporting_delete_title else Res.string.diagnostic_reporting_consent_title),
            )
        },
        text = {
            Text(
                stringResource(
                    if (delete) Res.string.diagnostic_reporting_delete_body else Res.string.diagnostic_reporting_consent_body,
                    destination,
                ),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(UiRes.string.common_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_cancel)) } },
    )
}
