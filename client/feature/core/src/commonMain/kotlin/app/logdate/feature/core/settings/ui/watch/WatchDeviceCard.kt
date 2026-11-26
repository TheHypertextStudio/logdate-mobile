@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_app_not_installed
import logdate.client.feature.core.generated.resources.watch_association_pending
import logdate.client.feature.core.generated.resources.watch_attention_not_sent
import logdate.client.feature.core.generated.resources.watch_attention_set_up_again
import logdate.client.feature.core.generated.resources.watch_connected
import logdate.client.feature.core.generated.resources.watch_discovery_unavailable
import logdate.client.feature.core.generated.resources.watch_installation_unknown
import logdate.client.feature.core.generated.resources.watch_loading
import logdate.client.feature.core.generated.resources.watch_needs_association
import logdate.client.feature.core.generated.resources.watch_no_paired
import logdate.client.feature.core.generated.resources.watch_out_of_range
import logdate.client.feature.core.generated.resources.watch_settings
import logdate.client.feature.core.generated.resources.watch_setup_update_phone_title
import logdate.client.feature.core.generated.resources.watch_setup_update_watch_title
import logdate.client.feature.core.generated.resources.watch_sync_pending
import logdate.client.feature.core.generated.resources.watch_sync_up_to_date
import logdate.client.feature.core.generated.resources.watch_unsupported
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WatchDeviceCard(
    status: WatchStatus?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    deviceName: String? = null,
) {
    MaterialContainer(modifier = modifier) {
        WatchDeviceRow(status, onClick, deviceName)
    }
}

/**
 * The watch as one row of a list that already provides the container, such as the Devices list,
 * with its status as the single supporting line. A list of devices passes its own platform
 * [overline] and [icon] so the watch reads like the rows around it.
 */
@Composable
internal fun WatchDeviceRow(
    status: WatchStatus?,
    onClick: () -> Unit,
    deviceName: String? = null,
    overline: String? = null,
    icon: @Composable () -> Unit = { Icon(Icons.Default.Watch, contentDescription = null) },
) {
    SettingsNavigationItem(
        title = deviceName ?: stringResource(Res.string.watch_settings),
        description = watchDeviceDescription(status),
        icon = icon,
        onClick = onClick,
        overline = overline,
    )
}

@Composable
internal fun watchDeviceDescription(status: WatchStatus?): String =
    when (status) {
        null -> {
            stringResource(Res.string.watch_loading)
        }

        is WatchStatus.Unknown -> {
            stringResource(
                if (status.watchName == null) {
                    Res.string.watch_discovery_unavailable
                } else {
                    Res.string.watch_installation_unknown
                },
            )
        }

        WatchStatus.NoWatch -> {
            stringResource(Res.string.watch_no_paired)
        }

        WatchStatus.Unsupported -> {
            stringResource(Res.string.watch_unsupported)
        }

        is WatchStatus.NotInstalled -> {
            stringResource(Res.string.watch_app_not_installed)
        }

        is WatchStatus.SettingUp -> {
            when (val step = status.step) {
                is WatchSetupStep.PairWithPhone -> stringResource(Res.string.watch_needs_association)
                is WatchSetupStep.ConfirmOnPhone -> stringResource(Res.string.watch_association_pending)
                is WatchSetupStep.Linking -> watchLinkingCopy(step).title
            }
        }

        is WatchStatus.NeedsAttention -> {
            when (val issue = status.issue) {
                WatchIssue.SetUpAgain -> stringResource(Res.string.watch_attention_set_up_again)
                is WatchIssue.CapturesNotSent -> stringResource(Res.string.watch_attention_not_sent, issue.count)
                is WatchIssue.WatchUpdateRequired -> stringResource(Res.string.watch_setup_update_watch_title, status.watchName)
                is WatchIssue.PhoneUpdateRequired -> stringResource(Res.string.watch_setup_update_phone_title)
            }
        }

        is WatchStatus.Ready -> {
            when {
                !status.nearby -> stringResource(Res.string.watch_out_of_range)
                status.sync == null -> stringResource(Res.string.watch_connected)
                status.sync.waiting > 0 -> stringResource(Res.string.watch_sync_pending, status.sync.waiting)
                else -> stringResource(Res.string.watch_sync_up_to_date)
            }
        }
    }
