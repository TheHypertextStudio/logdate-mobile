@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.repository.account.BlueskySignInState
import org.koin.compose.viewmodel.koinViewModel

/** Provider identity changes submission, not LogDate's existing account setup flow. */
@Composable
internal fun BlueskyAccountSetupFlow(
    provider: BlueskySignInViewModel,
    onAccountCreated: () -> Unit,
    onCancel: () -> Unit,
) {
    val ui by provider.uiState.collectAsStateWithLifecycle()
    val stage by provider.stage.collectAsStateWithLifecycle()
    val signup: CloudAccountOnboardingViewModel = koinViewModel(key = "bluesky-signup-${ui.signupGeneration}")
    val saved = (stage as? BlueskySignInState.AccountDetails)?.savedRequest
    LaunchedEffect(saved) {
        if (saved != null) {
            signup.updateUsername(saved.username)
            signup.updateDisplayName(saved.displayName)
            signup.updateBio(saved.bio.orEmpty())
            signup.setInitialStep(OnboardingStep.PasskeyCreation)
        } else if (!ui.creatingAccount) {
            signup.setInitialStep(OnboardingStep.DisplayName)
        }
    }
    CloudAccountOnboardingScreen(
        viewModel = signup,
        onAccountCreated = onAccountCreated,
        onSkipOnboarding = { provider.cancel(onCancel) },
        onBack = { provider.cancel(onCancel) },
        creationOverride =
            CloudAccountCreationOverride(
                busy = ui.busy,
                error = ui.failure?.let { blueskyFailure(it, stage) },
                detailsEditable = !ui.detailsLocked,
                create = { provider.createAccount(it) },
                clearError = provider::clearFailure,
            ),
    )
}
