package app.logdate.feature.onboarding.flow

import app.logdate.client.domain.dayboundary.HealthConnectStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the onboarding flow planning logic.
 *
 * This suite validates the state machine used to determine the sequence of
 * onboarding steps based on the user's entry mode (fresh vs. returning) and
 * the current device's configuration state.
 */
class OnboardingFlowPlannerTest {
    @kotlin.test.Test
    fun `watch setup follows notifications only when available and unhandled`() {
        val present = OnboardingProgressSnapshot(watchAvailable = true)
        kotlin.test.assertEquals(
            OnboardingStep.WATCH,
            nextOnboardingStepAfter(OnboardingStep.NOTIFICATIONS, OnboardingEntryMode.FRESH, present),
        )
        kotlin.test.assertEquals(
            OnboardingStep.COMPLETE,
            nextOnboardingStepAfter(OnboardingStep.NOTIFICATIONS, OnboardingEntryMode.FRESH, present.copy(watchHandledOnThisDevice = true)),
        )
        kotlin.test.assertEquals(
            OnboardingStep.COMPLETE,
            nextOnboardingStepAfter(OnboardingStep.NOTIFICATIONS, OnboardingEntryMode.FRESH, present.copy(watchAvailable = false)),
        )
    }

    @Test
    fun `fresh flow includes full setup sequence when nothing is configured`() {
        val snapshot = OnboardingProgressSnapshot()

        assertEquals(
            listOf(
                OnboardingStep.PERSONAL_INTRO,
                OnboardingStep.APP_OVERVIEW,
                OnboardingStep.MEMORY_IMPORT,
                OnboardingStep.MEMORY_SELECTION,
                OnboardingStep.ACCOUNT,
                OnboardingStep.BIRTHDAY,
                OnboardingStep.RECOMMENDATIONS,
                OnboardingStep.DAY_BOUNDARIES,
                OnboardingStep.LOCATION,
                OnboardingStep.NOTIFICATIONS,
                OnboardingStep.COMPLETE,
            ),
            plannedSteps(
                entryMode = OnboardingEntryMode.FRESH,
                snapshot = snapshot,
            ),
        )
    }

    @Test
    fun `fresh flow never asks for a recovery phrase`() {
        // The identity key is provisioned for the device (OnboardingViewModel), so there is
        // nothing for a fresh user to recover and nothing to block them on. Reading the phrase
        // is a settings action, not an onboarding gate.
        val snapshot = OnboardingProgressSnapshot()

        assertFalse(
            plannedSteps(
                entryMode = OnboardingEntryMode.FRESH,
                snapshot = snapshot,
            ).contains(OnboardingStep.RECOVERY_PHRASE),
        )
        assertNotEquals(
            OnboardingStep.RECOVERY_PHRASE,
            snapshot.firstIncompleteRequiredOnboardingStep(),
        )
    }

    @Test
    fun `fresh flow skips day boundaries when health connect is unavailable`() {
        val snapshot =
            OnboardingProgressSnapshot(
                healthConnectStatus = HealthConnectStatus.NOT_AVAILABLE,
            )

        assertFalse(
            plannedSteps(
                entryMode = OnboardingEntryMode.FRESH,
                snapshot = snapshot,
            ).contains(OnboardingStep.DAY_BOUNDARIES),
        )
    }

    @Test
    fun `continue setup flow only includes unresolved setup steps`() {
        val snapshot =
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
                hasBirthday = false,
                notificationsHandledOnThisDevice = false,
                recommendationsHandledOnThisDevice = true,
                locationHandledOnThisDevice = true,
                dayBoundariesHandledOnThisDevice = true,
            )

