package app.logdate.feature.core.account.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.account.BlueskySignInScreen
import app.logdate.feature.core.account.CloudAccountOnboardingScreen
import app.logdate.feature.core.account.CloudAccountOnboardingViewModel
import app.logdate.feature.core.account.OnboardingStep
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import org.koin.compose.viewmodel.koinViewModel

/**
 * Top-level cloud account setup flow opened from the Settings screen ("Sign in to LogDate
 * Cloud", "Create a cloud account") and from `SyncSettingsScreen`. Reuses the same
 * `CloudAccountOnboardingScreen` the in-onboarding step uses; the only knob is whether to
 * jump directly to the sign-in step instead of starting at the welcome screen.
 */
@Serializable
data class CloudAccountSetupRoute(
    val startOnSignIn: Boolean = false,
) : NavKey

/** Pushes the cloud account setup flow. */
fun NavBackStack<NavKey>.navigateToCloudAccountSetup(startOnSignIn: Boolean = false) {
    add(CloudAccountSetupRoute(startOnSignIn = startOnSignIn))
}

/** Registers the cloud account setup entry. */
fun EntryProviderScope<NavKey>.cloudAccountSetupEntry(
    onAccountCreated: () -> Unit,
    onSkipped: () -> Unit,
    onBack: () -> Unit,
    onContinueWithBluesky: (() -> Unit)? = null,
    onBlueskyAccountCreated: () -> Unit = onAccountCreated,
) {
    taggedEntry<BlueskySignInRoute> { route ->
        BlueskySignInScreen(route.attemptId, onBlueskyAccountCreated, onBack)
    }
    taggedEntry<CloudAccountSetupRoute> { route ->
        val viewModel: CloudAccountOnboardingViewModel = koinViewModel()
        LaunchedEffect(route.startOnSignIn) {
            if (route.startOnSignIn) {
                viewModel.setInitialStep(OnboardingStep.SignIn)
            }
        }
        CloudAccountOnboardingScreen(
            viewModel = viewModel,
            onAccountCreated = onAccountCreated,
            onSkipOnboarding = onSkipped,
            onBack = onBack,
            onContinueWithBluesky = onContinueWithBluesky,
        )
    }
}
