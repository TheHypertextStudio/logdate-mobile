package app.logdate.feature.onboarding.flow

import app.logdate.client.domain.dayboundary.HealthConnectStatus

enum class OnboardingEntryMode {
    FRESH,
    CONTINUE_SETUP,
}

enum class OnboardingStep {
    PERSONAL_INTRO,
    APP_OVERVIEW,
    MEMORY_IMPORT,
    MEMORY_SELECTION,
    ACCOUNT,

    /**
     * Parked: deliberately absent from [onboardingStepOrderFor], so nothing navigates here today.
     * The key is provisioned for the device instead, and the phrase is read from privacy settings.
     * Kept for the recovery flow that offers it when content is found that this device cannot
     * decrypt -- restoring an identity, rather than gating a new one.
     */
    RECOVERY_PHRASE,
    BIRTHDAY,
    RECOMMENDATIONS,
    DAY_BOUNDARIES,
    LOCATION,
    NOTIFICATIONS,
    WATCH,
    COMPLETE,
    WELCOME_BACK,
}

data class OnboardingProgressSnapshot(
    val hasPersonalIntro: Boolean = false,
    val hasBirthday: Boolean = false,
    val hasCloudAccount: Boolean = false,
    val accountHandledOnThisDevice: Boolean = false,
    val recommendationsHandledOnThisDevice: Boolean = false,
    val contextualRecommendationsEnabled: Boolean = true,
    val dayBoundariesHandledOnThisDevice: Boolean = false,
    val sleepBasedDayBoundariesEnabled: Boolean = false,
    val locationHandledOnThisDevice: Boolean = false,
    val locationTrackingEnabled: Boolean = false,
    val notificationsHandledOnThisDevice: Boolean = false,
    val watchAvailable: Boolean = false,
    val watchHandledOnThisDevice: Boolean = false,
    val healthConnectStatus: HealthConnectStatus = HealthConnectStatus.CHECKING,
)

fun nextOnboardingStepAfter(
    currentStep: OnboardingStep,
    entryMode: OnboardingEntryMode,
    snapshot: OnboardingProgressSnapshot,
): OnboardingStep? {
    val steps = onboardingStepOrderFor(entryMode, snapshot.healthConnectStatus)
    val currentIndex = steps.indexOf(currentStep)
    if (currentIndex < 0 || currentIndex == steps.lastIndex) {
        return null
    }
    return steps
        .drop(currentIndex + 1)
        .firstOrNull { step -> shouldIncludeStep(step, snapshot) }
}

private fun onboardingStepOrderFor(
    entryMode: OnboardingEntryMode,
    healthConnectStatus: HealthConnectStatus,
): List<OnboardingStep> =
    buildList {
        add(OnboardingStep.PERSONAL_INTRO)
        if (entryMode == OnboardingEntryMode.FRESH) {
            add(OnboardingStep.APP_OVERVIEW)
            add(OnboardingStep.MEMORY_IMPORT)
            add(OnboardingStep.MEMORY_SELECTION)
        }
        add(OnboardingStep.ACCOUNT)
        add(OnboardingStep.BIRTHDAY)
        add(OnboardingStep.RECOMMENDATIONS)
        if (healthConnectStatus != HealthConnectStatus.NOT_AVAILABLE) {
            add(OnboardingStep.DAY_BOUNDARIES)
        }
        add(OnboardingStep.LOCATION)
        add(OnboardingStep.NOTIFICATIONS)
        add(OnboardingStep.WATCH)
        add(terminalStepFor(entryMode))
    }

private fun shouldIncludeStep(
    step: OnboardingStep,
    snapshot: OnboardingProgressSnapshot,
): Boolean =
    when (step) {
        OnboardingStep.PERSONAL_INTRO -> !snapshot.hasPersonalIntro
        OnboardingStep.ACCOUNT -> !snapshot.hasResolvedAccount()
        OnboardingStep.BIRTHDAY -> !snapshot.hasBirthday
        OnboardingStep.RECOMMENDATIONS -> !snapshot.hasResolvedRecommendations()
        OnboardingStep.DAY_BOUNDARIES -> !snapshot.hasResolvedDayBoundaries()
        OnboardingStep.LOCATION -> !snapshot.hasResolvedLocation()
        OnboardingStep.NOTIFICATIONS -> !snapshot.notificationsHandledOnThisDevice
        OnboardingStep.WATCH -> snapshot.watchAvailable && !snapshot.watchHandledOnThisDevice
        else -> true
    }

private fun OnboardingProgressSnapshot.hasResolvedAccount(): Boolean =
    hasCloudAccount ||
        accountHandledOnThisDevice

private fun OnboardingProgressSnapshot.hasResolvedRecommendations(): Boolean =
    recommendationsHandledOnThisDevice || !contextualRecommendationsEnabled

private fun OnboardingProgressSnapshot.hasResolvedDayBoundaries(): Boolean =
    healthConnectStatus == HealthConnectStatus.NOT_AVAILABLE ||
        dayBoundariesHandledOnThisDevice ||
        sleepBasedDayBoundariesEnabled

private fun OnboardingProgressSnapshot.hasResolvedLocation(): Boolean =
    locationHandledOnThisDevice ||
        locationTrackingEnabled

private fun terminalStepFor(entryMode: OnboardingEntryMode): OnboardingStep =
    when (entryMode) {
        OnboardingEntryMode.FRESH -> OnboardingStep.COMPLETE
        OnboardingEntryMode.CONTINUE_SETUP -> OnboardingStep.WELCOME_BACK
    }
