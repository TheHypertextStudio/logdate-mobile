@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import app.logdate.client.sync.diagnostics.DiagnosticReportingSettings
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.LogDateTheme
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AutomaticDiagnosticReportingSectionTest {
    @Test fun unresolvedAvailabilityDoesNotRenderAnOffToggleOrUnavailableClaim() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            setContent { LogDateTheme(darkTheme = false) { SectionFixture(AutomaticDiagnosticReportingState()) } }
            onNodeWithText("Checking reporting settings…").assertExists()
            onAllNodes(isToggleable()).assertCountEquals(0)
            onNodeWithText(
                "Automatic reporting is unavailable for this account or server. Local export remains available.",
            ).assertDoesNotExist()
            saveScreenshot("diagnostics-phone-loading", onRoot().captureToImage())
        }

    @Test fun deletionProgressStaysOnItsActionAndRetainsTheKnownEnabledPreference() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            val state =
                AutomaticDiagnosticReportingState(
                    ReportingAvailability.Available(DiagnosticReportingSettings("https://fixture.invalid", true, true)),
                    deletion = ReportingDeleteOperation.Deleting,
                )
            setContent { LogDateTheme(darkTheme = false) { SectionFixture(state) } }
            onNodeWithText("Deleting uploaded reports…").assertIsNotEnabled()
            onAllNodes(isToggleable())[0].assertIsOn().assertIsNotEnabled()
            onNodeWithText("Saving reporting preference…").assertDoesNotExist()
            saveScreenshot("diagnostics-phone-delete-busy", onRoot().captureToImage())
        }

    @Test fun aToggleRetryDoesNotEraseTheUploadedDeletionResult() =
        runDesktopComposeUiTest(width = 411, height = 891) {
            var retries = 0
            val state =
                AutomaticDiagnosticReportingState(
                    ReportingAvailability.Available(DiagnosticReportingSettings("https://fixture.invalid", true, true)),
                    toggle = ReportingToggleOperation.Failed(false, ReportingToggleFailure.DISABLE),
                    deletion = ReportingDeleteOperation.Deleted,
                )
            setContent { LogDateTheme(darkTheme = false) { SectionFixture(state) { retries++ } } }
            onNodeWithText("Automatic reports couldn’t be turned off. Try again.").assertExists()
            onNodeWithText("Uploaded reports deleted.").assertExists()
            onNodeWithText("Uploaded reports couldn’t be deleted. Try again.").assertDoesNotExist()
            onNodeWithText("Try again").performClick()
            assertEquals(1, retries)
            saveScreenshot("diagnostics-phone-toggle-error", onRoot().captureToImage())
        }

    @Test
    fun compactLargeTextKeepsToggleSavingLocal() =
        renderLargeText(
            name = "diagnostics-phone-200-toggle-busy-light",
            state =
                AutomaticDiagnosticReportingState(
                    ReportingAvailability.Available(DiagnosticReportingSettings("https://fixture.invalid", true, true)),
                    toggle = ReportingToggleOperation.Saving(false),
                ),
            focus = "Saving reporting preference…",
            dark = false,
        )

    @Test
    fun compactLargeTextKeepsDeletionBusyInDarkMode() =
        renderLargeText(
            name = "diagnostics-phone-200-delete-busy-dark",
            state =
                AutomaticDiagnosticReportingState(
                    ReportingAvailability.Available(DiagnosticReportingSettings("https://fixture.invalid", true, true)),
                    deletion = ReportingDeleteOperation.Deleting,
                ),
            focus = "Deleting uploaded reports…",
            dark = true,
        )

    @Test
    fun compactLargeTextKeepsDeletionFailureAndRetryReadable() =
        renderLargeText(
            name = "diagnostics-phone-200-delete-error-light",
            state =
                AutomaticDiagnosticReportingState(
                    ReportingAvailability.Available(DiagnosticReportingSettings("https://fixture.invalid", true, true)),
                    deletion = ReportingDeleteOperation.Failed(ReportingDeleteFailure.DELETE),
                ),
            focus = "Uploaded reports couldn’t be deleted. Try again.",
            dark = false,
        )

    private fun renderLargeText(
        name: String,
        state: AutomaticDiagnosticReportingState,
        focus: String,
        dark: Boolean,
    ) = runDesktopComposeUiTest(width = 411, height = 891) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                LogDateTheme(darkTheme = dark) { SectionFixture(state) }
            }
        }
        onAllNodes(isToggleable())[0].assertIsOn()
        saveScreenshot("$name-overview", onRoot().captureToImage())
        val action = onNodeWithText(focus)
        action.performScrollTo().assertIsDisplayed()
        val bounds = action.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= 0f && bounds.right <= 411f, "The reporting action must fit the compact viewport at 200% text")
        if (state.deletion is ReportingDeleteOperation.Failed) {
            onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
        } else {
            onAllNodes(isToggleable())[0].assertIsNotEnabled()
        }
        saveScreenshot("$name-action", onRoot().captureToImage())
    }

    private fun saveScreenshot(
        name: String,
        image: ImageBitmap,
    ) {
        val directory = System.getenv("DIAGNOSTIC_CAPTURE_DIR") ?: return
        val pixels = image.toPixelMap()
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) output.setRGB(x, y, pixels[x, y].toArgb())
        }
        val file = File(directory, "$name.png")
        file.parentFile.mkdirs()
        check(ImageIO.write(output, "png", file)) { "Could not encode the diagnostics capture" }
    }
}

@Composable
private fun SectionFixture(
    state: AutomaticDiagnosticReportingState,
    onRetry: () -> Unit = {},
) {
    SettingsScaffold(title = "Developer tools", onBack = {}) {
        item { AutomaticDiagnosticReportingContent(state, {}, {}, onRetry, {}, {}, {}, {}, {}, {}) }
    }
}
