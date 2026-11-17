package app.logdate.client.e2e

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.MainActivity
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_NEW_ENTRY
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_TARGET
import app.logdate.client.testing.onboarding.OnboardingTestFixture
import app.logdate.client.testing.onboarding.putOnboardingTestFixture
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.koin.dsl.module

/**
 * Instrumented E2E tests verifying UI state preservation during activity recreation.
 *
 * This suite ensures that the [MainActivity] correctly saves and restores its
 * navigation and input state—specifically within the entry editor—when the
 * system triggers an activity restart (e.g., due to a configuration change).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityRecreationE2ETest {
    private val koinRule = OnboardingKoinModuleOverrideRule(module {})
    private val activityRule = ActivityScenarioRule<MainActivity>(createEditorLaunchIntent())
    private val composeRule = AndroidComposeTestRule(activityRule, ::getActivityFromScenarioRule)

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule).around(composeRule)

    @Test
    fun `entry editor remains open across activity recreation`() {
        // The row of ways to add something comes in once the entry has loaded.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Write").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Write").performClick()
        waitForTag("editor_text_input")

        composeRule.onNodeWithTag("editor_text_input").performTextInput("Keep me in the editor")
        composeRule.waitForIdle()

        composeRule.activityRule.scenario.recreate()

        waitForTag("editor_text_input")
        composeRule.onNodeWithTag("editor_text_input").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_text_input").assertTextContains("Keep me in the editor")
    }

    private fun waitForTag(
        tag: String,
        shouldExist: Boolean = true,
        timeoutMillis: Long = 10_000,
    ) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            val exists = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            if (shouldExist) exists else !exists
        }
    }
}

private fun createEditorLaunchIntent(): Intent =
    Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        putOnboardingTestFixture(OnboardingTestFixture.ONBOARDED_HOME)
        putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_NEW_ENTRY)
    }

private fun getActivityFromScenarioRule(activityRule: ActivityScenarioRule<MainActivity>): MainActivity {
    var activity: MainActivity? = null
    activityRule.scenario.onActivity { launchedActivity ->
        activity = launchedActivity
    }
    return checkNotNull(activity) { "MainActivity was not available from ActivityScenarioRule" }
}