        assertEquals(
            listOf(
                OnboardingStep.ACCOUNT,
                OnboardingStep.BIRTHDAY,
                OnboardingStep.NOTIFICATIONS,
                OnboardingStep.WELCOME_BACK,
            ),
            plannedSteps(
                entryMode = OnboardingEntryMode.CONTINUE_SETUP,
                snapshot = snapshot,
            ),
        )
    }

    @Test
    fun `continue setup flow can finish immediately when required setup is already satisfied`() {
        val snapshot =
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
                hasCloudAccount = true,
                hasBirthday = true,
                notificationsHandledOnThisDevice = true,
                recommendationsHandledOnThisDevice = true,
                locationHandledOnThisDevice = true,
                dayBoundariesHandledOnThisDevice = true,
            )

        assertEquals(
            OnboardingStep.WELCOME_BACK,
            plannedSteps(
                entryMode = OnboardingEntryMode.CONTINUE_SETUP,
                snapshot = snapshot,
            ).first(),
        )
    }

    @Test
    fun `continue setup flow starts with account before unresolved profile steps`() {
        val snapshot = OnboardingProgressSnapshot()

        assertEquals(
            OnboardingStep.PERSONAL_INTRO,
            plannedSteps(
                entryMode = OnboardingEntryMode.CONTINUE_SETUP,
                snapshot = snapshot,
            ).first(),
        )
    }

    @Test
    fun `completion eligibility requires all canonical setup steps to be handled`() {
        assertFalse(
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
                hasBirthday = true,
                recommendationsHandledOnThisDevice = true,
                locationHandledOnThisDevice = true,
                dayBoundariesHandledOnThisDevice = false,
                notificationsHandledOnThisDevice = true,
            ).canCompleteOnboarding(),
        )

        assertTrue(
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
                hasBirthday = true,
                recommendationsHandledOnThisDevice = true,
                locationHandledOnThisDevice = true,
                dayBoundariesHandledOnThisDevice = true,
                notificationsHandledOnThisDevice = true,
            ).canCompleteOnboarding(),
        )
    }

    @Test
    fun `fresh flow advances from personal intro to app overview after intro is saved`() {
        val snapshot =
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
            )

        assertEquals(
            OnboardingStep.APP_OVERVIEW,
            nextOnboardingStepAfter(
                currentStep = OnboardingStep.PERSONAL_INTRO,
                entryMode = OnboardingEntryMode.FRESH,
                snapshot = snapshot,
            ),
        )
    }

    @Test
    fun `fresh flow advances from birthday to recommendations after birthday is saved`() {
        val snapshot =
            OnboardingProgressSnapshot(
                hasBirthday = true,
            )

        assertEquals(
            OnboardingStep.RECOMMENDATIONS,
            nextOnboardingStepAfter(
                currentStep = OnboardingStep.BIRTHDAY,
                entryMode = OnboardingEntryMode.FRESH,
                snapshot = snapshot,
            ),
        )
    }

    @Test
    fun `continue setup flow includes unresolved setup choices after birthday`() {
        val snapshot =
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
                hasBirthday = true,
                healthConnectStatus = HealthConnectStatus.NOT_AVAILABLE,
            )

        assertEquals(
            listOf(
                OnboardingStep.ACCOUNT,
                OnboardingStep.RECOMMENDATIONS,
                OnboardingStep.LOCATION,
                OnboardingStep.NOTIFICATIONS,
                OnboardingStep.WELCOME_BACK,
            ),
            plannedSteps(
                entryMode = OnboardingEntryMode.CONTINUE_SETUP,
                snapshot = snapshot,
            ),
        )
    }

    @Test
    fun `skipping account setup is remembered unlike a never-visited step`() {
        // Every other optional step has a *HandledOnThisDevice flag so skipping it is
        // remembered; account setup used to be the exception, reappearing every re-entry.
        val skipped =
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
                accountHandledOnThisDevice = true,
            )
        val neverVisited =
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
            )

        assertFalse(
            plannedSteps(
                entryMode = OnboardingEntryMode.CONTINUE_SETUP,
                snapshot = skipped,
            ).contains(OnboardingStep.ACCOUNT),
        )
        assertTrue(
            plannedSteps(
                entryMode = OnboardingEntryMode.CONTINUE_SETUP,
                snapshot = neverVisited,
            ).contains(OnboardingStep.ACCOUNT),
        )
    }

    @Test
    fun `completion guard redirects to recommendations before notifications when unresolved`() {
        assertEquals(
            OnboardingStep.RECOMMENDATIONS,
            OnboardingProgressSnapshot(
                hasPersonalIntro = true,
                hasBirthday = true,
                notificationsHandledOnThisDevice = true,
            ).firstIncompleteRequiredOnboardingStep(),
        )
    }

    private fun plannedSteps(
        entryMode: OnboardingEntryMode,
        snapshot: OnboardingProgressSnapshot,
    ): List<OnboardingStep> =
        generateSequence(
            if (snapshot.hasPersonalIntro) {
                nextOnboardingStepAfter(OnboardingStep.PERSONAL_INTRO, entryMode, snapshot)
            } else {
                OnboardingStep.PERSONAL_INTRO
            },
        ) { step -> nextOnboardingStepAfter(step, entryMode, snapshot) }.toList()
}
