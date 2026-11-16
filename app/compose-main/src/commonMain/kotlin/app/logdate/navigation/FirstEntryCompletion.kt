package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.editor.navigation.completeEditorVisit
import app.logdate.feature.onboarding.flow.OnboardingFinishResult
import app.logdate.feature.onboarding.navigation.OnboardingComplete

/**
 * Saving the first entry is what finishes onboarding, so the editor hands off straight to Home.
 *
 * When the completion cannot be saved the editor hands off to [OnboardingComplete] instead, which
 * retries and says what failed. Opening Home with onboarding still unfinished would send the next
 * launch back to the start.
 */
internal suspend fun MutableList<NavKey>.completeFirstEntry(
    saved: EntryEditorRoute,
    finishOnboarding: suspend () -> OnboardingFinishResult,
) {
    val destination = if (finishOnboarding() == OnboardingFinishResult.Finished) HomeRoute else OnboardingComplete
    completeEditorVisit(saved, destination)
}
