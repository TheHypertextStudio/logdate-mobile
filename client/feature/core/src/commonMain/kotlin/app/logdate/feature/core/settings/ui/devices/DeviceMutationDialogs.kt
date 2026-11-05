@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import app.logdate.ui.common.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.device_name
import logdate.client.feature.core.generated.resources.device_state_remove_failed
import logdate.client.feature.core.generated.resources.device_state_rename_failed
import logdate.client.feature.core.generated.resources.device_state_reset_failed
import logdate.client.feature.core.generated.resources.enter_a_new_name_for_this_device
import logdate.client.feature.core.generated.resources.remove_device_confirmation
import logdate.client.feature.core.generated.resources.remove_linked_watch_confirmation
import logdate.client.feature.core.generated.resources.rename
import logdate.client.feature.core.generated.resources.reset
import logdate.client.feature.core.generated.resources.reset_device_id
import logdate.client.feature.core.generated.resources.reset_device_id_confirmation
import logdate.client.feature.core.generated.resources.sync_device_remove_title
import logdate.client.feature.core.generated.resources.sync_device_rename_title
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_remove
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
fun RenameDeviceDialog(
    currentName: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    operation: DeviceOperationState? = null,
) {
    AlertDialog(
        onDismissRequest = { if (operation !is DeviceOperationState.Running) onDismiss() },
        title = { Text(stringResource(Res.string.sync_device_rename_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.enter_a_new_name_for_this_device))
                Spacer(modifier = Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = currentName,
                    enabled = operation !is DeviceOperationState.Running,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(Res.string.device_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DeviceOperationFeedback(operation)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = currentName.isNotBlank() && operation !is DeviceOperationState.Running,
            ) {
                DeviceOperationButtonLabel(operation) { Text(stringResource(Res.string.rename)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = operation !is DeviceOperationState.Running) {
                Text(stringResource(UiRes.string.common_cancel))
            }
        },
    )
}

@Composable
fun RemoveDeviceDialog(
    deviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    operation: DeviceOperationState? = null,
    erasesData: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = { if (operation !is DeviceOperationState.Running) onDismiss() },
        title = { Text(stringResource(Res.string.sync_device_remove_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(if (erasesData) Res.string.remove_linked_watch_confirmation else Res.string.remove_device_confirmation, deviceName))
                DeviceOperationFeedback(operation)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = operation !is DeviceOperationState.Running) {
                DeviceOperationButtonLabel(operation) { Text(stringResource(UiRes.string.common_remove)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = operation !is DeviceOperationState.Running) {
                Text(stringResource(UiRes.string.common_cancel))
            }
        },
    )
}

@Composable
fun ResetDeviceIdDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    operation: DeviceOperationState? = null,
) {
    AlertDialog(
        onDismissRequest = { if (operation !is DeviceOperationState.Running) onDismiss() },
        title = { Text(stringResource(Res.string.reset_device_id)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(Res.string.reset_device_id_confirmation))
                DeviceOperationFeedback(operation)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = operation !is DeviceOperationState.Running) {
                DeviceOperationButtonLabel(operation) { Text(stringResource(Res.string.reset)) }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = operation !is DeviceOperationState.Running) {
                Text(stringResource(UiRes.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun DeviceOperationFeedback(operation: DeviceOperationState?) {
    if (operation is DeviceOperationState.Failed) {
        Text(
            stringResource(
                when (operation.action) {
                    DeviceAction.Rename -> Res.string.device_state_rename_failed
                    DeviceAction.Remove -> Res.string.device_state_remove_failed
                    DeviceAction.ResetIdentity -> Res.string.device_state_reset_failed
                },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DeviceOperationButtonLabel(
    operation: DeviceOperationState?,
    label: @Composable () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        if (operation is DeviceOperationState.Running) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        label()
    }
}
