@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.components.companion

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.R
import app.logdate.screenshots.common.OsIntegrationPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.MarkedTimelineAcceptanceContent
import app.logdate.screenshots.shared.WatchCompanionAcceptanceContent
import app.logdate.screenshots.shared.WatchCompanionAcceptanceScene
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun CompanionReady() {
    ScreenshotTheme { WatchCompanionAcceptanceContent(WatchCompanionAcceptanceScene.COMPANION) }
}

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun CompanionDiagnosticsNeedsAttention() {
    ScreenshotTheme { WatchCompanionAcceptanceContent(WatchCompanionAcceptanceScene.DIAGNOSTICS) }
}

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun CompanionRemovalReviewWithUnsentMoments() {
    ScreenshotTheme { WatchCompanionAcceptanceContent(WatchCompanionAcceptanceScene.REMOVE_REVIEW) }
}

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun CompanionRemovedWithUnsentMoments() {
    ScreenshotTheme { WatchCompanionAcceptanceContent(WatchCompanionAcceptanceScene.REMOVED_WITH_UNSENT) }
}

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun CompanionRemovalFailed() {
    ScreenshotTheme { WatchCompanionAcceptanceContent(WatchCompanionAcceptanceScene.REMOVE_FAILED) }
}

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun CompanionMarkedTimeline() {
    ScreenshotTheme {
        MarkedTimelineAcceptanceContent(
            "android.resource://studio.hypertext.logdate.debug/drawable/sample_note_photo",
            painterResource(R.drawable.sample_note_photo),
        )
    }
}

@PreviewTest
@Preview(name = "Compact 200%", device = "spec:width=320dp,height=568dp", fontScale = 2f, showBackground = true)
@Composable
fun CompanionMarkedTimelineCompactLargeText() {
    CompanionMarkedTimeline()
}
