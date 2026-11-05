@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.export.ExportState
import app.logdate.feature.core.restore.RestoreState
import app.logdate.ui.foldable.FoldableHingeBounds
import app.logdate.ui.foldable.FoldableHingeInfo
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableHingeState
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.FoldableOcclusionType
import app.logdate.ui.foldable.FoldablePosture
import app.logdate.ui.foldable.provideFoldableLayoutInfo
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AutomaticDataSettingsTest {
    @Test
    fun phoneDoesNotAskUsersToRepairSyncMetadata() = assertAutomaticRecovery(FoldableLayoutInfo(), 411)

    @Test
    fun bookPostureDoesNotAskUsersToRepairSyncMetadata() =
        assertAutomaticRecovery(
            FoldableLayoutInfo(
                isFoldable = true,
                posture = FoldablePosture.Book,
                hinge =
                    FoldableHingeInfo(
                        orientation = FoldableHingeOrientation.Vertical,
                        state = FoldableHingeState.HalfOpened,
                        occlusionType = FoldableOcclusionType.Full,
                        bounds = FoldableHingeBounds(708.dp, 0.dp, 732.dp, 900.dp, 24.dp, 900.dp),
                        isSeparating = true,
                    ),
            ),
            1440,
        )

    @Test
    fun unknownAccountKeepsLocalDataActionsAvailable() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var exports = 0
            var imports = 0
            setContent {
                LogDateTheme(darkTheme = false) {
                    DataRegionFixture(null, onExport = { exports++ }, onImport = { imports++ })
                }
            }
            onNodeWithText("Checking your account…").assertExists()
            onNodeWithText("Create Account").assertDoesNotExist()
            onNodeWithText("Export data").performClick()
            onNodeWithText("Import backup").performClick()
            assertEquals(1, exports)
            assertEquals(1, imports)
        }

    @Test
    fun failedQuotaRefreshKeepsLoadedUsageAndLocalActions() =
        runDesktopComposeUiTest(width = 720, height = 1000) {
            setContent {
                LogDateTheme(darkTheme = false) {
                    DataRegionFixture(
                        true,
                        QuotaAvailability.Unavailable(
                            app.logdate.shared.model
                                .CloudStorageQuota(100, 25, emptyList()),
                        ),
                    )
                }
            }
            onNodeWithText("25 B / 100 B").assertExists()
            onNodeWithText("Couldn’t update cloud storage usage.").assertExists()
            onNodeWithText("Export data").assertExists()
            onNodeWithText("Import backup").assertExists()
            onNodeWithText("Try again").assertExists()
        }

    @Test
    fun bookPostureMountsTheFeedbackHost() =
        runDesktopComposeUiTest(width = 1440, height = 900) {
            val host = SnackbarHostState()
            var handled = false
            setContent {
                LogDateTheme(darkTheme = false) {
                    provideFoldableLayoutInfo(
                        FoldableLayoutInfo(
                            isFoldable = true,
                            posture = FoldablePosture.Book,
                            hinge =
                                FoldableHingeInfo(
                                    orientation = FoldableHingeOrientation.Vertical,
                                    state = FoldableHingeState.HalfOpened,
                                    occlusionType = FoldableOcclusionType.Full,
                                    bounds = FoldableHingeBounds(708.dp, 0.dp, 732.dp, 900.dp, 24.dp, 900.dp),
                                    isSeparating = true,
                                ),
                        ),
                    ) {
                        DataRegionFixture(true, snackbarHostState = host)
                        LaunchedEffect(host) {
                            host.showSnackbar("Backup request result", duration = SnackbarDuration.Indefinite)
                            handled = true
                        }
                    }
                }
            }
            onNodeWithText("Backup request result").assertExists()
            onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
                .performSemanticsAction(SemanticsActions.Dismiss) { it() }
            runOnIdle { assertTrue(handled) }
        }

    private fun assertAutomaticRecovery(
        layout: FoldableLayoutInfo,
        width: Int,
    ) = runDesktopComposeUiTest(width = width, height = 900) {
        var exports = 0
        var imports = 0
        setContent {
            LogDateTheme(darkTheme = false) {
                provideFoldableLayoutInfo(layout) {
                    DataSettingsContent(
                        onBack = {},
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 B", formattedUsed = "0 B"),
                        isQuotaAvailable = false,
                        exportState = ExportState.Idle,
                        onShowExportOptions = { exports++ },
                        onUpdateExportOptions = {},
                        onConfirmExport = {},
                        onCancelExport = {},
                        onRetryExport = {},
                        onDismissExport = {},
                        restoreState = RestoreState.Idle,
                        onShowRestoreSheet = { imports++ },
                        onSelectRestoreFile = {},
                        onUpdateImportOptions = {},
                        onConfirmImport = {},
                        onCancelRestore = {},
                        onRetryRestore = {},
                        onDismissRestore = {},
                        integrityState = IntegrityState(operation = IntegrityOperation.Failed(IntegrityFailure.CHECK)),
                        onRunIntegrityCheck = {},
                        onRepairIntegrity = {},
                        snackbarHostState = SnackbarHostState(),
                        cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.FAILED),
                        isAuthenticated = true,
                    )
                }
            }
        }
        onNodeWithText("Integrity check").assertDoesNotExist()
        onNodeWithText("Repair").assertDoesNotExist()
        onNodeWithText("Internal metadata failure").assertDoesNotExist()
        onAllNodesWithText("Sync couldn't finish").assertCountEquals(1)
        onNodeWithText("Export data").assertHasClickAction().performClick()
        onNodeWithText("Import backup").assertHasClickAction().performClick()
        assertEquals(1, exports)
        assertEquals(1, imports)
    }
}

@Composable
private fun DataRegionFixture(
    authenticated: Boolean?,
    quota: QuotaAvailability? = null,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
) {
    DataSettingsContent(
        onBack = {},
        quotaUsage = StorageQuotaUi(100, 25, 0.25f, formattedTotal = "100 B", formattedUsed = "25 B"),
        isQuotaAvailable = quota != null,
        quotaAvailability = quota,
        exportState = ExportState.Idle,
        onShowExportOptions = onExport,
        onUpdateExportOptions = {},
        onConfirmExport = {},
        onCancelExport = {},
        onRetryExport = {},
        onDismissExport = {},
        onBrowseExport = {},
        restoreState = RestoreState.Idle,
        onShowRestoreSheet = onImport,
        onSelectRestoreFile = {},
        onUpdateImportOptions = {},
        onConfirmImport = {},
        onCancelRestore = {},
        onRetryRestore = {},
        onDismissRestore = {},
        integrityState = IntegrityState(),
        onRunIntegrityCheck = {},
        onRepairIntegrity = {},
        snackbarHostState = snackbarHostState,
        isAuthenticated = authenticated,
    )
}
