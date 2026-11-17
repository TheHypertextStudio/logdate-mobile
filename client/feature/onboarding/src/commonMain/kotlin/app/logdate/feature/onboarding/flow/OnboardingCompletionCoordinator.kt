package app.logdate.feature.onboarding.flow

import app.logdate.client.domain.streak.RefreshStreakUseCase
import app.logdate.client.repository.user.UserStateRepository
import io.github.aakira.napier.Napier

/**
 * The completion check terminal onboarding screens (the welcome-back and completion screens)
 * need is a one-shot read, not a live reactive view -- unlike [OnboardingDeviceStateRepository]'s
 * other consumers, which re-render as gating inputs change mid-flow. Reading each input's current
 * value directly here avoids standing up the same combine chain as a permanently-subscribed,
 * eagerly-shared [kotlinx.coroutines.flow.StateFlow] just for a check made once, at exit.
 */
class OnboardingCompletionCoordinator(
    private val userStateRepository: UserStateRepository,
    private val refreshStreakUseCase: RefreshStreakUseCase,
) {
    /**
     * Saves that onboarding is complete.
     *
     * Callers leave onboarding only on [OnboardingFinishResult.Finished]. Leaving after a failed
     * save would put the user on Home with the onboarded flag still false, and the next activity
     * recreation would send them back into onboarding.
     */
    suspend fun finishOnboarding(): OnboardingFinishResult {
        runCatching { userStateRepository.setIsOnboardingComplete(true) }
            .onFailure { error ->
                Napier.e("Failed to save onboarding completion", error)
                return OnboardingFinishResult.SaveFailed(error)
            }

        refreshStreakUseCase()
        return OnboardingFinishResult.Finished
    }
}

/** The outcome of [OnboardingCompletionCoordinator.finishOnboarding]. */
sealed interface OnboardingFinishResult {
    /** The onboarded flag is saved; the caller can leave onboarding. */
    data object Finished : OnboardingFinishResult

    /** A required [step] is still incomplete; the caller routes there instead. */
    data class IncompleteStep(
        val step: OnboardingStep,
    ) : OnboardingFinishResult

    /** Saving the onboarded flag failed; the caller stays put and offers a retry. */
    data class SaveFailed(
        val error: Throwable,
    ) : OnboardingFinishResult
}
