package app.logdate.client.e2e

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.MainActivity
import app.logdate.client.testing.launch.ActivityLaunchTestOverrides
import app.logdate.client.testing.onboarding.OnboardingTestFixture
import app.logdate.feature.onboarding.ui.CLOUD_ACCOUNT_SETUP_ROOT_TAG
import app.logdate.feature.onboarding.ui.CLOUD_ACCOUNT_SETUP_SKIP_ACTION_TAG
import app.logdate.feature.onboarding.ui.MEMORIES_IMPORT_INFO_CONTINUE_TAG
import app.logdate.feature.onboarding.ui.MEMORIES_IMPORT_INFO_ROOT_TAG
import app.logdate.feature.onboarding.ui.MEMORY_SELECTION_CONTINUE_TAG
import app.logdate.feature.onboarding.ui.MEMORY_SELECTION_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_BIRTHDAY_CONFIRM_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_BIRTHDAY_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_BIRTHDAY_SET_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_COMPLETION_CONTINUE_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_COMPLETION_FINAL_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_COMPLETION_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_DAY_BOUNDARIES_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_LOCATION_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_LOCATION_SKIP_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_NOTIFICATIONS_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_NOTIFICATIONS_SKIP_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_OVERVIEW_CONTINUE_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_OVERVIEW_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_RECOMMENDATIONS_KEEP_ON_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_RECOMMENDATIONS_ROOT_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_RECOMMENDATIONS_TURN_OFF_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_START_FROM_BACKUP_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_START_GET_STARTED_TAG
import app.logdate.feature.onboarding.ui.ONBOARDING_START_ROOT_TAG
import app.logdate.feature.onboarding.ui.PERSONAL_INTRO_BIO_CONTINUE_TAG
import app.logdate.feature.onboarding.ui.PERSONAL_INTRO_BIO_FIELD_TAG
import app.logdate.feature.onboarding.ui.PERSONAL_INTRO_NAME_CONTINUE_TAG
import app.logdate.feature.onboarding.ui.PERSONAL_INTRO_NAME_FIELD_TAG
import app.logdate.feature.onboarding.ui.PERSONAL_INTRO_ROOT_TAG
import app.logdate.feature.onboarding.ui.WELCOME_BACK_ROOT_TAG
import io.github.aakira.napier.Napier
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.time.LocalDate

