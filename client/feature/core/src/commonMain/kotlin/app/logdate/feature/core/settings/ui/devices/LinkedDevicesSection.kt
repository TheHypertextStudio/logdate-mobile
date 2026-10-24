@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.logdate.client.sync.peer.devices.LinkedDevicesFailure
import app.logdate.client.text.asString
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.linked_device_erases_on_next_connect
import logdate.client.feature.core.generated.resources.linked_device_remove_account_changed
import logdate.client.feature.core.generated.resources.linked_device_remove_refused
import logdate.client.feature.core.generated.resources.linked_device_remove_sign_in
import logdate.client.feature.core.generated.resources.linked_device_remove_unexpected
import logdate.client.feature.core.generated.resources.linked_device_remove_unreachable
import logdate.client.feature.core.generated.resources.linked_device_remove_unreadable
import logdate.client.feature.core.generated.resources.linked_device_remove_watch_changed
import logdate.client.feature.core.generated.resources.linked_device_remove_watch_erase
import logdate.client.feature.core.generated.resources.linked_device_remove_watch_forget
import logdate.client.feature.core.generated.resources.linked_device_remove_watch_revoke
import logdate.client.feature.core.generated.resources.linked_device_remove_watch_sync
import logdate.client.feature.core.generated.resources.linked_device_remove_watch_unfinished
import logdate.client.feature.core.generated.resources.linked_device_removing
import logdate.client.feature.core.generated.resources.linked_device_times
import logdate.client.feature.core.generated.resources.linked_devices_error_account_changed
import logdate.client.feature.core.generated.resources.linked_devices_error_refused
import logdate.client.feature.core.generated.resources.linked_devices_error_sign_in
import logdate.client.feature.core.generated.resources.linked_devices_error_unexpected
import logdate.client.feature.core.generated.resources.linked_devices_error_unreachable
import logdate.client.feature.core.generated.resources.linked_devices_error_unreadable
import logdate.client.feature.core.generated.resources.linked_devices_loading
import logdate.client.feature.core.generated.resources.linked_devices_title
import logdate.client.ui.generated.resources.common_dismiss
import logdate.client.ui.generated.resources.common_remove
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

object LinkedDevicesTags {
    const val SECTION = "linked-devices"
    const val DEVICE = "linked-device"
    const val NOTICE = "linked-device-notice"
}

/**
 * The devices linked to the account with their own sessions, each with a working Remove. Nothing is
 * shown while linking is unavailable or no device is linked. The watch paired with this phone is left
 * out when [hidePairedWatch], because the page already shows it as its watch.
 */
@Composable
fun LinkedDevicesSection(
    state: LinkedDevicesUiState,
    hidePairedWatch: Boolean,
    onRemove: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isShown(hidePairedWatch)) return
    val devices = state.shownDevices(hidePairedWatch)
    var confirming by remember { mutableStateOf<LinkedDeviceUiState?>(null) }
    confirming?.let { device ->
        RemoveDeviceDialog(
            deviceName = device.name.asString(),
            onConfirm = {
                confirming = null
                onRemove(device.id)
            },
            onDismiss = { confirming = null },
            erasesData = device.erasesData,
        )
    }
    SettingsSection(title = stringResource(Res.string.linked_devices_title), modifier = modifier.testTag(LinkedDevicesTags.SECTION)) {
        when (state) {
            LinkedDevicesUiState.Unavailable -> {
                Unit
            }

            LinkedDevicesUiState.Loading -> {
                SimpleSettingsItem(title = stringResource(Res.string.linked_devices_loading), description = null, action = {})
            }

            is LinkedDevicesUiState.Failed -> {
                SimpleSettingsItem(title = loadFailureMessage(state.failure), description = null) {
                    TextButton(onClick = onRetry) { Text(stringResource(UiRes.string.common_try_again)) }
                }
            }

            is LinkedDevicesUiState.Listed -> {
                state.notice?.let { notice ->
                    LinkedDeviceNoticeRow(
                        notice = notice,
                        onRetryRemove = { id -> confirming = state.devices.firstOrNull { it.id == id } },
                        onDismiss = onDismissNotice,
                    )
                }
                devices.forEach { device ->
                    LinkedDeviceRow(device, removingId = state.removingId, onRemove = { confirming = device })
                }
            }
        }
    }
}

/** Whether the section has anything to show: a load in progress, its failure, a device or a notice. */
fun LinkedDevicesUiState.isShown(hidePairedWatch: Boolean): Boolean =
    when (this) {
        LinkedDevicesUiState.Unavailable -> false
        LinkedDevicesUiState.Loading, is LinkedDevicesUiState.Failed -> true
        is LinkedDevicesUiState.Listed -> notice != null || shownDevices(hidePairedWatch).isNotEmpty()
    }

