package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.onboarding.flow.OnboardingFinishResult
import app.logdate.feature.onboarding.navigation.OnboardingComplete
import app.logdate.feature.onboarding.navigation.OnboardingStart
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FirstEntryCompletionTest {
    private val editor = EntryEditorRoute(firstEntry = true)

    @Test
    fun `saving the first entry opens Home with nothing in between`() =
        runTest {
            val backStack = mutableListOf<NavKey>(OnboardingStart, editor)
            backStack.completeFirstEntry(editor) { OnboardingFinishResult.Finished }
            assertEquals(listOf<NavKey>(HomeRoute), backStack)
        }

    @Test
    fun `a first entry whose completion cannot be saved stays in onboarding to retry`() =
        runTest {
            val backStack = mutableListOf<NavKey>(OnboardingStart, editor)
            backStack.completeFirstEntry(editor) { OnboardingFinishResult.SaveFailed(IllegalStateException("disk full")) }
            assertEquals(listOf<NavKey>(OnboardingComplete), backStack)
        }
}