/**
 * Comprehensive E2E tests for the multi-step user onboarding journey.
 *
 * This suite walks through the entire first-run experience, verifying that
 * profile creation, feature education, permission requests, and cloud setup
 * steps are correctly sequenced and maintain state across configuration changes.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingJourneyE2ETest {
    private val environment = OnboardingJourneyEnvironment()
    private val koinRule = OnboardingKoinModuleOverrideRule(environment.module)
    private val launchOverrideRule = OnboardingLaunchOverrideRule()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule).around(launchOverrideRule).around(composeRule)

    @Test
    fun `fresh onboarding journey reaches completion screen and captures screens`() {
        advanceToStartActions()
        capture(ONBOARDING_START_ROOT_TAG, "fresh", "01_start.png")
        composeRule.onNodeWithTag(ONBOARDING_START_GET_STARTED_TAG).performClick()
        waitForTag(PERSONAL_INTRO_ROOT_TAG)

        composeRule.onNodeWithTag(PERSONAL_INTRO_ROOT_TAG).assertIsDisplayed()
        capture(PERSONAL_INTRO_ROOT_TAG, "fresh", "02_personal_intro.png")
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_FIELD_TAG).performTextInput("Willie")
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_CONTINUE_TAG).performClick()
        waitForTag(PERSONAL_INTRO_BIO_FIELD_TAG)
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_FIELD_TAG).performTextInput("I like journaling and long walks.")
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_CONTINUE_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(2_200)
        composeRule.waitForIdle()
        waitForTag(ONBOARDING_OVERVIEW_ROOT_TAG)

        composeRule.onNodeWithTag(ONBOARDING_OVERVIEW_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_OVERVIEW_ROOT_TAG, "fresh", "03_overview.png")
        composeRule.onNodeWithTag(ONBOARDING_OVERVIEW_CONTINUE_TAG).performScrollTo()
        composeRule.onNodeWithTag(ONBOARDING_OVERVIEW_CONTINUE_TAG).performClick()
        waitForTag(MEMORIES_IMPORT_INFO_ROOT_TAG)

        composeRule.onNodeWithTag(MEMORIES_IMPORT_INFO_ROOT_TAG).assertIsDisplayed()
        capture(MEMORIES_IMPORT_INFO_ROOT_TAG, "fresh", "04_memory_import.png")
        composeRule.onNodeWithTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG).performScrollTo()
        composeRule.onNodeWithTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG).performClick()
        waitForTag(MEMORY_SELECTION_ROOT_TAG)

        composeRule.onNodeWithTag(MEMORY_SELECTION_ROOT_TAG).assertIsDisplayed()
        capture(MEMORY_SELECTION_ROOT_TAG, "fresh", "05_memory_selection.png")
        // The Continue button is pinned to the Scaffold's bottomBar, not inside the paginating
        // list, so it is always on screen without needing to scroll to it.
        composeRule.onNodeWithTag(MEMORY_SELECTION_CONTINUE_TAG).performClick()
        waitForTag(CLOUD_ACCOUNT_SETUP_ROOT_TAG)

        composeRule.onNodeWithTag(CLOUD_ACCOUNT_SETUP_ROOT_TAG).assertIsDisplayed()
        capture(CLOUD_ACCOUNT_SETUP_ROOT_TAG, "fresh", "06_backup_sync.png")
        skipCloudAccountSetup()
        waitForTag(ONBOARDING_BIRTHDAY_ROOT_TAG)

        composeRule.onNodeWithTag(ONBOARDING_BIRTHDAY_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_BIRTHDAY_ROOT_TAG, "fresh", "07_birthday.png")
        selectBirthday()

        composeRule.onNodeWithTag(ONBOARDING_RECOMMENDATIONS_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_RECOMMENDATIONS_ROOT_TAG, "fresh", "08_recommendations.png")
        composeRule.onNodeWithTag(ONBOARDING_RECOMMENDATIONS_TURN_OFF_TAG).performClick()

        composeRule.onNodeWithTag(ONBOARDING_LOCATION_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_LOCATION_ROOT_TAG, "fresh", "09_location.png")
        composeRule.onNodeWithTag(ONBOARDING_LOCATION_SKIP_TAG).performClick()

        composeRule.onNodeWithTag(ONBOARDING_NOTIFICATIONS_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_NOTIFICATIONS_ROOT_TAG, "fresh", "10_notifications.png")
        composeRule.onNodeWithTag(ONBOARDING_NOTIFICATIONS_SKIP_TAG).performScrollTo()
        composeRule.onNodeWithTag(ONBOARDING_NOTIFICATIONS_SKIP_TAG).performClick()

        composeRule.onNodeWithTag(ONBOARDING_COMPLETION_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_COMPLETION_ROOT_TAG, "fresh", "11_completion_streak.png")
        composeRule.onNodeWithTag(ONBOARDING_COMPLETION_CONTINUE_TAG).performScrollTo()
        composeRule.onNodeWithTag(ONBOARDING_COMPLETION_CONTINUE_TAG).performClick()

        composeRule.onNodeWithTag(ONBOARDING_COMPLETION_FINAL_TAG).assertIsDisplayed()
        capture(ONBOARDING_COMPLETION_FINAL_TAG, "fresh", "12_completion_final.png")
    }

    @Test
    fun `personal intro state and current step survive activity recreation`() {
        advanceToStartActions()
        composeRule.onNodeWithTag(ONBOARDING_START_GET_STARTED_TAG).performClick()
        waitForTag(PERSONAL_INTRO_ROOT_TAG)

        composeRule.onNodeWithTag(PERSONAL_INTRO_ROOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_FIELD_TAG).performTextInput("Willie")
        composeRule.activityRule.scenario.recreate()
        waitForTag(PERSONAL_INTRO_ROOT_TAG)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PERSONAL_INTRO_ROOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_FIELD_TAG).assertTextContains("Willie")

        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_CONTINUE_TAG).performClick()
        waitForTag(PERSONAL_INTRO_BIO_FIELD_TAG)
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_FIELD_TAG).performTextInput("I write every day.")
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        waitForTag(PERSONAL_INTRO_BIO_FIELD_TAG)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PERSONAL_INTRO_ROOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_FIELD_TAG).assertTextContains("I write every day.")
        capture(PERSONAL_INTRO_ROOT_TAG, "rotation", "01_personal_intro_bio_landscape.png")
    }

    @Test
    fun `birthday step survives activity recreation and continues forward`() {
        advanceToStartActions()
        composeRule.onNodeWithTag(ONBOARDING_START_GET_STARTED_TAG).performClick()
        waitForTag(PERSONAL_INTRO_NAME_FIELD_TAG)
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_FIELD_TAG).performTextInput("Willie")
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_CONTINUE_TAG).performClick()
        waitForTag(PERSONAL_INTRO_BIO_FIELD_TAG)
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_FIELD_TAG).performTextInput("I want onboarding to survive rotation.")
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_CONTINUE_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(2_200)
        composeRule.waitForIdle()
        waitForTag(ONBOARDING_OVERVIEW_ROOT_TAG)

        composeRule.onNodeWithTag(ONBOARDING_OVERVIEW_CONTINUE_TAG).performScrollTo()
        composeRule.onNodeWithTag(ONBOARDING_OVERVIEW_CONTINUE_TAG).performClick()
        waitForTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG)
        composeRule.onNodeWithTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG).performScrollTo()
        composeRule.onNodeWithTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG).performClick()
        waitForTag(MEMORY_SELECTION_CONTINUE_TAG)
        // The Continue button is pinned to the Scaffold's bottomBar, not inside the paginating
        // list, so it is always on screen without needing to scroll to it.
        composeRule.onNodeWithTag(MEMORY_SELECTION_CONTINUE_TAG).performClick()
        skipCloudAccountSetup()
        waitForTag(ONBOARDING_BIRTHDAY_ROOT_TAG)

        composeRule.onNodeWithTag(ONBOARDING_BIRTHDAY_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_BIRTHDAY_ROOT_TAG, "rotation", "02_birthday_before_recreate.png")
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ONBOARDING_BIRTHDAY_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_BIRTHDAY_ROOT_TAG, "rotation", "03_birthday_after_recreate.png")
        selectBirthday()

        composeRule.onNodeWithTag(ONBOARDING_RECOMMENDATIONS_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_RECOMMENDATIONS_ROOT_TAG, "rotation", "04_recommendations_after_birthday.png")
    }

    @Test
    fun `continue setup flow uses canonical setup order and reaches welcome back`() {
        logStep("continue-setup: waiting for onboarding start")
        advanceToStartActions()
        logStep("continue-setup: tapping start-from-backup")
        composeRule.onNodeWithTag(ONBOARDING_START_FROM_BACKUP_TAG).performClick()
        logStep("continue-setup: waiting for personal intro root")
        waitForTag(PERSONAL_INTRO_ROOT_TAG)

        composeRule.onNodeWithTag(PERSONAL_INTRO_ROOT_TAG).assertIsDisplayed()
        capture(PERSONAL_INTRO_ROOT_TAG, "continue_setup", "01_personal_intro.png")
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_FIELD_TAG).performTextInput("Willie")
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_CONTINUE_TAG).performClick()
        waitForTag(PERSONAL_INTRO_BIO_FIELD_TAG)
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_FIELD_TAG).performTextInput("I restore my apps carefully.")
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_CONTINUE_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(2_200)
        composeRule.waitForIdle()
        waitForTag(CLOUD_ACCOUNT_SETUP_ROOT_TAG)

        composeRule.onNodeWithTag(CLOUD_ACCOUNT_SETUP_ROOT_TAG).assertIsDisplayed()
        capture(CLOUD_ACCOUNT_SETUP_ROOT_TAG, "continue_setup", "02_account.png")
        skipCloudAccountSetup()
        waitForTag(ONBOARDING_BIRTHDAY_ROOT_TAG)

        composeRule.onNodeWithTag(ONBOARDING_BIRTHDAY_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_BIRTHDAY_ROOT_TAG, "continue_setup", "03_birthday.png")
        selectBirthday()

        composeRule.onNodeWithTag(ONBOARDING_RECOMMENDATIONS_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_RECOMMENDATIONS_ROOT_TAG, "continue_setup", "04_recommendations.png")
        composeRule.onNodeWithTag(ONBOARDING_RECOMMENDATIONS_KEEP_ON_TAG).performClick()

        composeRule.onNodeWithTag(ONBOARDING_LOCATION_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_LOCATION_ROOT_TAG, "continue_setup", "05_location.png")
        composeRule.onNodeWithTag(ONBOARDING_LOCATION_SKIP_TAG).performClick()

        composeRule.onNodeWithTag(ONBOARDING_NOTIFICATIONS_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_NOTIFICATIONS_ROOT_TAG, "continue_setup", "06_notifications.png")
        composeRule.onNodeWithTag(ONBOARDING_NOTIFICATIONS_SKIP_TAG).performClick()

        composeRule.onNodeWithTag(WELCOME_BACK_ROOT_TAG).assertIsDisplayed()
        capture(WELCOME_BACK_ROOT_TAG, "continue_setup", "07_welcome_back.png")
    }

    @Test
    fun `fresh flow shows day boundaries when health connect is available`() {
        environment.healthRepository.available = true
        environment.healthRepository.sleepPermissionsGranted = false

        advanceToStartActions()
        composeRule.onNodeWithTag(ONBOARDING_START_GET_STARTED_TAG).performClick()
        waitForTag(PERSONAL_INTRO_NAME_FIELD_TAG)
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_FIELD_TAG).performTextInput("Willie")
        composeRule.onNodeWithTag(PERSONAL_INTRO_NAME_CONTINUE_TAG).performClick()
        waitForTag(PERSONAL_INTRO_BIO_FIELD_TAG)
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_FIELD_TAG).performTextInput("I want sleep-aware day boundaries.")
        composeRule.onNodeWithTag(PERSONAL_INTRO_BIO_CONTINUE_TAG).performClick()
        composeRule.mainClock.advanceTimeBy(2_200)
        composeRule.waitForIdle()
        waitForTag(ONBOARDING_OVERVIEW_CONTINUE_TAG)

        composeRule.onNodeWithTag(ONBOARDING_OVERVIEW_CONTINUE_TAG).performClick()
        waitForTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG)
        composeRule.onNodeWithTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG).performScrollTo()
        composeRule.onNodeWithTag(MEMORIES_IMPORT_INFO_CONTINUE_TAG).performClick()
        waitForTag(MEMORY_SELECTION_CONTINUE_TAG)
        // The Continue button is pinned to the Scaffold's bottomBar, not inside the paginating
        // list, so it is always on screen without needing to scroll to it.
        composeRule.onNodeWithTag(MEMORY_SELECTION_CONTINUE_TAG).performClick()
        skipCloudAccountSetup()
        waitForTag(ONBOARDING_BIRTHDAY_ROOT_TAG)
        selectBirthday()

        composeRule.onNodeWithTag(ONBOARDING_RECOMMENDATIONS_ROOT_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ONBOARDING_RECOMMENDATIONS_KEEP_ON_TAG).performClick()

        composeRule.onNodeWithTag(ONBOARDING_DAY_BOUNDARIES_ROOT_TAG).assertIsDisplayed()
        capture(ONBOARDING_DAY_BOUNDARIES_ROOT_TAG, "health_connect", "01_day_boundaries.png")
    }

    private fun advanceToStartActions() {
        composeRule.mainClock.autoAdvance = false
        waitForTag(ONBOARDING_START_ROOT_TAG)
        composeRule.mainClock.advanceTimeBy(3_200)
        composeRule.waitForIdle()
        waitForTag(ONBOARDING_START_GET_STARTED_TAG)
    }

    private fun waitForTag(
        tag: String,
        timeoutMillis: Long = 5_000,
    ) {
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            hasTag(tag)
        }
    }

    private fun hasTag(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun selectBirthday() {
        composeRule.onNodeWithTag(ONBOARDING_BIRTHDAY_SET_TAG).performScrollTo()
        composeRule.onNodeWithTag(ONBOARDING_BIRTHDAY_SET_TAG).performClick()
        composeRule.waitForIdle()
        val dayOfMonth = LocalDate.now().dayOfMonth.toString()
        composeRule.onAllNodesWithText(dayOfMonth, useUnmergedTree = true)[0].performClick()
        composeRule.onNodeWithTag(ONBOARDING_BIRTHDAY_CONFIRM_TAG).performClick()
        composeRule.waitForIdle()
    }

    private fun skipCloudAccountSetup() {
        waitForTag(CLOUD_ACCOUNT_SETUP_SKIP_ACTION_TAG)
        // The action buttons are pinned to the Scaffold's bottomBar, not inside the
        // scrollable plan list, so they are always on screen without needing to scroll.
        composeRule.onNodeWithTag(CLOUD_ACCOUNT_SETUP_SKIP_ACTION_TAG).performClick()
    }

    private fun capture(
        rootTag: String,
        testName: String,
        fileName: String,
    ) {
        composeRule.onNodeWithTag(rootTag).captureStepScreenshot(composeRule, testName, fileName)
    }

    private fun logStep(message: String) {
        Napier.i(message, tag = "OnboardingJourneyE2E")
    }
}

/**
 * A [TestRule] that overrides the activity launch configuration to use a specific
 * onboarding fixture during tests.
 */
internal class OnboardingLaunchOverrideRule : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                ActivityLaunchTestOverrides.onboardingFixture = OnboardingTestFixture.FRESH_ONBOARDING
                try {
                    base.evaluate()
                } finally {
                    ActivityLaunchTestOverrides.clear()
                }
            }
        }
}