private fun LinkedDevicesUiState.shownDevices(hidePairedWatch: Boolean): List<LinkedDeviceUiState> =
    (this as? LinkedDevicesUiState.Listed)?.devices.orEmpty().filterNot { hidePairedWatch && it.pairedWatch }

@Composable
private fun LinkedDeviceRow(
    device: LinkedDeviceUiState,
    removingId: String?,
    onRemove: () -> Unit,
) {
    SettingsRow(
        headlineContent = { Text(device.name.asString()) },
        overlineContent = { Text(stringResource(device.platformName)) },
        supportingContent = {
            Text(stringResource(Res.string.linked_device_times, device.linkedAtFormatted.asString(), device.lastSeenFormatted.asString()))
        },
        leadingContent = { Icon(Icons.Outlined.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            if (removingId == device.id) {
                Text(stringResource(Res.string.linked_device_removing), style = MaterialTheme.typography.labelLarge)
            } else {
                TextButton(onClick = onRemove, enabled = removingId == null) { Text(stringResource(UiRes.string.common_remove)) }
            }
        },
        modifier = Modifier.testTag(LinkedDevicesTags.DEVICE),
    )
}

@Composable
private fun LinkedDeviceNoticeRow(
    notice: LinkedDeviceNotice,
    onRetryRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (notice) {
        is LinkedDeviceNotice.ErasesOnNextConnect -> {
            SettingsRow(
                headlineContent = { Text(stringResource(Res.string.linked_device_erases_on_next_connect, notice.deviceName)) },
                trailingContent = { TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_dismiss)) } },
                modifier = Modifier.testTag(LinkedDevicesTags.NOTICE),
            )
        }

        is LinkedDeviceNotice.RemoveFailed -> {
            SettingsRow(
                headlineContent = { Text(removeFailureMessage(notice.deviceName, notice.failure)) },
                trailingContent = {
                    TextButton(onClick = { onRetryRemove(notice.deviceId) }) { Text(stringResource(UiRes.string.common_try_again)) }
                },
                modifier = Modifier.testTag(LinkedDevicesTags.NOTICE),
            )
        }
    }
}

@Composable
private fun loadFailureMessage(failure: LinkedDevicesFailure): String =
    when (failure) {
        LinkedDevicesFailure.Unreachable -> {
            stringResource(Res.string.linked_devices_error_unreachable)
        }

        LinkedDevicesFailure.SignInRequired -> {
            stringResource(Res.string.linked_devices_error_sign_in)
        }

        is LinkedDevicesFailure.Refused -> {
            stringResource(Res.string.linked_devices_error_refused, failure.status)
        }

        LinkedDevicesFailure.UnreadableAnswer -> {
            stringResource(Res.string.linked_devices_error_unreadable)
        }

        LinkedDevicesFailure.AccountChanged -> {
            stringResource(Res.string.linked_devices_error_account_changed)
        }

        is LinkedDevicesFailure.WatchRemovalStopped, LinkedDevicesFailure.Unexpected -> {
            stringResource(Res.string.linked_devices_error_unexpected)
        }
    }

@Composable
private fun removeFailureMessage(
    name: String,
    failure: LinkedDevicesFailure,
): String =
    when (failure) {
        LinkedDevicesFailure.Unreachable -> {
            stringResource(Res.string.linked_device_remove_unreachable, name)
        }

        LinkedDevicesFailure.SignInRequired -> {
            stringResource(Res.string.linked_device_remove_sign_in, name)
        }

        is LinkedDevicesFailure.Refused -> {
            stringResource(Res.string.linked_device_remove_refused, name, failure.status)
        }

        LinkedDevicesFailure.UnreadableAnswer -> {
            stringResource(Res.string.linked_device_remove_unreadable, name)
        }

        LinkedDevicesFailure.AccountChanged -> {
            stringResource(Res.string.linked_device_remove_account_changed, name)
        }

        LinkedDevicesFailure.Unexpected -> {
            stringResource(Res.string.linked_device_remove_unexpected, name)
        }

        is LinkedDevicesFailure.WatchRemovalStopped -> {
            when (failure.step) {
                "sync" -> stringResource(Res.string.linked_device_remove_watch_sync, name)
                "revoke" -> stringResource(Res.string.linked_device_remove_watch_revoke, name)
                "wipe", "queue wipe" -> stringResource(Res.string.linked_device_remove_watch_erase, name)
                "disassociate", "clear link" -> stringResource(Res.string.linked_device_remove_watch_forget, name)
                "link changed" -> stringResource(Res.string.linked_device_remove_watch_changed, name)
                else -> stringResource(Res.string.linked_device_remove_watch_unfinished, name)
            }
        }
    }
