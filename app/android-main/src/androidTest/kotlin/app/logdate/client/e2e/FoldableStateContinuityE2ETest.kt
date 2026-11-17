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
 * Instrumented E2E coverage that the editor preserves in-progress input across both a posture
 * publish (foldable unfold/fold) and a full activity recreation.
 *
 * Unfolding into book posture changes the window layout the same way a configuration change
 * does, so any hinge-aware layout work must not drop the user's draft. This suite types into the
 * editor text input, publishes a separating book hinge, and asserts the text survives — then
 * forces an [ActivityScenarioRule] recreate and asserts it survives that too.
 */
@RunWith(AndroidJUnit4::class)
class FoldableStateContinuityE2ETest {
    private val postureSupport = FoldablePostureTestSupport()
    private val koinRule = OnboardingKoinModuleOverrideRule(module {})
    private val activityRule = ActivityScenarioRule<MainActivity>(createEditorLaunchIntent())
    private val composeRule = AndroidComposeTestRule(activityRule, ::continuityActivity)

    @get:Rule
    val ruleChain: RuleChain =
        RuleChain
            .outerRule(koinRule)
            .around(postureSupport.publisherRule)
            .around(composeRule)

    @Test
    fun `editor text survives book posture publish`() {
        openEditorAndType(DRAFT_TEXT)

        composeRule.activityRule.scenario.onActivity { activity ->
            postureSupport.publishBookPosture(activity)
        }
        composeRule.waitForIdle()

        waitForTag(EDITOR_TEXT_INPUT_TAG)
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).assertTextContains(DRAFT_TEXT)
    }

    @Test
    fun `editor text survives posture publish then recreation`() {
        openEditorAndType(DRAFT_TEXT)

        composeRule.activityRule.scenario.onActivity { activity ->
            postureSupport.publishBookPosture(activity)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).assertTextContains(DRAFT_TEXT)

        composeRule.activityRule.scenario.recreate()

        waitForTag(EDITOR_TEXT_INPUT_TAG)
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).assertTextContains(DRAFT_TEXT)
    }

    @Test
    fun `editor text survives unfold then refold`() {
        openEditorAndType(DRAFT_TEXT)

        composeRule.activityRule.scenario.onActivity { activity ->
            postureSupport.publishBookPosture(activity)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).assertTextContains(DRAFT_TEXT)

        composeRule.activityRule.scenario.onActivity { _ ->
            postureSupport.publishFlat()
        }
        composeRule.waitForIdle()

        waitForTag(EDITOR_TEXT_INPUT_TAG)
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).assertTextContains(DRAFT_TEXT)
    }

    private fun openEditorAndType(text: String) {
        // A new entry opens on its writing, or on the row of ways to add something, which comes in
        // once the entry has loaded. Writing is the row's Write choice.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(EDITOR_TEXT_INPUT_TAG).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithContentDescription(START_TEXT_ENTRY_DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
        }
        if (composeRule.onAllNodesWithTag(EDITOR_TEXT_INPUT_TAG).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription(START_TEXT_ENTRY_DESCRIPTION).performClick()
        }
        waitForTag(EDITOR_TEXT_INPUT_TAG)
        composeRule.onNodeWithTag(EDITOR_TEXT_INPUT_TAG).performTextInput(text)
        composeRule.waitForIdle()
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

    private companion object {
        const val EDITOR_TEXT_INPUT_TAG = "editor_text_input"
        const val START_TEXT_ENTRY_DESCRIPTION = "Write"
        const val DRAFT_TEXT = "Folded draft that must survive the hinge"
    }
}

private fun createEditorLaunchIntent(): Intent =
    Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        putOnboardingTestFixture(OnboardingTestFixture.ONBOARDED_HOME)
        putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_NEW_ENTRY)
    }

private fun continuityActivity(activityRule: ActivityScenarioRule<MainActivity>): MainActivity {
    var activity: MainActivity? = null
    activityRule.scenario.onActivity { launchedActivity ->
        activity = launchedActivity
    }
    return checkNotNull(activity) { "MainActivity was not available from ActivityScenarioRule" }
}
