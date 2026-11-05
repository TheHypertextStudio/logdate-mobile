@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.feature.core.export.ExportBottomSheet
import app.logdate.feature.core.export.ExportOptions
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.export.UserDataExportViewModel
import app.logdate.feature.core.restore.ImportOptions
import app.logdate.feature.core.restore.RestoreBottomSheet
import app.logdate.feature.core.restore.RestoreState
import app.logdate.feature.core.restore.UserDataRestoreViewModel
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.export_and_import
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ExportSettingsScreen(
    onBack: () -> Unit,
    exportViewModel: UserDataExportViewModel = koinViewModel(),
    restoreViewModel: UserDataRestoreViewModel = koinViewModel(),
) {
    val exportState by exportViewModel.exportState.collectAsStateWithLifecycle()
    val isExportSheetVisible by exportViewModel.isSheetVisible.collectAsStateWithLifecycle()
    val restoreState by restoreViewModel.restoreState.collectAsStateWithLifecycle()
    val isRestoreSheetVisible by restoreViewModel.isSheetVisible.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    ExportSettingsContent(
        onBack = onBack,
        exportState = exportState,
        isExportSheetVisible = isExportSheetVisible,
        onShowExportOptions = exportViewModel::showExportOptions,
        onUpdateExportOptions = exportViewModel::updateExportOptions,
        onConfirmExport = exportViewModel::confirmExport,
        onCancelExport = exportViewModel::cancelExport,
        onRetryExport = exportViewModel::retryExport,
        onDismissExport = exportViewModel::dismissSheet,
        restoreState = restoreState,
        isRestoreSheetVisible = isRestoreSheetVisible,
        onShowRestoreSheet = restoreViewModel::showRestoreSheet,
        onSelectRestoreFile = restoreViewModel::selectFile,
        onUpdateImportOptions = restoreViewModel::updateImportOptions,
        onConfirmImport = restoreViewModel::confirmImport,
        onCancelRestore = restoreViewModel::cancelRestore,
        onRetryRestore = restoreViewModel::retryRestore,
        onDismissRestore = restoreViewModel::dismissSheet,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
fun ExportSettingsContent(
    onBack: () -> Unit,
    exportState: ExportState,
    isExportSheetVisible: Boolean = exportState !is ExportState.Idle,
    onShowExportOptions: () -> Unit,
    onUpdateExportOptions: (ExportOptions) -> Unit,
    onConfirmExport: () -> Unit,
    onCancelExport: () -> Unit,
    onRetryExport: () -> Unit,
    onDismissExport: () -> Unit,
    restoreState: RestoreState,
    isRestoreSheetVisible: Boolean = restoreState !is RestoreState.Idle,
    onShowRestoreSheet: () -> Unit,
    onSelectRestoreFile: () -> Unit,
    onUpdateImportOptions: (ImportOptions) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelRestore: () -> Unit,
    onRetryRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    Box(Modifier.fillMaxSize()) {
        SettingsScaffold(
            title = stringResource(Res.string.export_and_import),
            onBack = onBack,
            snackbarHostState = snackbarHostState,
        ) {
            item {
                MaterialContainer(
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                ) {
                    ExportDataItem(onShowExportOptions)
                    ImportBackupItem(onShowRestoreSheet)
                }
            }
        }

        if (isExportSheetVisible) {
            ExportBottomSheet(
                exportState = exportState,
                onOptionsChanged = onUpdateExportOptions,
                onConfirm = onConfirmExport,
                onCancel = onCancelExport,
                onRetry = onRetryExport,
                onDismiss = onDismissExport,
            )
        }

        if (isRestoreSheetVisible) {
            RestoreBottomSheet(
                restoreState = restoreState,
                onSelectFile = onSelectRestoreFile,
                onUpdateOptions = onUpdateImportOptions,
                onConfirmImport = onConfirmImport,
                onCancel = onCancelRestore,
                onRetry = onRetryRestore,
                onDismiss = onDismissRestore,
            )
        }
    }
}
