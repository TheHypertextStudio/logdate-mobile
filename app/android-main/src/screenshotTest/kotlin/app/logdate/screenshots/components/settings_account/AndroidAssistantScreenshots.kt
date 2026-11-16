package app.logdate.screenshots.components.settings_account

import androidx.compose.runtime.Composable
import app.logdate.screenshots.common.OsIntegrationPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.SharedScreenshotScene
import app.logdate.screenshots.shared.SharedScreenshotSceneId
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun AndroidAssistants() {
    ScreenshotTheme { SharedScreenshotScene(SharedScreenshotSceneId.AndroidAssistants) }
}
