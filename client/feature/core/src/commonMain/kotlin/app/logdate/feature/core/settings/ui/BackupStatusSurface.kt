@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.client.sync.SyncStatus
import app.logdate.feature.core.sync.AccountSyncStatus
import app.logdate.feature.core.sync.SyncProgressIndicator
import app.logdate.feature.core.sync.messageResource
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.cloud_archive_checking_contents
import logdate.client.feature.core.generated.resources.cloud_archive_encrypting
import logdate.client.feature.core.generated.resources.cloud_archive_exporting
import logdate.client.feature.core.generated.resources.cloud_archive_failed_detail
import logdate.client.feature.core.generated.resources.cloud_archive_retry
import logdate.client.feature.core.generated.resources.cloud_archive_unavailable_detail
import logdate.client.feature.core.generated.resources.cloud_archive_uploading
import logdate.client.feature.core.generated.resources.sync_account_mobile_data
import logdate.client.feature.core.generated.resources.sync_heading_background
import logdate.client.feature.core.generated.resources.sync_heading_failed
import logdate.client.feature.core.generated.resources.sync_heading_offline
import logdate.client.feature.core.generated.resources.sync_heading_storage
import logdate.client.feature.core.generated.resources.sync_heading_wifi
import logdate.client.feature.core.generated.resources.sync_retry_automatic_detail
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BackupStatusItem(
    syncStatus: SyncStatus?,
    cloudArchiveStatus: CloudArchiveStatus,
    onSyncUsingMobileData: () -> Unit = {},
    onArchiveBackupNow: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val status = backupStatus(syncStatus, cloudArchiveStatus)
    val showArchiveRetry = cloudArchiveStatus.needsManualRetry && status == AccountSyncStatus.UNKNOWN
    val archiveProgress = cloudArchiveStatus.progress.takeIf { cloudArchiveStatus.phase == CloudArchivePhase.RUNNING }
    val title =
        when (status) {
            AccountSyncStatus.WAITING_FOR_WIFI -> Res.string.sync_heading_wifi

            AccountSyncStatus.OFFLINE -> Res.string.sync_heading_offline

            AccountSyncStatus.BACKGROUND_RESTRICTED -> Res.string.sync_heading_background

            AccountSyncStatus.STORAGE_FULL -> Res.string.sync_heading_storage

            AccountSyncStatus.UNKNOWN, AccountSyncStatus.SERVER_UNAVAILABLE, AccountSyncStatus.CONNECTION_UNAVAILABLE,
            AccountSyncStatus.DEVICE_ACCESS_REQUIRED, AccountSyncStatus.CONFLICT, AccountSyncStatus.LOCAL_DATA_UNAVAILABLE,
            AccountSyncStatus.MEDIA_TOO_LARGE,
            -> Res.string.sync_heading_failed

            else -> status.messageResource()
        }
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    if (status == AccountSyncStatus.SYNCING) {
                        SyncProgressIndicator(
                            total =
                                if (archiveProgress != null) {
                                    archiveProgress.fraction?.let { 1000 }
                                } else if (syncStatus?.isSyncing == true) {
                                    syncStatus.totalForRun
                                } else {
                                    null
                                },
                            completed =
                                if (archiveProgress != null) {
                                    ((archiveProgress.fraction ?: 0f) * 1000).toInt()
                                } else if (syncStatus?.isSyncing == true) {
                                    syncStatus.completedInRun
                                } else {
                                    0
                                },
                            modifier = Modifier.size(28.dp),
                        )
                    } else {
                        val icon =
                            when (status) {
                                AccountSyncStatus.UP_TO_DATE -> Icons.Rounded.CloudDone
                                AccountSyncStatus.WAITING_FOR_WIFI -> Icons.Rounded.Wifi
                                AccountSyncStatus.OFFLINE -> Icons.Rounded.CloudOff
                                AccountSyncStatus.BACKGROUND_RESTRICTED -> Icons.Rounded.DataSaverOn
                                AccountSyncStatus.WAITING -> Icons.Rounded.Schedule
                                else -> Icons.Rounded.CloudSync
                            }
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    if (archiveProgress != null) {
                        val stage =
                            when (archiveProgress.stage) {
                                CloudArchiveStage.CHECKING -> Res.string.cloud_archive_checking_contents
                                CloudArchiveStage.EXPORTING -> Res.string.cloud_archive_exporting
                                CloudArchiveStage.ENCRYPTING -> Res.string.cloud_archive_encrypting
                                CloudArchiveStage.UPLOADING -> Res.string.cloud_archive_uploading
                            }
                        Text(stringResource(stage), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (showArchiveRetry) {
                        val detail =
                            if (cloudArchiveStatus.phase == CloudArchivePhase.FAILED) {
                                Res.string.cloud_archive_failed_detail
                            } else {
                                Res.string.cloud_archive_unavailable_detail
                            }
                        Text(
                            stringResource(detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (title != status.messageResource()) {
                        val detail =
                            if (title ==
                                Res.string.sync_heading_failed
                            ) {
                                Res.string.sync_retry_automatic_detail
                            } else {
                                status.messageResource()
                            }
                        Text(
                            stringResource(detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (status == AccountSyncStatus.WAITING_FOR_WIFI) {
                FilledTonalButton(
                    onClick = onSyncUsingMobileData,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.sync_account_mobile_data)) }
            }
            if (showArchiveRetry) {
                FilledTonalButton(
                    onClick = onArchiveBackupNow,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.cloud_archive_retry)) }
            }
        }
    }
}
