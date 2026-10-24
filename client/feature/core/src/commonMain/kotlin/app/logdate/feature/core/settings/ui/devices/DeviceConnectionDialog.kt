@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.connect_device_account
import logdate.client.feature.core.generated.resources.connect_device_code
import logdate.client.feature.core.generated.resources.connect_device_confirm
import logdate.client.feature.core.generated.resources.connect_device_confirmation_help
import logdate.client.feature.core.generated.resources.connect_device_confirmation_title
import logdate.client.feature.core.generated.resources.connect_device_connecting
import logdate.client.feature.core.generated.resources.connect_device_done
import logdate.client.feature.core.generated.resources.connect_device_failed_title
import logdate.client.feature.core.generated.resources.connect_device_looking_up
import logdate.client.feature.core.generated.resources.connect_device_reject
import logdate.client.feature.core.generated.resources.connect_device_rejected_body
import logdate.client.feature.core.generated.resources.connect_device_rejected_title
import logdate.client.feature.core.generated.resources.connect_device_rejecting
import logdate.client.feature.core.generated.resources.connect_device_scan_again
import logdate.client.feature.core.generated.resources.connect_device_success_body
import logdate.client.feature.core.generated.resources.connect_device_success_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DeviceConnectionDialog(
    state: DeviceApprovalUiState,
    onScan: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = state is DeviceApprovalUiState.LookingUp || state is DeviceApprovalUiState.Working
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("device-connection-dialog").semantics { liveRegion = LiveRegionMode.Polite },
        icon = {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            } else {
                Icon(
                    imageVector =
                        when (state) {
                            is DeviceApprovalUiState.Done -> if (state.connected) Icons.Outlined.CheckCircle else Icons.Outlined.Close
                            is DeviceApprovalUiState.Failed -> Icons.Outlined.ErrorOutline
                            else -> Icons.Outlined.Devices
                        },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
        },
        title = { Text(connectionTitle(state)) },
        text = { ConnectionDetails(state) },
        confirmButton = {
            when (state) {
                is DeviceApprovalUiState.Confirm -> Button(onClick = onApprove) { Text(stringResource(Res.string.connect_device_confirm)) }
                is DeviceApprovalUiState.Done -> Button(onClick = onDismiss) { Text(stringResource(Res.string.connect_device_done)) }
                is DeviceApprovalUiState.Failed -> Button(onClick = onScan) { Text(stringResource(Res.string.connect_device_scan_again)) }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is DeviceApprovalUiState.Confirm -> {
                    TextButton(
                        onClick = onReject,
                    ) { Text(stringResource(Res.string.connect_device_reject)) }
                }

                is DeviceApprovalUiState.Failed -> {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.connect_device_done)) }
                }

                else -> {
                    Unit
                }
            }
        },
    )
}

@Composable
private fun connectionTitle(state: DeviceApprovalUiState): String =
    when (state) {
        is DeviceApprovalUiState.Confirm -> {
            stringResource(Res.string.connect_device_confirmation_title, state.deviceName)
        }

        DeviceApprovalUiState.LookingUp -> {
            stringResource(Res.string.connect_device_looking_up)
        }

        is DeviceApprovalUiState.Working -> {
            stringResource(
                if (state.connecting) Res.string.connect_device_connecting else Res.string.connect_device_rejecting,
                state.deviceName,
            )
        }

        is DeviceApprovalUiState.Done -> {
            stringResource(if (state.connected) Res.string.connect_device_success_title else Res.string.connect_device_rejected_title)
        }

        is DeviceApprovalUiState.Failed -> {
            stringResource(Res.string.connect_device_failed_title)
        }

        DeviceApprovalUiState.Idle -> {
            ""
        }
    }

@Composable
private fun ConnectionDetails(state: DeviceApprovalUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        when (state) {
            is DeviceApprovalUiState.Confirm -> {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(stringResource(Res.string.connect_device_account, state.accountName))
                        Text(stringResource(Res.string.connect_device_code, state.code), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(stringResource(Res.string.connect_device_confirmation_help))
                state.failure?.let { Text(stringResource(it.message), color = MaterialTheme.colorScheme.error) }
            }

            is DeviceApprovalUiState.Done -> {
                Text(state.deviceName, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        if (state.connected) Res.string.connect_device_success_body else Res.string.connect_device_rejected_body,
                    ),
                )
            }

            is DeviceApprovalUiState.Failed -> {
                Text(stringResource(state.reason.message))
            }

            else -> {
                Unit
            }
        }
    }
}
