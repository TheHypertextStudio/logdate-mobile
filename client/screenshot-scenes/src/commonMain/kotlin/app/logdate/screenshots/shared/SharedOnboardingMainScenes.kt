@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import app.logdate.client.domain.dayboundary.HealthConnectGateKind
import app.logdate.client.domain.dayboundary.HealthConnectGateState
import app.logdate.feature.onboarding.ui.CloudAccountSetupContent
import app.logdate.feature.onboarding.ui.MemoriesImportInfoScreen
import app.logdate.feature.onboarding.ui.MemorySelectionScreen
import app.logdate.feature.onboarding.ui.MemorySelectionUiState
import app.logdate.feature.onboarding.ui.OnboardingBirthdayContent
import app.logdate.feature.onboarding.ui.OnboardingCompletionContent
import app.logdate.feature.onboarding.ui.OnboardingDayBoundariesContent
import app.logdate.feature.onboarding.ui.OnboardingLocationContent
import app.logdate.feature.onboarding.ui.OnboardingNotificationsContent
import app.logdate.feature.onboarding.ui.OnboardingOverviewScreen
import app.logdate.feature.onboarding.ui.OnboardingRecommendationsContent
import app.logdate.feature.onboarding.ui.OnboardingStartScreenContent
import app.logdate.feature.onboarding.ui.PersonalIntroContent
import app.logdate.feature.onboarding.ui.PersonalIntroStep
import app.logdate.feature.onboarding.ui.PersonalIntroUiState
import app.logdate.feature.onboarding.ui.WelcomeBackScreenContent

internal val catalogOnboardingMainScenes: List<SharedScreenshotSceneSpec> =
    listOf(
        sharedScene(SharedScreenshotSceneId.OnboardingStartSplash, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            OnboardingStartScreenContent(
                showLanding = false,
                onGetStarted = {},
                onSignIn = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingStartLanding, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            OnboardingStartScreenContent(
                showLanding = true,
                onGetStarted = {},
                onSignIn = {},
                animateContent = false,
            )
        },
        sharedScene(SharedScreenshotSceneId.PersonalIntroName, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            PersonalIntroContent(
                uiState =
                    PersonalIntroUiState(
                        name = "Alex",
                        currentStep = PersonalIntroStep.Name,
                    ),
                onNameChanged = {},
                onBioChanged = {},
                onProceedToBio = {},
                onGoBackToName = {},
                onProcessWithLlm = {},
                onBack = {},
                autoFocusInputs = false,
                animateStepTransitions = false,
            )
        },
        sharedScene(SharedScreenshotSceneId.PersonalIntroBio, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            PersonalIntroContent(
                uiState =
                    PersonalIntroUiState(
                        name = "Alex",
                        bio = "I keep a private timeline of the people, places, and moments that matter.",
                        currentStep = PersonalIntroStep.Bio,
                    ),
                onNameChanged = {},
                onBioChanged = {},
                onProceedToBio = {},
                onGoBackToName = {},
                onProcessWithLlm = {},
                onBack = {},
                autoFocusInputs = false,
                animateStepTransitions = false,
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingOverview, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingOverviewScreen(onBack = {}, onNext = {})
        },
        sharedScene(SharedScreenshotSceneId.MemoriesImportInfo, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            MemoriesImportInfoScreen(onBack = {}, onContinue = {})
        },
        sharedScene(SharedScreenshotSceneId.MemorySelectionPopulated, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            val memories = sampleMemories()
            MemorySelectionScreen(
                uiState =
                    MemorySelectionUiState(
                        allMemories = memories,
                        aiCuratedMemories = memories.take(6),
                        selectedMemoryIds = setOf("sample1", "sample5"),
                        isLoading = false,
                        hasMoreMemories = true,
                    ),
                onBack = {},
                onContinue = {},
                onToggleMemorySelection = {},
                onLoadMoreMemories = {},
                onRefreshMemories = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.CloudAccountSetupCompact, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            CloudAccountSetupContent(
                onBack = {},
                onContinue = {},
                onSkip = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingBirthday, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingBirthdayContent(
                onBack = {},
                onBirthdaySelected = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingRecommendations, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingRecommendationsContent(
                onBack = {},
                onKeepOn = {},
                onTurnOff = {},
            )
        },
        sharedScene(
            SharedScreenshotSceneId.OnboardingDayBoundariesConnected,
            ScreenshotSceneGroup.ONBOARDING,
            onboardingStepVariants,
        ) {
            OnboardingDayBoundariesContent(
                gateState = HealthConnectGateState(HealthConnectGateKind.READY),
                onBack = {},
                onEnable = {},
                onSkip = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingLocation, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingLocationContent(
                onBack = {},
                onEnable = {},
                onSkip = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingNotifications, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingNotificationsContent(
                onBack = {},
                onPrimaryAction = {},
                onSkip = {},
                recommendationsEnabled = true,
                hasDecision = false,
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingCompletionCampfire, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingCompletionCampfireScene()
        },
        sharedScene(SharedScreenshotSceneId.OnboardingCompletionFinal, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            OnboardingCompletionContent(
                shouldShowFinish = true,
                onContinue = {},
                onFinish = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.OnboardingWelcomeBack, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            WelcomeBackScreenContent(name = "Alex")
        },
    )
