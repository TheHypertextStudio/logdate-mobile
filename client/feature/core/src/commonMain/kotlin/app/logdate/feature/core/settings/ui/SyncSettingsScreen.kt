@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.create_account
import logdate.client.feature.core.generated.resources.sign_in
import logdate.client.feature.core.generated.resources.sync_and_backup
import logdate.client.feature.core.generated.resources.sync_devices_subtitle
import logdate.client.feature.core.generated.resources.sync_feature_access
import logdate.client.feature.core.generated.resources.sync_feature_backup
import logdate.client.feature.core.generated.resources.sync_feature_sync
import logdate.client.feature.core.generated.resources.sync_feedback_failed
import logdate.client.feature.core.generated.resources.sync_feedback_needs_account
import logdate.client.feature.core.generated.resources.sync_feedback_sign_in_action
import logdate.client.feature.core.generated.resources.sync_feedback_started
import logdate.client.feature.core.generated.resources.sync_feedback_succeeded
import logdate.client.feature.core.generated.resources.sync_feedback_up_to_date
import logdate.client.feature.core.generated.resources.sync_recovery_review
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sync settings screen.
 *
 * This screen handles cloud sync status, background sync, storage quota,
 * and sync conflict resolution.
 */
@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToRecoveryPhrase: () -> Unit = {},
    onRecoverJournalMerge: (JournalMergeOperation) -> Unit = {},
    onReviewSavedEdits: () -> Unit = {},
    viewModel: DataSettingsViewModel = koinViewModel(),
    mergeJournals: MergeJournalsUseCase = koinInject(),
) {
    val mergeIssues by remember(mergeJournals) { mergeJournals.observeIssues() }.collectAsState(emptyList())
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // This screen owns the Sync Now the user actually reaches from Settings, so it reports the
    // outcome the same way Data & Storage does. Both share DataSettingsViewModel.
    val syncFeedback by viewModel.syncFeedback.collectAsState()
    val needsAccountMessage = stringResource(Res.string.sync_feedback_needs_account)
    val signInActionLabel = stringResource(Res.string.sync_feedback_sign_in_action)
    val upToDateMessage = stringResource(Res.string.sync_feedback_up_to_date)
    LaunchedEffect(syncFeedback) {
        val feedback = syncFeedback ?: return@LaunchedEffect
        when (feedback) {
            is SyncFeedback.NeedsAccount -> {
                val action = snackbarHostState.showSnackbar(needsAccountMessage, signInActionLabel)
                if (action == SnackbarResult.ActionPerformed) {
                    onNavigateToSignIn()
                }
            }

            is SyncFeedback.Succeeded -> {
                val moved = feedback.uploadedItems + feedback.downloadedItems
                snackbarHostState.showSnackbar(
                    if (moved == 0) {
                        upToDateMessage
                    } else {
                        getString(
                            Res.string.sync_feedback_succeeded,
                            feedback.uploadedItems,
                            feedback.downloadedItems,
                        )
                    },
                )
            }

            SyncFeedback.Started -> {
                snackbarHostState.showSnackbar(getString(Res.string.sync_feedback_started))
            }

            is SyncFeedback.Failed -> {
                snackbarHostState.showSnackbar(
                    getString(Res.string.sync_feedback_failed, feedback.message),
                )
            }
        }
        viewModel.consumeSyncFeedback()
    }

    // The session flow starts at null meaning "not read yet", and stateIn's seed used to call
    // that signed out - so the screen opened on Create Account for someone already signed in and
    // syncing, then corrected itself a frame later. Hold the screen until the answer is known.
    val isAuthenticated = uiState.isAuthenticated
    if (isAuthenticated == null) {
        SettingsScaffold(
            title = stringResource(Res.string.sync_and_backup),
            onBack = onBack,
            snackbarHostState = snackbarHostState,
        ) {}
        return
    }

    SyncSettingsContent(
        onBack = onBack,
        onReviewSavedEdits = onReviewSavedEdits,
        mergeIssues = mergeIssues,
        onRecoverJournalMerge = onRecoverJournalMerge,
        syncStatus = uiState.syncStatus,
        cloudArchiveStatus = uiState.cloudArchiveStatus,
        isAuthenticated = isAuthenticated,
        onSyncNow = { viewModel.syncNow() },
        onSyncUsingMobileData = { viewModel.syncNow(allowMeteredMedia = true) },
        onArchiveBackupNow = viewModel::backupArchiveNow,
        onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
        onNavigateToSignIn = onNavigateToSignIn,
        onNavigateToRecoveryPhrase = onNavigateToRecoveryPhrase,
        quotaUsage = uiState.quotaState.orDefault().toStorageQuotaUi(),
        isQuotaAvailable = uiState.isQuotaAvailable && uiState.hasAuthoritativeQuota,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
fun SyncSettingsContent(
    onBack: () -> Unit,
    syncStatus: app.logdate.client.sync.SyncStatus?,
    cloudArchiveStatus: CloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.CHECKING),
    isAuthenticated: Boolean,
    onSyncNow: () -> Unit,
    onSyncUsingMobileData: () -> Unit = {},
    onArchiveBackupNow: () -> Unit = {},
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit,
    onNavigateToRecoveryPhrase: () -> Unit = {},
    quotaUsage: StorageQuotaUi,
    isQuotaAvailable: Boolean,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    mergeIssues: List<JournalMergeOperation> = emptyList(),
    onRecoverJournalMerge: (JournalMergeOperation) -> Unit = {},
    onReviewSavedEdits: () -> Unit = {},
) {
    SettingsScaffold(
        title = stringResource(Res.string.sync_and_backup),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        item {
            TextButton(onClick = onReviewSavedEdits) { Text(stringResource(Res.string.sync_recovery_review)) }
        }
        item {
            if (!isAuthenticated) {
                SyncPromoContent(
                    onCreateAccount = onNavigateToCloudAccountCreation,
                    onSignIn = onNavigateToSignIn,
                    modifier = Modifier.fillParentMaxHeight(),
                )
            } else {
                BackupStatusItem(
                    syncStatus = syncStatus,
                    cloudArchiveStatus = cloudArchiveStatus,
                    onSyncUsingMobileData = onSyncUsingMobileData,
                    onArchiveBackupNow = onArchiveBackupNow,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
        if (isAuthenticated) {
            items(mergeIssues, key = { it.operationId }) { operation ->
                JournalMergeRecoveryItem(operation) { onRecoverJournalMerge(operation) }
            }
        }
    }
}

@Composable
private fun SyncPromoContent(
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.Cloud,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        Text(
            text = stringResource(Res.string.sync_devices_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(Spacing.xl))

        SyncFeatureList()

        Spacer(modifier = Modifier.height(Spacing.xl))

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Button(
                onClick = onCreateAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.create_account))
            }
            OutlinedButton(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.sign_in))
            }
        }
    }
}

@Composable
private fun SyncFeatureList(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
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
    }
}

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
