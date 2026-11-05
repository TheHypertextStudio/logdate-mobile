@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.feature.core.export.UserDataExportViewModel
import app.logdate.feature.core.restore.RestoreState
import app.logdate.feature.core.restore.UserDataRestoreViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
    onNavigateToRecoveryPhrase: () -> Unit = {},
    onBrowseFile: (String) -> Unit = {},
    viewModel: DataSettingsViewModel = koinViewModel(),
    exportViewModel: UserDataExportViewModel = koinViewModel(),
    restoreViewModel: UserDataRestoreViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val nativeBackupStatus by viewModel.nativeBackupStatus.collectAsStateWithLifecycle()
    val exportState by exportViewModel.exportState.collectAsStateWithLifecycle()
    val isExportSheetVisible by exportViewModel.isSheetVisible.collectAsStateWithLifecycle()
    val restoreState by restoreViewModel.restoreState.collectAsStateWithLifecycle()
    val isRestoreSheetVisible by restoreViewModel.isSheetVisible.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(restoreState) {
        (restoreState as? RestoreState.Completed)?.let { viewModel.onRestoreCompleted(it.operationId) }
    }

    SyncFeedbackEffect(viewModel, snackbarHostState, onNavigateToSignIn)

    DataSettingsContent(
        onBack = onBack,
        quotaUsage = uiState.quotaState.orDefault().toStorageQuotaUi(),
        isQuotaAvailable = uiState.isQuotaAvailable,
        quotaAvailability = uiState.quotaAvailability,
        onRetryQuota = viewModel::observeQuota,
        exportState = exportState,
        isExportSheetVisible = isExportSheetVisible,
        onShowExportOptions = exportViewModel::showExportOptions,
        onUpdateExportOptions = exportViewModel::updateExportOptions,
        onConfirmExport = exportViewModel::confirmExport,
        onCancelExport = exportViewModel::cancelExport,
        onRetryExport = exportViewModel::retryExport,
        onDismissExport = exportViewModel::dismissSheet,
        onBrowseExport = { path -> onBrowseFile(path) },
        restoreState = restoreState,
        isRestoreSheetVisible = isRestoreSheetVisible,
        onShowRestoreSheet = restoreViewModel::showRestoreSheet,
        onSelectRestoreFile = restoreViewModel::selectFile,
        onUpdateImportOptions = restoreViewModel::updateImportOptions,
        onConfirmImport = restoreViewModel::confirmImport,
        onCancelRestore = restoreViewModel::cancelRestore,
        onRetryRestore = restoreViewModel::retryRestore,
        onDismissRestore = restoreViewModel::dismissSheet,
        integrityState = uiState.integrityState,
        onRunIntegrityCheck = viewModel::runIntegrityCheck,
        onRepairIntegrity = viewModel::repairIntegrity,
        snackbarHostState = snackbarHostState,
        syncStatus = uiState.syncStatus,
        cloudArchiveStatus = uiState.cloudArchiveStatus,
        nativeBackupStatus = nativeBackupStatus,
        onRetryNativeRecovery = viewModel::retryNativeRecovery,
        isAuthenticated = uiState.isAuthenticated,
        accountLoadFailed = uiState.accountLoadFailed,
        onRetryAccount = viewModel::observeAccount,
        syncRequest = uiState.syncRequest,
        archiveRequest = uiState.archiveRequest,
        onSyncNow = { viewModel.syncNow() },
        onSyncUsingMobileData = { viewModel.syncNow(allowMeteredMedia = true) },
        onArchiveBackupNow = viewModel::backupArchiveNow,
        onNavigateToRecoveryPhrase = onNavigateToRecoveryPhrase,
        onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
        onNavigateToSignIn = onNavigateToSignIn,
    )
}
