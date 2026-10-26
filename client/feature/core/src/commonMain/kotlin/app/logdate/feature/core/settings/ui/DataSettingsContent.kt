@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.export.ExportBottomSheet
import app.logdate.feature.core.export.ExportOptions
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.restore.ImportOptions
import app.logdate.feature.core.restore.RestoreBottomSheet
import app.logdate.feature.core.restore.RestoreState
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.data_and_storage
import logdate.client.feature.core.generated.resources.data_management
import org.jetbrains.compose.resources.stringResource

@Composable
fun DataSettingsContent(
    onBack: () -> Unit,
    quotaUsage: StorageQuotaUi,
    isQuotaAvailable: Boolean,
    exportState: ExportState,
    isExportSheetVisible: Boolean = exportState !is ExportState.Idle,
    onShowExportOptions: () -> Unit,
    onUpdateExportOptions: (ExportOptions) -> Unit,
    onConfirmExport: () -> Unit,
    onCancelExport: () -> Unit,
    onRetryExport: () -> Unit,
    onDismissExport: () -> Unit,
    onBrowseExport: (String) -> Unit,
    restoreState: RestoreState,
    isRestoreSheetVisible: Boolean = restoreState !is RestoreState.Idle,
    onShowRestoreSheet: () -> Unit,
    onSelectRestoreFile: () -> Unit,
    onUpdateImportOptions: (ImportOptions) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelRestore: () -> Unit,
    onRetryRestore: () -> Unit,
    onDismissRestore: () -> Unit,
    integrityState: IntegrityState,
    onRunIntegrityCheck: () -> Unit,
    onRepairIntegrity: () -> Unit,
    snackbarHostState: SnackbarHostState,
    syncStatus: app.logdate.client.sync.SyncStatus? = null,
    cloudArchiveStatus: CloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.CHECKING),
    isAuthenticated: Boolean = false,
    onSyncNow: () -> Unit = {},
    onSyncUsingMobileData: () -> Unit = {},
    onArchiveBackupNow: () -> Unit = {},
    onNavigateToRecoveryPhrase: () -> Unit = {},
    onNavigateToCloudAccountCreation: () -> Unit = {},
    onNavigateToSignIn: () -> Unit = {},
) {
    if (isExportSheetVisible) {
        ExportBottomSheet(
            exportState = exportState,
            onOptionsChanged = onUpdateExportOptions,
            onConfirm = onConfirmExport,
            onCancel = onCancelExport,
            onRetry = onRetryExport,
            onDismiss = onDismissExport,
            onBrowse = onBrowseExport,
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

    FoldableBookLayout(
        modifier = Modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                if (isAuthenticated && isQuotaAvailable) {
                    QuotaUsageBlock(
                        quotaUsage = quotaUsage,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }

                SettingsSection(
                    title = stringResource(Res.string.data_management),
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                ) {
                    Column {
                        ExportDataItem(onShowExportOptions = onShowExportOptions)
                        ImportBackupItem(onShowRestoreSheet = onShowRestoreSheet)
                    }
                }
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                SyncSettingsSection(
                    syncStatus = syncStatus,
                    cloudArchiveStatus = cloudArchiveStatus,
                    onSyncUsingMobileData = onSyncUsingMobileData,
                    onArchiveBackupNow = onArchiveBackupNow,
                    isAuthenticated = isAuthenticated,
                    onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
                    onNavigateToSignIn = onNavigateToSignIn,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.data_and_storage),
                onBack = onBack,
                snackbarHostState = snackbarHostState,
            ) {
                if (isAuthenticated && isQuotaAvailable) {
                    item {
                        QuotaUsageBlock(
                            quotaUsage = quotaUsage,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        )
                    }
                }

                item {
                    SettingsSection(
                        title = stringResource(Res.string.data_management),
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    ) {
                        Column {
                            ExportDataItem(onShowExportOptions = onShowExportOptions)
                            ImportBackupItem(onShowRestoreSheet = onShowRestoreSheet)
                        }
                    }
                }

                item {
                    SyncSettingsSection(
                        syncStatus = syncStatus,
                        cloudArchiveStatus = cloudArchiveStatus,
                        onSyncUsingMobileData = onSyncUsingMobileData,
                        onArchiveBackupNow = onArchiveBackupNow,
                        isAuthenticated = isAuthenticated,
                        onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
                        onNavigateToSignIn = onNavigateToSignIn,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        },
    )
}

@Preview
@Composable
private fun DataSettingsScreenPreview() {
    DataSettingsContent(
        onBack = {},
        quotaUsage =
            StorageQuotaUi(
                totalBytes = 100_000_000_000L,
                usedBytes = 0L,
                usagePercentage = 0f,
                formattedTotal = "100 GB",
                formattedUsed = "0 B",
                categories = emptyList(),
            ),
        isQuotaAvailable = true,
        exportState = ExportState.Idle,
        onShowExportOptions = {},
        onUpdateExportOptions = {},
        onConfirmExport = {},
        onCancelExport = {},
        onRetryExport = {},
        onDismissExport = {},
        onBrowseExport = {},
        restoreState = RestoreState.Idle,
        onShowRestoreSheet = {},
        onSelectRestoreFile = {},
        onUpdateImportOptions = {},
        onConfirmImport = {},
        onCancelRestore = {},
        onRetryRestore = {},
        onDismissRestore = {},
        integrityState = IntegrityState(),
        onRunIntegrityCheck = {},
        onRepairIntegrity = {},
        snackbarHostState = remember { SnackbarHostState() },
    )
}
