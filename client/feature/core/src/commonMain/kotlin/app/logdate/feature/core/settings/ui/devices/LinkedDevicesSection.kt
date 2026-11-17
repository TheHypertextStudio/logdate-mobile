@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.logdate.client.sync.peer.devices.LinkedDevicesFailure
import app.logdate.client.text.asString
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.feature.core.settings.ui.watch.watchDeviceDescription
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.last_active_label
import logdate.client.feature.core.generated.resources.linked_device_cleared_unknown
import logdate.client.feature.core.generated.resources.linked_device_cleared_unsent
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
import logdate.client.feature.core.generated.resources.linked_device_remove_watch_unfinished
import logdate.client.feature.core.generated.resources.linked_device_removing
import logdate.client.feature.core.generated.resources.linked_device_watch_settings
import logdate.client.feature.core.generated.resources.linked_devices_error_account_changed
import logdate.client.feature.core.generated.resources.linked_devices_error_refused
import logdate.client.feature.core.generated.resources.linked_devices_error_sign_in
import logdate.client.feature.core.generated.resources.linked_devices_error_unexpected
import logdate.client.feature.core.generated.resources.linked_devices_error_unreachable
import logdate.client.feature.core.generated.resources.linked_devices_error_unreadable
import logdate.client.feature.core.generated.resources.linked_devices_loading
import logdate.client.feature.core.generated.resources.sync_device_remove_label
import logdate.client.feature.core.generated.resources.watch_v2_remove
import logdate.client.ui.generated.resources.common_dismiss
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

object LinkedDevicesTags {
    const val SECTION = "linked-devices"
    const val DEVICE = "linked-device"
    const val NOTICE = "linked-device-notice"
}

/**
 * The devices linked to the account with their own sessions, as rows of the Devices list. Each row
 * is emitted directly so the list's container spaces and rounds it like every other row. Nothing is
 * emitted while linking is unavailable or no device is linked.
 *
 * A row reads like an account device: its platform, its name, and one status line. Its options menu
 * removes the device through [onRemove], which asks for confirmation first; a paired watch's menu
 * also opens its settings. Outcomes of a removal are reported below the list by
 * [LinkedDeviceNoticeMessage], not as rows.
 */
@Composable
fun LinkedDeviceRows(
    state: LinkedDevicesUiState,
    onRemove: (LinkedDeviceUiState) -> Unit,
    onRetry: () -> Unit,
    onManagePairedWatch: (() -> Unit)? = null,
    pairedWatchStatus: WatchStatus? = null,
) {
    when (state) {
        LinkedDevicesUiState.Unavailable -> {}

        LinkedDevicesUiState.Loading -> {
            SimpleSettingsItem(title = stringResource(Res.string.linked_devices_loading), description = null, action = {})
        }

        is LinkedDevicesUiState.Failed -> {
            SimpleSettingsItem(title = loadFailureMessage(state.failure), description = null) {
                TextButton(onClick = onRetry) { Text(stringResource(UiRes.string.common_try_again)) }
            }
        }

        is LinkedDevicesUiState.Listed -> {
            state.devices.forEach { device ->
                LinkedDeviceRow(
                    device,
                    removing = state.removingId == device.id,
                    removeEnabled = state.removingId == null,
                    onRemove = { onRemove(device) },
                    onManage = onManagePairedWatch?.takeIf { device.pairedWatch },
                    status =
                        pairedWatchStatus?.takeIf {
                            device.pairedWatch &&
                                device.pairedWatchNodeId != null &&
                                it.nodeId == device.pairedWatchNodeId
                        },
                )
            }
        }
    }
}

/** Whether the list has anything to show for linked devices: a load in progress, its failure, a device or a notice. */
fun LinkedDevicesUiState.isShown(): Boolean =
    when (this) {
        LinkedDevicesUiState.Unavailable -> false
        LinkedDevicesUiState.Loading, is LinkedDevicesUiState.Failed -> true
        is LinkedDevicesUiState.Listed -> notice != null || devices.isNotEmpty()
    }

/**
 * What the last removal needs to tell the person, shown below the Devices list: a device that erases
 * its data the next time it connects, or unsent work that was cleared with a removed watch, which can
 * be dismissed, or a removal that failed, which can be tried again.
 */
@Composable
fun LinkedDeviceNoticeMessage(
    notice: LinkedDeviceNotice,
    onRetryRemove: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(LinkedDevicesTags.NOTICE), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        when (notice) {
            is LinkedDeviceNotice.ErasesOnNextConnect -> {
                Text(
                    text = stringResource(Res.string.linked_device_erases_on_next_connect, notice.deviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_dismiss)) }
            }

            is LinkedDeviceNotice.ClearedUnsent -> {
                Text(
                    text =
                        notice.count?.let { pluralStringResource(Res.plurals.linked_device_cleared_unsent, it, it, notice.deviceName) }
                            ?: stringResource(Res.string.linked_device_cleared_unknown, notice.deviceName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_dismiss)) }
            }

            is LinkedDeviceNotice.RemoveFailed -> {
                Text(
                    text = removeFailureMessage(notice.deviceName, notice.failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = { onRetryRemove(notice.deviceId) }) { Text(stringResource(UiRes.string.common_try_again)) }
            }
        }
    }
}

@Composable
private fun LinkedDeviceRow(
    device: LinkedDeviceUiState,
    removing: Boolean,
    removeEnabled: Boolean,
    onRemove: () -> Unit,
    onManage: (() -> Unit)?,
    status: WatchStatus?,
) {
    SettingsRow(
        overlineContent = { Text(stringResource(device.platformName)) },
        headlineContent = { Text(device.name.asString()) },
        supportingContent = {
            Text(
                when {
                    removing -> stringResource(Res.string.linked_device_removing)
                    status != null -> watchDeviceDescription(status)
                    else -> stringResource(Res.string.last_active_label, device.lastSeenFormatted.asString())
                },
            )
        },
        leadingContent = { Icon(Icons.Outlined.Watch, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            if (removing) {
                DeviceActionProgress()
            } else {
                DeviceOptionsMenu { close ->
                    onManage?.let { manage ->
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.linked_device_watch_settings)) },
                            onClick = {
                                close()
                                manage()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (device.erasesData) Res.string.watch_v2_remove else Res.string.sync_device_remove_label))
                        },
                        enabled = removeEnabled,
                        onClick = {
                            close()
                            onRemove()
                        },
                    )
                }
            }
        },
        modifier = Modifier.testTag(LinkedDevicesTags.DEVICE),
    )
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
                "revoke" -> stringResource(Res.string.linked_device_remove_watch_revoke, name)
                "wipe", "queue wipe" -> stringResource(Res.string.linked_device_remove_watch_erase, name)
                "disassociate", "clear link" -> stringResource(Res.string.linked_device_remove_watch_forget, name)
                "link changed" -> stringResource(Res.string.linked_device_remove_watch_changed, name)
                else -> stringResource(Res.string.linked_device_remove_watch_unfinished, name)
            }
        }
    }
