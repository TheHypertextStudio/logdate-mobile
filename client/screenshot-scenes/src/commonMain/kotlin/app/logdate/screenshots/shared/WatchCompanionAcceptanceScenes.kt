@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.runtime.Composable
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.peer.removal.WatchRemovalResult
import app.logdate.client.sync.peer.removal.WatchRemovalTarget
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionPage
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionPreferences
import app.logdate.feature.core.settings.ui.watch.v2.WatchCompanionSections
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticCheck
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticFix
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticOutcome
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticStep
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticsContent
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticsState
import app.logdate.feature.core.settings.ui.watch.v2.WatchRemovalContent
import app.logdate.feature.core.settings.ui.watch.v2.WatchRemovalState

/** Actual companion content shared by Android goldens and the interactive desktop matrix. */
enum class WatchCompanionAcceptanceScene { COMPANION, DIAGNOSTICS, REMOVE_REVIEW, REMOVE_SYNC_FAILED }

@Composable
fun WatchCompanionAcceptanceContent(scene: WatchCompanionAcceptanceScene) {
    when (scene) {
        WatchCompanionAcceptanceScene.COMPANION -> PopulatedCompanion()
        WatchCompanionAcceptanceScene.DIAGNOSTICS -> CompanionDiagnostics()
        WatchCompanionAcceptanceScene.REMOVE_REVIEW -> CompanionRemovalReview()
        WatchCompanionAcceptanceScene.REMOVE_SYNC_FAILED -> CompanionRemovalSyncFailed()
    }
}

@Composable
private fun PopulatedCompanion() {
    WatchCompanionPage(
        status = WatchStatus.Ready("Pixel Watch", nearby = true),
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
        preferences = { captureActions ->
            WatchCompanionSections(
                state = WatchCompanionPreferences(30, true, true, true, true),
                saving = false,
                saveFailed = false,
                onDays = {},
                onThisDay = {},
                onRewind = {},
                onPlaces = {},
                onMood = {},
                captureActions = captureActions,
            )
        },
    )
}

@Composable
private fun CompanionDiagnostics() {
    WatchDiagnosticsContent(
        state =
            WatchDiagnosticsState.Ready(
                WatchDiagnosticCheck.entries.map { check ->
                    when (check) {
                        WatchDiagnosticCheck.SYNC -> {
                            WatchDiagnosticStep(
                                check,
                                WatchDiagnosticOutcome.FIXABLE,
                                WatchDiagnosticFix.SYNC,
                            )
                        }

                        WatchDiagnosticCheck.NOTIFICATIONS -> {
                            WatchDiagnosticStep(
                                check,
                                WatchDiagnosticOutcome.FIXABLE,
                                WatchDiagnosticFix.OPEN_WATCH,
                            )
                        }

                        else -> {
                            WatchDiagnosticStep(check, WatchDiagnosticOutcome.PASSED)
                        }
                    }
                },
            ),
        busy = false,
        onCheck = {},
        onFix = {},
        onBack = {},
    )
}

@Composable
private fun CompanionRemovalReview() {
    WatchRemovalContent(
        WatchRemovalState.Review(
            WatchRemovalTarget(
                PeerDeliveryContext("fixture-link", "fixture-phone", "fixture-watch", LinkMode.PAIR),
                "Pixel Watch",
                pending = 3,
            ),
        ),
        onRemove = {},
        onRetry = {},
        onBack = {},
    )
}

@Composable
private fun CompanionRemovalSyncFailed() {
    WatchRemovalContent(
        WatchRemovalState.Finished(WatchRemovalResult.Failed("sync")),
        onRemove = {},
        onRetry = {},
        onBack = {},
    )
}
