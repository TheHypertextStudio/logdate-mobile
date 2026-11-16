package app.logdate.screenshots.flows.flow20_os_integration

import androidx.compose.runtime.Composable
import app.logdate.screenshots.common.OsIntegrationPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.SharedScreenshotScene
import app.logdate.screenshots.shared.SharedScreenshotSceneId
import com.android.tools.screenshot.PreviewTest

/** Privacy settings, where people decide whether journal entries appear in Android system search. */
@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun S01_PrivacySystemSearchVisibility() {
    ScreenshotTheme {
        SharedScreenshotScene(SharedScreenshotSceneId.PrivacySettings)
    }
}
