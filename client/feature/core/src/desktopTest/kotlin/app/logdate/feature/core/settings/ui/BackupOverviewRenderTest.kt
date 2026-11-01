package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.client.sync.SyncStatus
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackupOverviewRenderTest {
    private val idle = SyncStatus(true, null, 0, false, false)

    @Test
    fun `phone shows first backup action without inventing a saved copy`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var requests = 0
            setContent {
                LogDateTheme(darkTheme = false) {
                    backupPage(idle, CloudArchiveStatus(CloudArchivePhase.NEVER_BACKED_UP), onBackupNow = { requests++ })
                }
            }
            onNodeWithText("No saved backup yet").assertExists()
            onNodeWithText("Preparing to sync…").assertDoesNotExist()
            onNodeWithText("Back up now").performClick()
            assertEquals(1, requests)
            onNodeWithText("Review sync issues").assertDoesNotExist()
            save(onRoot().captureToImage(), "backup-first-copy")
        }

    @Test
    fun `tablet dark shows a confirmed date alongside pending work`() =
        runDesktopComposeUiTest(width = 840, height = 1100) {
            val sync = idle.copy(pendingUploads = 3)
            val archive = CloudArchiveStatus(CloudArchivePhase.COMPLETE, 1793036520000L)
            setContent {
                LogDateTheme(darkTheme = true) {
                    backupPage(sync, archive)
                }
            }
            onNodeWithText("Saved backup available").assertExists()
            onNodeWithText("Preparing to sync…").assertDoesNotExist()
            onNodeWithText("Last saved backup").assertExists()
            onNodeWithText("Changes waiting to sync").assertExists()
            onNodeWithText("3").assertExists()
            save(onRoot().captureToImage(), "backup-tablet-dark")
        }

    @Test
    fun `large RTL review action opens the retained edit destination`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var reviews = 0
            val sync = idle.copy(conflictCount = 1)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LogDateTheme(darkTheme = true) {
                        backupPage(sync, CloudArchiveStatus(CloudArchivePhase.COMPLETE, 1793036520000L), onReview = { reviews++ })
                    }
                }
            }
            onNodeWithText("1 current sync issue").performScrollTo().performClick()
            assertEquals(1, reviews)
            save(onRoot().captureToImage(), "backup-review-rtl-large")
        }

    @Test
    fun `earlier records remain visible without being called edits or blocking backup`() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent {
                LogDateTheme {
                    backupPage(idle.copy(earlierConflictCount = 1268), CloudArchiveStatus(CloudArchivePhase.NEVER_BACKED_UP))
                }
            }
            onNodeWithText("1268 earlier sync issues").performScrollTo().assertExists()
            onNodeWithText("1268 edits need review").assertDoesNotExist()
            onNodeWithText("Back up now").performScrollTo().assertExists()
            save(onRoot().captureToImage(), "backup-earlier-records")
        }

    @Composable
    private fun backupPage(
        sync: SyncStatus,
        archive: CloudArchiveStatus,
        onBackupNow: () -> Unit = {},
        onReview: () -> Unit = {},
    ) {
        SyncSettingsContent(
            onBack = {},
            syncStatus = sync,
            cloudArchiveStatus = archive,
            isAuthenticated = true,
            onSyncNow = {},
            onArchiveBackupNow = onBackupNow,
            onReviewSavedEdits = onReview,
            onNavigateToSignIn = {},
            quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 GB", formattedUsed = "0 B"),
            isQuotaAvailable = true,
            snackbarHostState = remember { SnackbarHostState() },
        )
    }

    private fun save(
        bitmap: androidx.compose.ui.graphics.ImageBitmap,
        name: String,
    ) {
        val pixels = bitmap.toPixelMap()
        val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val root = System.getenv("DISK_JANITOR_ARTIFACT_ROOT")?.let { File(it, "renders") } ?: File("build/reports/sync-renders")
        root.mkdirs()
        ImageIO.write(image, "png", File(root, "$name.png"))
    }
}
