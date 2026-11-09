package app.logdate.client

import app.logdate.client.launch.LaunchBootstrapState
import app.logdate.client.launch.LaunchStage
import app.logdate.client.launch.LaunchStageTracker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val LAUNCH_WATCHDOG_TIMEOUT_MS = 750L

/**
 * Starts a watchdog timer that releases the Android splash screen if the initial UI state
 * takes longer than [LAUNCH_WATCHDOG_TIMEOUT_MS] to load.
 */
internal fun CoroutineScope.startLaunchWatchdog(launchTracker: LaunchStageTracker) {
    launch {
        delay(LAUNCH_WATCHDOG_TIMEOUT_MS)
        launchTracker.expireWatchdog()
        if (launchTracker.bootstrapState is LaunchBootstrapState.SplashReleased) {
            Napier.w(
                "MainActivity launch watchdog expired after ${LAUNCH_WATCHDOG_TIMEOUT_MS}ms; " +
                    "releasing splash while waiting for ${LaunchStage.AppUiLoaded.analyticsName}; " +
                    "last completed stage=${launchTracker.snapshot.latestCompletedStage.analyticsName}",
                tag = APP_LAUNCH_TAG,
            )
        }
    }
}
