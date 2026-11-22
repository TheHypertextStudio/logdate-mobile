package app.logdate.feature.core.settings.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.logdate.client.sync.SyncStatus
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Renders the "Keep my key on this device only" row on both pages that offer it, for review. */
@OptIn(ExperimentalTestApi::class)
class KeyBackupChoiceRenderTest {
    private val title = "Keep my key on this device only"

    @Test
    fun `privacy page before and after the row on a phone`() {
        render("privacy-phone-before", 411, 891) { privacy(null) }
        render("privacy-phone-after", 411, 891) { privacy(KeyBackupChoiceState(keptOnDevice = false)) }
    }

    @Test
    fun `privacy page on a dark tablet with the key kept on this device`() {
        render("privacy-tablet-dark-kept", 840, 1100, dark = true) { privacy(KeyBackupChoiceState(keptOnDevice = true)) }
    }

    @Test
    fun `backup page before and after the row on a phone`() {
        render("backup-phone-before", 411, 891) { backup(null) }
        render("backup-phone-after", 411, 891) { backup(KeyBackupChoiceState(keptOnDevice = false)) }
    }

    @Test
    fun `the row at 200 percent text and right to left and after a failed change`() {
        render("privacy-phone-large-text", 411, 891, fontScale = 2f, scrollTo = title) {
            privacy(KeyBackupChoiceState(keptOnDevice = true))
        }
        render("privacy-phone-rtl", 411, 891, rtl = true) { privacy(KeyBackupChoiceState(keptOnDevice = false)) }
        render("privacy-phone-failed", 411, 891) { privacy(KeyBackupChoiceState(keptOnDevice = true, failed = true)) }
    }

    @Composable
    private fun privacy(choice: KeyBackupChoiceState?) {
        PrivacySettingsContent(
            state =
                PrivacySettingsState(
                    biometrics = PrivacyPreferenceState.Available(true),
                    systemSearchVisibility = PrivacyPreferenceState.Available(false),
                    showSystemSearchVisibilityToggle = true,
                ),
            onBack = {},
            onSetBiometricsEnabled = {},
            keyBackupChoice = choice,
        )
    }

    @Composable
    private fun backup(choice: KeyBackupChoiceState?) {
        SyncSettingsContent(
            onBack = {},
            syncStatus = SyncStatus(true, null, 0, false, false),
            cloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.COMPLETE, 1793036520000L),
            isAuthenticated = true,
            keyBackupChoice = choice,
            onNavigateToSignIn = {},
            snackbarHostState = remember { SnackbarHostState() },
        )
    }

    private fun render(
        name: String,
        width: Int,
        height: Int,
        dark: Boolean = false,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        scrollTo: String? = null,
        content: @Composable () -> Unit,
    ) = runDesktopComposeUiTest(width = width, height = height) {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                LogDateTheme(darkTheme = dark) { content() }
            }
        }
        scrollTo?.let { onNode(hasScrollToNodeAction()).performScrollToNode(hasText(it, substring = true)) }
        if (name.endsWith("before").not()) onNodeWithText(title).assertExists()
        save(onRoot().captureToImage(), name)
    }

    private fun save(
        bitmap: ImageBitmap,
        name: String,
    ) {
        val pixels = bitmap.toPixelMap()
        val image = BufferedImage(bitmap.width, bitmap.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) image.setRGB(x, y, pixels[x, y].toArgb())
        val root = File("build/reports/key-backup-renders").apply { mkdirs() }
        ImageIO.write(image, "png", File(root, "$name.png"))
    }
}
