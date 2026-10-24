package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.client.device.restore.NativeBackupQuotaFailure
import app.logdate.client.device.restore.NativeBackupStatus
import app.logdate.client.device.restore.NativeRestoreCustodyStatus
import app.logdate.ui.theme.LogDateTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NativeBackupSettingsTest {
    @Test
    fun backupHasANamedSettingsEntryWithoutAnAccount() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var visits = 0
            setContent {
                LogDateTheme {
                    SettingsDataSection(
                        userProfile = UserProfile("Fixture", "fixture"),
                        onNavigateToSync = { visits++ },
                        onNavigateToExport = {},
                        onNavigateToReset = {},
                        onNavigateToCloudAccountCreation = {},
                        onNavigateToSignIn = {},
                    )
                }
            }
            onNodeWithText("Backup").performClick()
            assertEquals(1, visits)
        }

    @Test
    fun largeTextKeepsTheAccountFreeExportReachable() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var exports = 0
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    LogDateTheme {
                        SyncSettingsContent(
                            onBack = {},
                            syncStatus = null,
                            isAuthenticated = false,
                            onSyncNow = {},
                            onNavigateToSignIn = {},
                            quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 B", formattedUsed = "0 B"),
                            isQuotaAvailable = false,
                            snackbarHostState = SnackbarHostState(),
                            nativeBackupStatus =
                                NativeBackupStatus.Available(NativeBackupQuotaFailure(30_000_000, 25_000_000)),
                            onExportArchive = { exports++ },
                        )
                    }
                }
            }
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Export & Import"))
            onNodeWithText("Export & Import").performClick()
            assertEquals(1, exports)
        }

    @Test
    fun signedOutBackupKeepsCloudSetupAndArchiveActionsDistinct() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var accounts = 0
            var signIns = 0
            var exports = 0
            setContent {
                LogDateTheme {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = null,
                        isAuthenticated = false,
                        onSyncNow = {},
                        onNavigateToCloudAccountCreation = { accounts++ },
                        onNavigateToSignIn = { signIns++ },
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 B", formattedUsed = "0 B"),
                        isQuotaAvailable = false,
                        snackbarHostState = SnackbarHostState(),
                        nativeBackupStatus = NativeBackupStatus.Available(),
                        onExportArchive = { exports++ },
                    )
                }
            }
            onNodeWithText("LogDate Cloud").assertExists()
            onNodeWithText("Your signed-in devices stay in sync").assertDoesNotExist()
            onNodeWithText("Create Account").performClick()
            onNodeWithText("Sign in").performClick()
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Export & Import"))
            onNodeWithText("Export & Import").performClick()
            onAllNodesWithText("Export & Import").assertCountEquals(1)
            assertEquals(1, accounts)
            assertEquals(1, signIns)
            assertEquals(1, exports)
        }

    @Test
    fun quotaFailureOffersArchiveExportWithoutAnAccount() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var exports = 0
            var accounts = 0
            var signIns = 0
            setContent {
                LogDateTheme {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = null,
                        isAuthenticated = false,
                        onSyncNow = {},
                        onNavigateToCloudAccountCreation = { accounts++ },
                        onNavigateToSignIn = { signIns++ },
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 B", formattedUsed = "0 B"),
                        isQuotaAvailable = false,
                        snackbarHostState = SnackbarHostState(),
                        nativeBackupStatus =
                            NativeBackupStatus.Available(NativeBackupQuotaFailure(30_000_000, 25_000_000)),
                        onExportArchive = { exports++ },
                    )
                }
            }
            onNodeWithText("Android backup").assertExists()
            onNodeWithText(
                "An Android backup exceeded its size limit. Export an archive to save a copy.",
                substring = true,
            ).assertExists()
            onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Export & Import"))
            onNodeWithText("Export & Import").performClick()
            assertEquals(1, exports)
            assertEquals(0, accounts)
            assertEquals(0, signIns)
            onNodeWithText("Backed up").assertDoesNotExist()
        }

    @Test
    fun otherPlatformsDoNotShowAndroidBackup() =
        runDesktopComposeUiTest {
            setContent { NativeBackupSettingsSection(NativeBackupStatus.Unsupported) }
            onNodeWithText("Android backup").assertDoesNotExist()
        }

    @Test
    fun pendingNativeRecoveryOffersRetryWithoutExposingStorageErrors() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var retries = 0
            setContent {
                LogDateTheme {
                    NativeBackupSettingsSection(
                        NativeBackupStatus.Available(custodyStatus = NativeRestoreCustodyStatus.RECOVERING),
                        onRetryRecovery = { retries++ },
                    )
                }
            }
            onNodeWithText("Finishing device transfer").performClick()
            assertEquals(1, retries)
            onNodeWithText("Pending native source custody", substring = true).assertDoesNotExist()
        }

    @Test
    fun incompleteNativeTransferOpensExistingArchiveRecovery() =
        runDesktopComposeUiTest(width = 411, height = 900) {
            var archiveVisits = 0
            setContent {
                LogDateTheme {
                    NativeBackupSettingsSection(
                        NativeBackupStatus.Available(custodyStatus = NativeRestoreCustodyStatus.NEEDS_TRANSFER),
                        onOpenArchive = { archiveVisits++ },
                    )
                }
            }
            onNodeWithText("Recover missing files").performClick()
            assertEquals(1, archiveVisits)
        }
}
