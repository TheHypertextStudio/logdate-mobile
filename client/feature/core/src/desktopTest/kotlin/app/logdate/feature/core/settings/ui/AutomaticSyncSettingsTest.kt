package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.sync.SyncPausedReason
import app.logdate.client.sync.SyncStatus
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AutomaticSyncSettingsTest {
    @Test
    fun backupFailuresDoNotCreateTroubleshootingChores() =
        runDesktopComposeUiTest(width = 720, height = 900) {
            setContent {
                LogDateTheme(darkTheme = false) {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = SyncStatus(true, null, 1, false, true, pausedReason = SyncPausedReason.NEEDS_RECOVERY_PHRASE),
                        cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.NEEDS_RECOVERY),
                        isAuthenticated = true,
                        onSyncNow = {},
                        onNavigateToSignIn = {},
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 B", formattedUsed = "0 B"),
                        isQuotaAvailable = true,
                        snackbarHostState = SnackbarHostState(),
                    )
                }
            }
            onNodeWithText("Sync entries").assertDoesNotExist()
            onNodeWithText("Enter recovery phrase").assertDoesNotExist()
            onNodeWithText("Entry sync").assertDoesNotExist()
            onNodeWithText("Encrypted Cloud archive").assertDoesNotExist()
            onAllNodesWithText("Sync couldn't finish").assertCountEquals(1)
            onNodeWithText("LogDate will try again automatically.").assertExists()
            onNodeWithText("Sync paused while this device gets access to your journal.").assertDoesNotExist()
            saveScreenshot("tablet-journal-access", onRoot().captureToImage())
        }

    @Test
    fun offlineBackupHasOneReasonOnPhone() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme(darkTheme = true) {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = SyncStatus(true, null, 1, false, false, pausedReason = SyncPausedReason.OFFLINE),
                        cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.RETRYING),
                        isAuthenticated = true,
                        onSyncNow = {},
                        onNavigateToSignIn = {},
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 GB", formattedUsed = "0 B"),
                        isQuotaAvailable = true,
                        snackbarHostState = SnackbarHostState(),
                    )
                }
            }
            onAllNodesWithText("Sync paused: this device is offline. It resumes automatically when connected.").assertCountEquals(1)
            onNodeWithText("Entry sync").assertDoesNotExist()
            onNodeWithText("Encrypted Cloud archive").assertDoesNotExist()
            onNodeWithText("Up to date").assertDoesNotExist()
            saveScreenshot("phone-offline-dark", onRoot().captureToImage())
        }

    @Test
    fun archiveFailureCannotShowEntrySyncAsComplete() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme(darkTheme = false) {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = SyncStatus(true, null, 0, false, false),
                        cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.FAILED),
                        isAuthenticated = true,
                        onSyncNow = {},
                        onNavigateToSignIn = {},
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 GB", formattedUsed = "0 B"),
                        isQuotaAvailable = true,
                        snackbarHostState = SnackbarHostState(),
                    )
                }
            }
            onAllNodesWithText("Sync couldn't finish").assertCountEquals(1)
            onNodeWithText("The cloud backup did not finish. Try again when you’re ready.").assertExists()
            onNodeWithText("Retry archive backup").assertExists()
            onNodeWithText("Up to date").assertDoesNotExist()
            saveScreenshot("phone-automatic-retry", onRoot().captureToImage())
        }

    @Test
    fun wifiWaitOffersOneRunMobileDataConsent() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var requests = 0
            setContent {
                LogDateTheme(darkTheme = false) {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = SyncStatus(true, null, 1, false, false, pausedReason = SyncPausedReason.MEDIA_WAITING_FOR_WIFI),
                        cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.RETRYING),
                        isAuthenticated = true,
                        onSyncNow = {},
                        onSyncUsingMobileData = { requests++ },
                        onNavigateToSignIn = {},
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 GB", formattedUsed = "0 B"),
                        isQuotaAvailable = true,
                        snackbarHostState = SnackbarHostState(),
                    )
                }
            }
            onAllNodesWithText("Photos and recordings will sync when this device is on Wi-Fi.").assertCountEquals(1)
            onNodeWithText("Waiting for Wi-Fi").assertExists()
            onNodeWithText("Total Usage").assertDoesNotExist()
            onNodeWithText("Sync using mobile data").performClick()
            assertEquals(1, requests)
            saveScreenshot("phone-wifi-mobile-data", onRoot().captureToImage())
        }

    private fun saveScreenshot(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("LOGDATE_SYNC_SCREENSHOT_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(output, "png", file)
    }
}
