@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.runtime.Composable
import app.logdate.feature.core.settings.ui.watch.WatchIssue
import app.logdate.feature.core.settings.ui.watch.WatchLinkLine
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchSetupStep
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.feature.core.settings.ui.watch.WatchSyncLine
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionPage

/** Every state the phone's Watch page shows while a watch is found, linked, or stuck, in the order a person meets them. */
enum class WatchLinkingAcceptanceScene(
    val status: WatchStatus?,
) {
    CHECKING(null),
    NO_WATCH(WatchStatus.NoWatch),
    NOT_INSTALLED(WatchStatus.NotInstalled("Pixel Watch 3")),
    ALLOW_PAIRING(WatchStatus.SettingUp(WatchSetupStep.PairWithPhone("Pixel Watch 3"))),
    CONFIRM_ON_PHONE(WatchStatus.SettingUp(WatchSetupStep.ConfirmOnPhone("Pixel Watch 3"))),
    LINKING(WatchStatus.SettingUp(WatchSetupStep.Linking("Pixel Watch 3"))),
    LINKING_RETRYING(WatchStatus.SettingUp(WatchSetupStep.Linking("Pixel Watch 3", WatchLinkProblem.RETRYING))),
    LINKING_DEVICE_LIMIT(WatchStatus.SettingUp(WatchSetupStep.Linking("Pixel Watch 3", WatchLinkProblem.DEVICE_LIMIT))),
    LINKING_ANOTHER_ACCOUNT(WatchStatus.SettingUp(WatchSetupStep.Linking("Pixel Watch 3", WatchLinkProblem.ANOTHER_ACCOUNT))),
    LINKED(
        WatchStatus.Ready(
            "Pixel Watch 3",
            nearby = true,
            link = WatchLinkLine.ON_ITS_OWN,
            sync = WatchSyncLine(waiting = 0),
        ),
    ),
    SET_UP_AGAIN(WatchStatus.NeedsAttention("Pixel Watch 3", nearby = true, issue = WatchIssue.SetUpAgain)),
    UPDATE_WATCH(
        WatchStatus.NeedsAttention(
            "Pixel Watch 5",
            nearby = true,
            issue = WatchIssue.WatchUpdateRequired(watchVersion = "0.1.0", phoneVersion = "0.2.0"),
        ),
    ),
    UPDATE_PHONE(
        WatchStatus.NeedsAttention(
            "Pixel Watch 5",
            nearby = true,
            issue = WatchIssue.PhoneUpdateRequired(watchVersion = "0.3.0", phoneVersion = "0.2.0"),
        ),
    ),
}

@Composable
fun WatchLinkingAcceptanceContent(scene: WatchLinkingAcceptanceScene) {
    WatchCompanionPage(
        status = scene.status,
        requestFailure = null,
        onBack = {},
        onAssociate = {},
        onInstall = {},
        onReminders = {},
        onTiles = {},
        onMicrophone = {},
        onNotifications = {},
        onDiagnostics = {},
        onRemove = {},
        onUpdateOnPhone = {},
    )
}
