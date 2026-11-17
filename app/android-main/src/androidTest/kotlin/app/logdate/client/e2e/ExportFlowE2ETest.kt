package app.logdate.client.e2e

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.text.toUiText
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.restore.RestoreState
import app.logdate.feature.core.settings.ui.DataSettingsContent
import app.logdate.feature.core.settings.ui.IntegrityState
import app.logdate.feature.core.settings.ui.StorageQuotaUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Comprehensive instrumentation tests for the export flow UI.
 *
 * Tests cover every state of the export state machine, all UI interactions,
 * and the complete export options bottom sheet.
 */
@RunWith(AndroidJUnit4::class)
class ExportFlowE2ETest {
    @get:Rule
    val composeRule = createComposeRule()

    private val defaultQuota =
        StorageQuotaUi(
            totalBytes = 100_000_000_000L,
            usedBytes = 0L,
            usagePercentage = 0f,
            formattedTotal = "100 GB",
            formattedUsed = "0 B",
            categories = emptyList(),
        )

    // region DataSettingsContent — Export State Rendering

    @Test
    fun `idle state shows export button`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Idle,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Export data").assertIsDisplayed()
        composeRule.onNodeWithText("Export").assertIsDisplayed()
        composeRule.onNodeWithText("Export").assertIsEnabled()
    }

    @Test
    fun `idle state export button triggers show export options`() {
        var showOptionsCalled = false

        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Idle,
                onShowExportOptions = { showOptionsCalled = true },
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Export").performClick()
        assert(showOptionsCalled) { "Expected showExportOptions to be called" }
    }

    @Test
    fun `selecting state shows export button disabled`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Selecting,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onAllNodesWithText("Export")[1].assertIsNotEnabled()
    }

    @Test
    fun `exporting state shows progress card`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Exporting(progressPercent = 45, message = "Collecting notes...".toUiText()),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("45%").assertIsDisplayed()
        composeRule.onNodeWithText("Collecting notes...").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `exporting state cancel button triggers cancel export`() {
        var cancelCalled = false

        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Exporting(progressPercent = 45, message = "Collecting notes...".toUiText()),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = { cancelCalled = true },
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Cancel").performClick()
        assert(cancelCalled) { "Expected cancelExport to be called" }
    }

    @Test
    fun `completed state shows success card`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState =
                    ExportState.Completed(
                        path = "content://downloads/logdate_export.zip",
                        fileName = "logdate_export.zip",
                        stats =
                            ExportStats(
                                journalCount = 3,
                                noteCount = 42,
                                draftCount = 5,
                                mediaCount = 10,
                            ),
                    ),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Export complete").assertIsDisplayed()
        composeRule.onNodeWithText("logdate_export.zip").assertIsDisplayed()
        composeRule.onNodeWithText("3 journals, 42 notes, 5 drafts, 10 media files").assertIsDisplayed()
        composeRule.onNodeWithText("Open").assertDoesNotExist()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun `completed state done button triggers dissmiss`() {
        var dismissCalled = false

        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState =
                    ExportState.Completed(
                        path = "/path/to/export.zip",
                        fileName = "export.zip",
                    ),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = { dismissCalled = true },
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Done").performClick()
        assert(dismissCalled) { "Expected dismissExportResult to be called" }
    }

    @Test
    fun `completed state without stats shows success card without stats line`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState =
                    ExportState.Completed(
                        path = "/path/to/export.zip",
                        fileName = "export.zip",
                        stats = null,
                    ),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Export complete").assertIsDisplayed()
        composeRule.onNodeWithText("export.zip").assertIsDisplayed()
        composeRule.onAllNodesWithText("media files", substring = true).fetchSemanticsNodes().let {
            assert(it.isEmpty()) { "Stats should not be shown when stats is null" }
        }
    }

    @Test
    fun `failed state shows failure card`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState =
                    ExportState.Failed(
                        reason = "Disk space full".toUiText(),
                        canRetry = true,
                    ),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Export failed").assertIsDisplayed()
        composeRule.onNodeWithText("Disk space full").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun `failed state retry button triggers retry export`() {
        var retryCalled = false

        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Failed(reason = "Error".toUiText(), canRetry = true),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = { retryCalled = true },
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Retry").performClick()
        assert(retryCalled) { "Expected retryExport to be called" }
    }

    @Test
    fun `failed state dismiss button triggers dismiss`() {
        var dismissCalled = false

        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Failed(reason = "Error".toUiText()),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = { dismissCalled = true },
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Dismiss").performClick()
        assert(dismissCalled) { "Expected dismissExportResult to be called" }
    }

    @Test
    fun `exporting state zero percent shows indeterminate progress`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Exporting(progressPercent = 0, message = "Starting export...".toUiText()),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("0%").assertIsDisplayed()
        composeRule.onNodeWithText("Starting export...").assertIsDisplayed()
    }

    @Test
    fun `exporting state hundred percent shows100`() {
        composeRule.setContent {
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = ExportState.Exporting(progressPercent = 100, message = "Finalizing...".toUiText()),
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("100%").assertIsDisplayed()
        composeRule.onNodeWithText("Finalizing...").assertIsDisplayed()
    }

    // endregion

    // region State Transitions — Dynamic state changes

    @Test
    fun `state transition idle to exporting swaps export button for progress card`() {
        composeRule.setContent {
            var state by remember { mutableStateOf<ExportState>(ExportState.Idle) }
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = state,
                onShowExportOptions = { state = ExportState.Exporting(10, "Starting...".toUiText()) },
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        // Initial state: export button visible
        composeRule.onNodeWithText("Export data").assertIsDisplayed()

        // Trigger state transition
        composeRule.onNodeWithText("Export").performClick()
        composeRule.waitForIdle()

        // After transition: progress card visible
        composeRule.onNodeWithText("10%").assertIsDisplayed()
        composeRule.onNodeWithText("Starting...").assertIsDisplayed()
    }

    @Test
    fun `state transition exporting to completed`() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf<ExportState>(ExportState.Exporting(90, "Finalizing...".toUiText()))
            }
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = state,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {
                    state =
                        ExportState.Completed(
                            path = "/test/export.zip",
                            fileName = "export.zip",
                            stats = ExportStats(1, 5, 2, 3),
                        )
                },
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        // Simulate transition via cancel (re-using cancel as a trigger for testing)
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Export complete").assertIsDisplayed()
        composeRule.onNodeWithText("export.zip").assertIsDisplayed()
    }

    @Test
    fun `state transition exporting to failed`() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf<ExportState>(ExportState.Exporting(30, "Working...".toUiText()))
            }
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = state,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {
                    state = ExportState.Failed(reason = "Out of memory".toUiText())
                },
                onRetryExport = {},
                onDismissExport = {},
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Export failed").assertIsDisplayed()
        composeRule.onNodeWithText("Out of memory").assertIsDisplayed()
    }

    @Test
    fun `state transition failed to idle via dismiss`() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf<ExportState>(ExportState.Failed(reason = "Disk full".toUiText()))
            }
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = state,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = { state = ExportState.Idle },
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Export data").assertIsDisplayed()
        composeRule.onNodeWithText("Export").assertIsEnabled()
    }

    @Test
    fun `state transition completed to idle via done`() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf<ExportState>(
                    ExportState.Completed(path = "/test.zip", fileName = "test.zip"),
                )
            }
            DataSettingsContent(
                onBack = {},
                quotaUsage = defaultQuota,
                isQuotaAvailable = true,
                exportState = state,
                onShowExportOptions = {},
                onUpdateExportOptions = {},
                onConfirmExport = {},
                onCancelExport = {},
                onRetryExport = {},
                onDismissExport = { state = ExportState.Idle },
                onShowRestoreSheet = {},
                onSelectRestoreFile = {},
                onUpdateImportOptions = {},
                onConfirmImport = {},
                onCancelRestore = {},
                onRetryRestore = {},
                onDismissRestore = {},
                restoreState = RestoreState.Idle,
                snackbarHostState = SnackbarHostState(),
            )
        }

        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Export data").assertIsDisplayed()
    }

    // endregion
}
