@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.ui.theme.Spacing

@Composable
internal fun SyncSettingsSection(
    syncStatus: app.logdate.client.sync.SyncStatus?,
    cloudArchiveStatus: CloudArchiveStatus,
    isAuthenticated: Boolean?,
    accountLoadFailed: Boolean = false,
    onRetryAccount: () -> Unit = {},
    syncRequest: SyncRequestState = SyncRequestState.Idle,
    archiveRequest: ArchiveRequestState = ArchiveRequestState.Idle,
    onSyncUsingMobileData: () -> Unit = {},
    onArchiveBackupNow: () -> Unit = {},
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    onEnterRecoveryPhrase: (() -> Unit)? = null,
    onUpdateApp: (() -> Unit)? = null,
    reviewableIssueCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (accountLoadFailed && isAuthenticated != null) {
            AccountLoadingRegion(true, onRetryAccount, Modifier.padding(Spacing.md))
        }
        when (isAuthenticated) {
            null -> AccountLoadingRegion(accountLoadFailed, onRetryAccount, Modifier.padding(Spacing.md))
            true -> BackupStatusItem(
                syncStatus = syncStatus,
                cloudArchiveStatus = cloudArchiveStatus,
                onSyncUsingMobileData = onSyncUsingMobileData,
                onArchiveBackupNow = onArchiveBackupNow,
                syncRequest = syncRequest,
                archiveRequest = archiveRequest,
                onUpdateApp = onUpdateApp,
                onEnterRecoveryPhrase = onEnterRecoveryPhrase,
                reviewableIssueCount = reviewableIssueCount,
            )
            false -> BackupCloudSection(
                isAuthenticated = false,
                syncStatus = syncStatus,
                cloudArchiveStatus = cloudArchiveStatus,
                onSyncUsingMobileData = onSyncUsingMobileData,
                onCreateAccount = onNavigateToCloudAccountCreation,
                onSignIn = onNavigateToSignIn,
                onEnterRecoveryPhrase = onEnterRecoveryPhrase,
                onArchiveBackupNow = onArchiveBackupNow,
                onUpdateApp = onUpdateApp,
                reviewableIssueCount = reviewableIssueCount,
            )
        }
    }
}
