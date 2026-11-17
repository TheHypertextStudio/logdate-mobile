package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.device.restore.NativeBackupQuotaFailure
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.domain.export.ExportCounts
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.domain.restore.ArchivePreview
import app.logdate.client.sync.SyncStatus
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.restore.ImportOptions
import app.logdate.feature.core.restore.RestoreState
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ArchiveSettingsNavigationTest {
    @Test
    fun tabletArchiveOptionsOpenInACenteredDialog() =
        runDesktopComposeUiTest(width = 1280, height = 800) {
            setContent {
                LogDateTheme {
                    ExportSettingsContent(
                        onBack = {},
                        exportState = ExportState.Configuring(counts = ExportCounts(1, 2, 1, 3)),
                        onShowExportOptions = {},
                        onUpdateExportOptions = {},
                        onConfirmExport = {},
                        onCancelExport = {},
                        onRetryExport = {},
                        onDismissExport = {},
                        restoreState = RestoreState.Idle,
                        onShowRestoreSheet = {},
                        onSelectRestoreFile = {},
                        onUpdateImportOptions = {},
                        onConfirmImport = {},
                        onCancelRestore = {},
                        onRetryRestore = {},
                        onDismissRestore = {},
                        snackbarHostState = SnackbarHostState(),
                    )
                }
            }
            onAllNodes(isDialog()).assertCountEquals(1)
            onNodeWithText("Export your data").assertIsDisplayed()
        }

    @Test
    fun dataSettingsHasExactlyOneExportActionWithAndroidCoverageVisible() =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            setContent { LogDateTheme { ArchiveSettingsDataFixture() } }
            onAllNodes(hasClickAction() and (hasText("Export data") or hasText("Export") or hasText("Export archive")))
                .assertCountEquals(1)
            onNodeWithText("Android backup").assertIsDisplayed()
            onNodeWithText("Export archive").assertDoesNotExist()
        }

    @Test
    fun wholeImportRowOpensRestoreOptionsOnce() =
        runDesktopComposeUiTest {
            var imports = 0
            setContent { LogDateTheme { ImportBackupItem { imports++ } } }
            onNodeWithText("Import backup").assertHasClickAction().performClick()
            onAllNodesWithText("Import").assertCountEquals(0)
            assertEquals(1, imports)
        }

    @Test
    fun wholeExportRowOpensOptionsOnce() =
        runDesktopComposeUiTest {
            var exports = 0
            setContent { LogDateTheme { ExportDataItem { exports++ } } }
            onNodeWithText("Export data").assertHasClickAction().performClick()
            onAllNodesWithText("Export").assertCountEquals(0)
            assertEquals(1, exports)
        }

    @Test
    fun exportImportDestinationGroupsItsTwoActionsWithoutARepeatedHeading() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            setContent {
                LogDateTheme {
                    ExportSettingsContent(
                        onBack = {},
                        exportState = ExportState.Idle,
                        onShowExportOptions = {},
                        onUpdateExportOptions = {},
                        onConfirmExport = {},
                        onCancelExport = {},
                        onRetryExport = {},
                        onDismissExport = {},
                        restoreState = RestoreState.Idle,
                        onShowRestoreSheet = {},
                        onSelectRestoreFile = {},
                        onUpdateImportOptions = {},
                        onConfirmImport = {},
                        onCancelRestore = {},
                        onRetryRestore = {},
                        onDismissRestore = {},
                        snackbarHostState = SnackbarHostState(),
                    )
                }
            }
            onNodeWithText("Export & Import").assertIsDisplayed()
            onNodeWithText("Data Management").assertDoesNotExist()
            onNodeWithText("Export data").assertHasClickAction()
            onNodeWithText("Import backup").assertHasClickAction()
        }

    @Test
    fun previewSheetsReceiveRealConfirmationTapsAboveTheSettingsScaffold() {
        listOf(false, true).forEach { importing ->
            runDesktopComposeUiTest(width = 411, height = 1200) {
                var confirmations = 0
                setContent {
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        LogDateTheme {
                            ExportSettingsContent(
                                onBack = {},
                                exportState =
                                    if (importing) {
                                        ExportState.Idle
                                    } else {
                                        ExportState.Configuring(
                                            counts = ExportCounts(1, 2, 1, 3),
                                        )
                                    },
                                onShowExportOptions = {},
                                onUpdateExportOptions = {},
                                onConfirmExport = { confirmations++ },
                                onCancelExport = {},
                                onRetryExport = {},
                                onDismissExport = {},
                                restoreState =
                                    if (importing) {
                                        RestoreState.Previewing(
                                            preview =
                                                ArchivePreview(
                                                    ExportSchemaVersion.CURRENT,
                                                    Instant.parse("2026-10-15T12:00:00Z"),
                                                    "1.0",
                                                    ExportStats(1, 2, 1, 3),
                                                ),
                                            fileName = "journal.zip",
                                            options = ImportOptions(),
                                        )
                                    } else {
                                        RestoreState.Idle
                                    },
                                onShowRestoreSheet = {},
                                onSelectRestoreFile = {},
                                onUpdateImportOptions = {},
                                onConfirmImport = { confirmations++ },
                                onCancelRestore = {},
                                onRetryRestore = {},
                                onDismissRestore = {},
                                snackbarHostState = SnackbarHostState(),
                            )
                        }
                    }
                }
                onNodeWithText(if (importing) "Import" else "Export").assertIsDisplayed().performTouchInput { click() }
                runOnIdle { assertEquals(1, confirmations, "The visible sheet must receive the tap instead of the settings background") }
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ArchiveSettingsDataFixture(onExport: () -> Unit = {}) {
    DataSettingsContent(
        onBack = {},
        quotaUsage = StorageQuotaUi(5_000_000_000, 2_650_000_000, .53f, formattedTotal = "5.0 GB", formattedUsed = "2.65 GB"),
        isQuotaAvailable = true,
        exportState = ExportState.Idle,
        onShowExportOptions = onExport,
        onUpdateExportOptions = {},
        onConfirmExport = {},
        onCancelExport = {},
        onRetryExport = {},
        onDismissExport = {},
        restoreState = RestoreState.Idle,
        onShowRestoreSheet = {},
        onSelectRestoreFile = {},
        onUpdateImportOptions = {},
        onConfirmImport = {},
        onCancelRestore = {},
        onRetryRestore = {},
        onDismissRestore = {},
        snackbarHostState = SnackbarHostState(),
        nativeBackupStatus = NativeBackupStatus.Available(NativeBackupQuotaFailure(30_000_000, 25_000_000)),
        isAuthenticated = true,
        syncStatus = SyncStatus(true, Instant.parse("2026-05-04T17:35:00Z"), 0, false, false),
        cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.COMPLETE, lastCompletedAt = 1_778_002_500_000),
    )
}
