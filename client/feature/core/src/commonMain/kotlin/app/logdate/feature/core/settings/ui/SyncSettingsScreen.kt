@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.repository.journals.JournalMergeOperation
import app.logdate.client.sync.space.migration.JournalMoveState
import app.logdate.client.sync.space.migration.JournalStorageMove
import app.logdate.feature.core.settings.updates.AppUpdateCheckTrigger
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.core.settings.updates.AppUpdateStatus
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.sync_and_backup
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
    onNavigateToExport: () -> Unit = {},
    onRecoverJournalMerge: (JournalMergeOperation) -> Unit = {},
    onReviewSavedEdits: () -> Unit = {},
    viewModel: DataSettingsViewModel = koinViewModel(),
    mergeJournals: MergeJournalsUseCase = koinInject(),
    journalStorage: JournalStorageMove = koinInject(),
    appUpdates: AppUpdateController = koinInject(),
) {
    val appUpdateState by appUpdates.uiState.collectAsStateWithLifecycle()
    val mergeIssues by remember(mergeJournals) { mergeJournals.observeIssues() }.collectAsStateWithLifecycle(emptyList())
    val journalMove by journalStorage.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(journalStorage) { journalStorage.refresh() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val reviewableIssueCount by viewModel.reviewableIssueCount.collectAsStateWithLifecycle()
    val nativeBackupStatus by viewModel.nativeBackupStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    SyncFeedbackEffect(viewModel, snackbarHostState, onNavigateToSignIn)

    SyncSettingsContent(
        onBack = onBack,
        onReviewSavedEdits = onReviewSavedEdits,
        reviewableIssueCount = reviewableIssueCount,
        mergeIssues = mergeIssues,
        onRecoverJournalMerge = onRecoverJournalMerge,
        journalMove = journalMove,
        onRetryJournalMove = { scope.launch { journalStorage.retry() } },
        onSwitchJournalBack = { scope.launch { journalStorage.switchBack() } },
        onUpdateApp =
            if (appUpdateState.status == AppUpdateStatus.Unsupported) {
                null
            } else {
                { scope.launch { appUpdates.checkForUpdates(AppUpdateCheckTrigger.Manual) } }
            },
        syncStatus = uiState.syncStatus,
        cloudArchiveStatus = uiState.cloudArchiveStatus,
        nativeBackupStatus = nativeBackupStatus,
        onExportArchive = onNavigateToExport,
        onRetryNativeRecovery = viewModel::retryNativeRecovery,
        isAuthenticated = uiState.isAuthenticated,
        accountLoadFailed = uiState.accountLoadFailed,
        onRetryAccount = viewModel::observeAccount,
        syncRequest = uiState.syncRequest,
        archiveRequest = uiState.archiveRequest,
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
    nativeBackupStatus: NativeBackupStatus = NativeBackupStatus.Unsupported,
    onExportArchive: () -> Unit = {},
    onRetryNativeRecovery: () -> Unit = {},
    isAuthenticated: Boolean?,
    accountLoadFailed: Boolean = false,
    onRetryAccount: () -> Unit = {},
    syncRequest: SyncRequestState = SyncRequestState.Idle,
    archiveRequest: ArchiveRequestState = ArchiveRequestState.Idle,
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
    journalMove: JournalMoveState = JournalMoveState.NotOffered,
    onRetryJournalMove: () -> Unit = {},
    onSwitchJournalBack: () -> Unit = {},
    onUpdateApp: (() -> Unit)? = null,
    reviewableIssueCount: Int = 0,
) {
    SettingsScaffold(
        title = stringResource(Res.string.sync_and_backup),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    ) {
        item {
            SyncSettingsSection(
                isAuthenticated = isAuthenticated,
                accountLoadFailed = accountLoadFailed,
                onRetryAccount = onRetryAccount,
                syncRequest = syncRequest,
                archiveRequest = archiveRequest,
                syncStatus = syncStatus,
                cloudArchiveStatus = cloudArchiveStatus,
                onSyncUsingMobileData = onSyncUsingMobileData,
                onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
                onNavigateToSignIn = onNavigateToSignIn,
                onEnterRecoveryPhrase = onNavigateToRecoveryPhrase,
                modifier = Modifier.padding(horizontal = Spacing.lg),
                onUpdateApp = onUpdateApp,
                onArchiveBackupNow = onArchiveBackupNow,
                reviewableIssueCount = reviewableIssueCount,
            )
        }
        if (isAuthenticated == true && backupOverviewState(syncStatus, cloudArchiveStatus, reviewableIssueCount).hasDetails) {
            item {
                BackupDetailsSection(
                    syncStatus = syncStatus,
                    archiveStatus = cloudArchiveStatus,
                    onReviewIssues = onReviewSavedEdits,
                    reviewableIssueCount = reviewableIssueCount,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
        if (isAuthenticated == true && journalMove != JournalMoveState.NotOffered) {
            item {
                JournalStorageSection(
                    state = journalMove,
                    onTryAgain = onRetryJournalMove,
                    onSwitchBack = onSwitchJournalBack,
                    onEnterRecoveryPhrase = onNavigateToRecoveryPhrase,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
        if (nativeBackupStatus is NativeBackupStatus.Available) {
            item {
                NativeBackupSettingsSection(
                    nativeBackupStatus,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                    onRetryRecovery = onRetryNativeRecovery,
                    onOpenArchive = onExportArchive,
                )
            }
        }
        item {
            BackupArchiveItem(onExportArchive, Modifier.padding(horizontal = Spacing.lg))
        }
        if (isAuthenticated == true) {
            items(mergeIssues, key = { it.operationId }) { operation ->
                JournalMergeRecoveryItem(operation) { onRecoverJournalMerge(operation) }
            }
        }
    }
}
