package app.logdate.feature.onboarding.di

import app.logdate.feature.core.settings.ui.watch.v2.NoWatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What onboarding needs to offer the watch step: the coordinator that pairs a watch, and whether
 * the step is turned on.
 *
 * Each platform binds this explicitly, so a platform with a watch cannot quietly fall back to the
 * no-watch defaults that [app.logdate.feature.onboarding.ui.OnboardingViewModel] declares.
 */
internal class OnboardingWatchSetup(
    val coordinator: WatchSetupCoordinator,
    val enabled: Flow<Boolean>,
) {
    companion object {
        /** For platforms without a watch: the step is never offered. */
        val None = OnboardingWatchSetup(NoWatchSetupCoordinator, flowOf(false))
    }
}
