package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
class ArchiveProgressRenderTest {
    @Test
    fun phoneMeasuredExport() = render("archive-export", 411, 891, false, false, 1f, CloudArchiveStage.EXPORTING, "Creating backup…", 0.4f)

    @Test
    fun tabletDarkEncryption() = render("archive-encrypt", 840, 1100, true, false, 1f, CloudArchiveStage.ENCRYPTING, "Encrypting backup…")

    @Test
    fun phoneLargeRtlUpload() =
        render("archive-upload-rtl-large", 411, 891, true, true, 2f, CloudArchiveStage.UPLOADING, "Uploading backup…")

    @Test
    fun phoneChecking() = render("archive-checking", 411, 891, false, false, 1f, CloudArchiveStage.CHECKING, "Checking backup contents…")

    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean,
        rtl: Boolean,
        scale: Float,
        stage: CloudArchiveStage,
        message: String,
        fraction: Float? = null,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                LogDateTheme(darkTheme = dark) {
                    SyncSettingsContent(
                        onBack = {},
                        syncStatus = SyncStatus(true, null, 0, false, false),
                        cloudArchiveStatus =
                            CloudArchiveStatus(
                                CloudArchivePhase.RUNNING,
                                progress = CloudArchiveProgress(stage, fraction),
                            ),
                        isAuthenticated = true,
                        onSyncNow = {},
                        onNavigateToSignIn = {},
                        quotaUsage = StorageQuotaUi(100, 0, 0f, formattedTotal = "100 GB", formattedUsed = "0 B"),
                        isQuotaAvailable = true,
                        snackbarHostState = SnackbarHostState(),
                    )
                }
            }
        }
        onNodeWithText(message).assertExists()
        onNodeWithText("Up to date").assertDoesNotExist()
        val actual =
            onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            ).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        if (fraction == null) assertEquals(ProgressBarRangeInfo.Indeterminate, actual) else assertEquals(fraction, actual.current)
        val bitmap = onRoot().captureToImage()
        val pixels = bitmap.toPixelMap()
        val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val renderRoot =
            System.getenv("DISK_JANITOR_ARTIFACT_ROOT")?.let { File(it, "renders") }
                ?: File("build/reports/sync-renders")
        val target = File(renderRoot, "$name.png")
        target.parentFile.mkdirs()
        ImageIO.write(image, "png", target)
    }
}
