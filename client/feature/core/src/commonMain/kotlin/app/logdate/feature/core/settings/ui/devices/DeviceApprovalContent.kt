@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.connect_device_description
import logdate.client.feature.core.generated.resources.connect_device_error_account_mismatch
import logdate.client.feature.core.generated.resources.connect_device_error_already_used
import logdate.client.feature.core.generated.resources.connect_device_error_camera_denied
import logdate.client.feature.core.generated.resources.connect_device_error_connection_failed
import logdate.client.feature.core.generated.resources.connect_device_error_expired
import logdate.client.feature.core.generated.resources.connect_device_error_keys_missing
import logdate.client.feature.core.generated.resources.connect_device_error_not_a_code
import logdate.client.feature.core.generated.resources.connect_device_error_scan_failed
import logdate.client.feature.core.generated.resources.connect_device_error_server
import logdate.client.feature.core.generated.resources.connect_device_error_signed_out
import logdate.client.feature.core.generated.resources.connect_device_error_wrong_account_or_expired
import logdate.client.feature.core.generated.resources.connect_device_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The scanner action and one connection dialog, including progress and completion. */
@Composable
fun DeviceApprovalContent(
    state: DeviceApprovalUiState,
    onConnectClick: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConnectDeviceCard(onClick = onConnectClick, enabled = state is DeviceApprovalUiState.Idle)
    if (state !is DeviceApprovalUiState.Idle) {
        DeviceConnectionDialog(state, onConnectClick, onApprove, onReject, onDismiss)
    }
}

internal val DeviceApprovalFailure.message: StringResource
    get() =
        when (this) {
            DeviceApprovalFailure.NotAConnectionCode -> Res.string.connect_device_error_not_a_code
            DeviceApprovalFailure.ScanFailed -> Res.string.connect_device_error_scan_failed
            DeviceApprovalFailure.CameraDenied -> Res.string.connect_device_error_camera_denied
            DeviceApprovalFailure.SignedOut -> Res.string.connect_device_error_signed_out
            DeviceApprovalFailure.AccountMismatch -> Res.string.connect_device_error_account_mismatch
            DeviceApprovalFailure.WrongAccountOrExpired -> Res.string.connect_device_error_wrong_account_or_expired
            DeviceApprovalFailure.Expired -> Res.string.connect_device_error_expired
            DeviceApprovalFailure.AlreadyUsed -> Res.string.connect_device_error_already_used
            DeviceApprovalFailure.KeysMissing -> Res.string.connect_device_error_keys_missing
            DeviceApprovalFailure.ConnectionFailed -> Res.string.connect_device_error_connection_failed
            DeviceApprovalFailure.ServerError -> Res.string.connect_device_error_server
        }

/**
 * The scanner action as a single settings row, so it shares the row layout of the device list
 * above it. The page title already names the group, so the row has no section heading.
 */
@Composable
internal fun ConnectDeviceCard(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    MaterialContainer(modifier = Modifier.testTag("connect-device-action")) {
        SettingsNavigationItem(
            title = stringResource(Res.string.connect_device_title),
            description = stringResource(Res.string.connect_device_description),
            icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = onClick,
            enabled = enabled,
        )
    }
}
