@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.device.restore.NativeRestoreCustodyStatus
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(stringResource(Res.string.native_backup_title), style = MaterialTheme.typography.titleSmall)
        NativeBackupExplanation(
            title = stringResource(Res.string.native_backup_cloud_title),
            description = cloudDescription,
            icon = Icons.Rounded.CloudQueue,
        )
        NativeBackupExplanation(
            title = stringResource(Res.string.native_backup_transfer_title),
            description = stringResource(Res.string.native_backup_transfer_detail),
            icon = Icons.Rounded.Devices,
        )
        when (status.custodyStatus) {
            NativeRestoreCustodyStatus.CLEAR -> {
                Unit
            }

            NativeRestoreCustodyStatus.RECOVERING -> {
                MaterialContainer {
                    SimpleSettingsItem(
                        title = stringResource(Res.string.native_backup_recovering_title),
                        description = stringResource(Res.string.native_backup_recovering_detail),
                        onClick = onRetryRecovery,
                        action = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    )
                }
            }

            NativeRestoreCustodyStatus.NEEDS_TRANSFER -> {
                MaterialContainer {
                    SettingsNavigationItem(
                        title = stringResource(Res.string.native_backup_recovery_title),
                        description = stringResource(Res.string.native_backup_recovery_detail),
                        onClick = onOpenArchive,
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeBackupExplanation(
    title: String,
    description: String,
    icon: ImageVector,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
