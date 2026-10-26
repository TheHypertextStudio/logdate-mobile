@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.account_sign_in_to_enable_sync
import logdate.client.feature.core.generated.resources.create_account
import logdate.client.feature.core.generated.resources.sign_in
import logdate.client.feature.core.generated.resources.sync_and_backup
import logdate.client.feature.core.generated.resources.sync_feature_access
import logdate.client.feature.core.generated.resources.sync_feature_backup
import logdate.client.feature.core.generated.resources.sync_feature_sync
import org.jetbrains.compose.resources.stringResource

@Composable
private fun SyncFeatureRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun SyncSettingsSection(
    syncStatus: app.logdate.client.sync.SyncStatus?,
    cloudArchiveStatus: CloudArchiveStatus,
    isAuthenticated: Boolean,
    onSyncUsingMobileData: () -> Unit = {},
    onArchiveBackupNow: () -> Unit = {},
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SettingsSection(
        title = stringResource(Res.string.sync_and_backup),
        modifier = modifier,
    ) {
        if (!isAuthenticated) {
            Column(
                modifier = Modifier.padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(Res.string.account_sign_in_to_enable_sync),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                SyncFeatureRow(
                    icon = Icons.Rounded.CloudDone,
                    text = stringResource(Res.string.sync_feature_backup),
                )
                SyncFeatureRow(
                    icon = Icons.Rounded.Devices,
                    text = stringResource(Res.string.sync_feature_access),
                )
                SyncFeatureRow(
                    icon = Icons.Rounded.Sync,
                    text = stringResource(Res.string.sync_feature_sync),
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Button(onClick = onNavigateToCloudAccountCreation) {
                        Text(stringResource(Res.string.create_account))
                    }
                    OutlinedButton(onClick = onNavigateToSignIn) {
                        Text(stringResource(Res.string.sign_in))
                    }
                }
            }
        } else {
            Column {
                BackupStatusItem(
                    syncStatus,
                    cloudArchiveStatus,
                    onSyncUsingMobileData = onSyncUsingMobileData,
                    onArchiveBackupNow = onArchiveBackupNow,
                )
            }
        }
    }
}
