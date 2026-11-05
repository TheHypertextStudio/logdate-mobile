@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DataSaverOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import app.logdate.ui.common.MaterialCard
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.backup_cloud_title
import logdate.client.feature.core.generated.resources.backup_overview_none
import logdate.client.feature.core.generated.resources.backup_overview_now
import logdate.client.feature.core.generated.resources.backup_overview_saved
import logdate.client.feature.core.generated.resources.cloud_archive_checking_contents
import logdate.client.feature.core.generated.resources.cloud_archive_encrypting
import logdate.client.feature.core.generated.resources.cloud_archive_exporting
import logdate.client.feature.core.generated.resources.cloud_archive_failed_detail
import logdate.client.feature.core.generated.resources.cloud_archive_unavailable_detail
import logdate.client.feature.core.generated.resources.cloud_archive_uploading
import logdate.client.feature.core.generated.resources.journal_storage_enter_recovery_phrase
import logdate.client.feature.core.generated.resources.data_state_archive_request_failed
import logdate.client.feature.core.generated.resources.data_state_requesting_archive
import logdate.client.feature.core.generated.resources.data_state_requesting_sync
import logdate.client.feature.core.generated.resources.data_state_sync_request_failed
import logdate.client.feature.core.generated.resources.sync_account_mobile_data
import logdate.client.feature.core.generated.resources.sync_account_storage_unsupported_detail
import logdate.client.feature.core.generated.resources.sync_account_update_app
import logdate.client.feature.core.generated.resources.sync_heading_background
import logdate.client.feature.core.generated.resources.sync_heading_failed
import logdate.client.feature.core.generated.resources.sync_heading_offline
import logdate.client.feature.core.generated.resources.sync_heading_storage
import logdate.client.feature.core.generated.resources.sync_heading_update
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
    onUpdateApp: (() -> Unit)? = null,
    onEnterRecoveryPhrase: (() -> Unit)? = null,
    reviewableIssueCount: Int = 0,
    syncRequest: SyncRequestState = SyncRequestState.Idle,
    archiveRequest: ArchiveRequestState = ArchiveRequestState.Idle,
) {
    val status = backupStatus(syncStatus, cloudArchiveStatus)
    val showArchiveRetry = cloudArchiveStatus.needsManualRetry && status == AccountSyncStatus.UNKNOWN
    val archiveProgress = cloudArchiveStatus.progress.takeIf { cloudArchiveStatus.phase == CloudArchivePhase.RUNNING }
    val noSavedBackup = cloudArchiveStatus.phase == CloudArchivePhase.NEVER_BACKED_UP && status == AccountSyncStatus.CHECKING
    val savedBackupAvailable =
        cloudArchiveStatus.phase == CloudArchivePhase.COMPLETE &&
            cloudArchiveStatus.lastCompletedAt != null &&
            status in setOf(AccountSyncStatus.CHECKING, AccountSyncStatus.WAITING, AccountSyncStatus.UP_TO_DATE)
    val title =
        if (noSavedBackup) {
            Res.string.backup_overview_none
        } else if (savedBackupAvailable) {
            Res.string.backup_overview_saved
        } else {
            when (status) {
                AccountSyncStatus.WAITING_FOR_WIFI -> Res.string.sync_heading_wifi

                AccountSyncStatus.OFFLINE -> Res.string.sync_heading_offline

                AccountSyncStatus.BACKGROUND_RESTRICTED -> Res.string.sync_heading_background

                AccountSyncStatus.STORAGE_FULL -> Res.string.sync_heading_storage
                AccountSyncStatus.ACCOUNT_STORAGE_UNSUPPORTED -> Res.string.sync_heading_update
                AccountSyncStatus.UNKNOWN, AccountSyncStatus.SERVER_UNAVAILABLE, AccountSyncStatus.CONNECTION_UNAVAILABLE,
                AccountSyncStatus.DEVICE_ACCESS_REQUIRED, AccountSyncStatus.CONFLICT, AccountSyncStatus.LOCAL_DATA_UNAVAILABLE,
                AccountSyncStatus.MEDIA_TOO_LARGE,
                -> Res.string.sync_heading_failed

                else -> status.messageResource()
            }
        }
    MaterialCard(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        title = stringResource(Res.string.backup_cloud_title),
        titleStyle = MaterialTheme.typography.titleLarge,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                            if (savedBackupAvailable) {
                                Icons.Rounded.CloudDone
                            } else {
                                when (status) {
                                    AccountSyncStatus.UP_TO_DATE -> Icons.Rounded.CloudDone
                                    AccountSyncStatus.RECOVERING -> Icons.Rounded.CloudSync
                                    AccountSyncStatus.WAITING_FOR_WIFI -> Icons.Rounded.Wifi
                                    AccountSyncStatus.OFFLINE -> Icons.Rounded.CloudOff
                                    AccountSyncStatus.BACKGROUND_RESTRICTED -> Icons.Rounded.DataSaverOn
                                    AccountSyncStatus.WAITING -> Icons.Rounded.Schedule
                                    AccountSyncStatus.ACCOUNT_STORAGE_UNSUPPORTED -> Icons.Rounded.SystemUpdate
                                    else -> Icons.Rounded.CloudSync
                                }
                            }
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(24.dp))
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
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else if (title != status.messageResource() && !noSavedBackup && !savedBackupAvailable) {
                        val detail =
                            when {
                                status == AccountSyncStatus.ACCOUNT_STORAGE_UNSUPPORTED ->
                                    Res.string.sync_account_storage_unsupported_detail
                                status == AccountSyncStatus.UNKNOWN -> Res.string.sync_retry_automatic_detail
                                else -> status.messageResource()
                            }
                        Text(
                            stringResource(detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (syncRequest == SyncRequestState.Failed) {
                Text(stringResource(Res.string.data_state_sync_request_failed), color = MaterialTheme.colorScheme.error)
            }
            if (archiveRequest == ArchiveRequestState.Failed) {
                Text(stringResource(Res.string.data_state_archive_request_failed), color = MaterialTheme.colorScheme.error)
            }
            if (status == AccountSyncStatus.WAITING_FOR_WIFI) {
                FilledTonalButton(
                    onClick = onSyncUsingMobileData,
                    enabled = syncRequest != SyncRequestState.Requesting,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    val label =
                        if (syncRequest == SyncRequestState.Requesting) {
                            Res.string.data_state_requesting_sync
                        } else {
                            Res.string.sync_account_mobile_data
                        }
                    Text(stringResource(label))
                }
            }
            val phraseRecoveryAvailable =
                status == AccountSyncStatus.KEY_RECOVERY_REQUIRED || status == AccountSyncStatus.DEVICE_ACCESS_REQUIRED
            if (phraseRecoveryAvailable && onEnterRecoveryPhrase != null) {
                Button(
                    onClick = onEnterRecoveryPhrase,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.journal_storage_enter_recovery_phrase)) }
            }
            if (status == AccountSyncStatus.ACCOUNT_STORAGE_UNSUPPORTED && onUpdateApp != null) {
                Button(
                    onClick = onUpdateApp,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(Res.string.sync_account_update_app)) }
            }
            if (backupOverviewState(syncStatus, cloudArchiveStatus, reviewableIssueCount).canBackUpNow) {
                Button(
                    onClick = onArchiveBackupNow,
                    enabled = archiveRequest != ArchiveRequestState.Requesting,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    val label =
                        if (archiveRequest == ArchiveRequestState.Requesting) {
                            Res.string.data_state_requesting_archive
                        } else {
                            Res.string.backup_overview_now
                        }
                    Text(stringResource(label))
                }
            }
        }
    }
}
