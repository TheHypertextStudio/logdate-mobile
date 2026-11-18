@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.components.companion

import androidx.compose.runtime.Composable
import app.logdate.screenshots.common.OsIntegrationPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.screenshots.shared.WatchLinkingAcceptanceContent
import app.logdate.screenshots.shared.WatchLinkingAcceptanceScene
import com.android.tools.screenshot.PreviewTest

/** The phone's Watch page through every step of finding and linking a watch. */
@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingChecking() = Scene(WatchLinkingAcceptanceScene.CHECKING)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingNoWatch() = Scene(WatchLinkingAcceptanceScene.NO_WATCH)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingNotInstalled() = Scene(WatchLinkingAcceptanceScene.NOT_INSTALLED)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingAllowPairing() = Scene(WatchLinkingAcceptanceScene.ALLOW_PAIRING)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingConfirmOnPhone() = Scene(WatchLinkingAcceptanceScene.CONFIRM_ON_PHONE)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingInProgress() = Scene(WatchLinkingAcceptanceScene.LINKING)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingRetrying() = Scene(WatchLinkingAcceptanceScene.LINKING_RETRYING)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingDeviceLimit() = Scene(WatchLinkingAcceptanceScene.LINKING_DEVICE_LIMIT)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingAnotherAccount() = Scene(WatchLinkingAcceptanceScene.LINKING_ANOTHER_ACCOUNT)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingLinked() = Scene(WatchLinkingAcceptanceScene.LINKED)

@PreviewTest
@OsIntegrationPreviewMatrix
@Composable
fun LinkingSetUpAgain() = Scene(WatchLinkingAcceptanceScene.SET_UP_AGAIN)

@Composable
private fun Scene(scene: WatchLinkingAcceptanceScene) {
    ScreenshotTheme { WatchLinkingAcceptanceContent(scene) }
}
