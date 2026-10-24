@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.device.restore.NativeRestoreCustodyStatus
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.native_backup_cloud_detail
import logdate.client.feature.core.generated.resources.native_backup_cloud_requirement
import logdate.client.feature.core.generated.resources.native_backup_cloud_title
import logdate.client.feature.core.generated.resources.native_backup_cloud_waiting
import logdate.client.feature.core.generated.resources.native_backup_quota_failure
import logdate.client.feature.core.generated.resources.native_backup_recovering_detail
import logdate.client.feature.core.generated.resources.native_backup_recovering_title
import logdate.client.feature.core.generated.resources.native_backup_recovery_detail
import logdate.client.feature.core.generated.resources.native_backup_recovery_title
import logdate.client.feature.core.generated.resources.native_backup_title
import logdate.client.feature.core.generated.resources.native_backup_transfer_detail
import logdate.client.feature.core.generated.resources.native_backup_transfer_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NativeBackupSettingsSection(
    status: NativeBackupStatus,
    modifier: Modifier = Modifier,
    onRetryRecovery: () -> Unit = {},
    onOpenArchive: () -> Unit = {},
) {
    if (status !is NativeBackupStatus.Available) return
    val cloudDescription =
        listOfNotNull(
            stringResource(Res.string.native_backup_cloud_detail),
            stringResource(Res.string.native_backup_cloud_requirement),
            if (status.lastQuotaFailure != null) stringResource(Res.string.native_backup_quota_failure) else null,
            if (!status.cloudBackupReady || status.custodyStatus != NativeRestoreCustodyStatus.CLEAR) {
                stringResource(Res.string.native_backup_cloud_waiting)
            } else {
                null
            },
        ).joinToString("\n")
    SettingsSection(stringResource(Res.string.native_backup_title), modifier) {
        SimpleSettingsItem(
            title = stringResource(Res.string.native_backup_cloud_title),
            leadingContent = { Icon(Icons.Rounded.CloudQueue, contentDescription = null) },
            description = cloudDescription,
            onClick = null,
            action = {},
        )
        SimpleSettingsItem(
            title = stringResource(Res.string.native_backup_transfer_title),
            leadingContent = { Icon(Icons.Rounded.Devices, contentDescription = null) },
            description = stringResource(Res.string.native_backup_transfer_detail),
            onClick = null,
            action = {},
        )
        when (status.custodyStatus) {
            NativeRestoreCustodyStatus.CLEAR -> {
                Unit
            }

            NativeRestoreCustodyStatus.RECOVERING -> {
                SimpleSettingsItem(
                    title = stringResource(Res.string.native_backup_recovering_title),
                    description = stringResource(Res.string.native_backup_recovering_detail),
                    onClick = onRetryRecovery,
                    action = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                )
            }

            NativeRestoreCustodyStatus.NEEDS_TRANSFER -> {
                SettingsNavigationItem(
                    title = stringResource(Res.string.native_backup_recovery_title),
                    description = stringResource(Res.string.native_backup_recovery_detail),
                    onClick = onOpenArchive,
                )
            }
        }
    }
}
