@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.ui.common.PlatformBackHandler
import app.logdate.ui.theme.LogDateTheme

const val ONBOARDING_START_ROOT_TAG = "onboarding_start_root"
const val ONBOARDING_START_GET_STARTED_TAG = "onboarding_start_get_started"
const val ONBOARDING_START_FROM_BACKUP_TAG = "onboarding_start_from_backup"

@Composable
fun OnboardingStartScreen(
    onNext: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var overview by rememberSaveable { mutableStateOf(false) }
    // Back from the timeline returns to the table, the same as the Back button under it.
    PlatformBackHandler(enabled = overview) { overview = false }
    OnboardingOpeningScreen(
        overview = overview,
        onContinue = { if (overview) onNext() else overview = true },
        onBack = { overview = false },
        onSignIn = onSignIn,
        modifier = modifier,
    )
}

/** Retains the deterministic splash/landing entry used by screenshot and adaptive fixtures. */
@Composable
fun OnboardingStartScreenContent(
    showLanding: Boolean,
    onGetStarted: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    animateContent: Boolean = true,
) {
    OnboardingOpeningScreen(
        overview = false,
        onContinue = onGetStarted,
        onBack = {},
        onSignIn = onSignIn,
        modifier = modifier,
        showControls = showLanding,
        animateScene = animateContent,
    )
}

@Preview
@Composable
private fun OnboardingStartPreview() {
    LogDateTheme { OnboardingStartScreen(onNext = {}, onSignIn = {}) }
}
