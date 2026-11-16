@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import app.logdate.client.domain.dayboundary.HealthConnectGateKind
import app.logdate.client.domain.dayboundary.HealthConnectGateState
import app.logdate.client.domain.dayboundary.HealthConnectMissingRequirement
import app.logdate.feature.core.account.CloudAccountSignInContent
import app.logdate.feature.core.account.CloudAccountWelcomeContent
import app.logdate.feature.core.account.PasskeyAccountCreationFinalContent
import app.logdate.feature.core.settings.ui.ServerSelectionState
import app.logdate.feature.onboarding.ui.MemorySelectionScreen
import app.logdate.feature.onboarding.ui.MemorySelectionUiState
import app.logdate.feature.onboarding.ui.OnboardingDayBoundariesContent
import app.logdate.feature.onboarding.ui.OnboardingNotificationsContent
import app.logdate.feature.onboarding.ui.OnboardingRecommendationsContent
import kotlin.time.Duration

internal val catalogOnboardingExtendedScenes: List<SharedScreenshotSceneSpec> =
    listOf(
        sharedScene(SharedScreenshotSceneId.MemorySelectionEmpty, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            MemorySelectionScreen(
                uiState = MemorySelectionUiState(isLoading = false, hasMoreMemories = false),
                onBack = {},
                onContinue = {},
                onToggleMemorySelection = {},
                onLoadMoreMemories = {},
                onRefreshMemories = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.MemorySelectionError, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            MemorySelectionScreen(
                uiState =
                    MemorySelectionUiState(
                        isLoading = false,
                        hasMoreMemories = false,
                        loadFailed = true,
                    ),
                onBack = {},
                onContinue = {},
                onToggleMemorySelection = {},
                onLoadMoreMemories = {},
                onRefreshMemories = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.MemorySelectionLoading, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            MemorySelectionScreen(
                uiState = MemorySelectionUiState(isLoading = true),
                onBack = {},
                onContinue = {},
                onToggleMemorySelection = {},
                onLoadMoreMemories = {},
                onRefreshMemories = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.RecommendationsSaving, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingRecommendationsContent(
                onBack = {},
                onKeepOn = {},
                onTurnOff = {},
                isSaving = true,
            )
        },
        sharedScene(SharedScreenshotSceneId.DayBoundariesPermissionsNeeded, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingDayBoundariesContent(
                gateState =
                    HealthConnectGateState(
                        kind = HealthConnectGateKind.NEEDS_PERMISSION,
                        missingRequirement = HealthConnectMissingRequirement.PERMISSION,
                    ),
                onBack = {},
                onEnable = {},
                onSkip = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.DayBoundariesChecking, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            OnboardingDayBoundariesContent(
                gateState = HealthConnectGateState(HealthConnectGateKind.CHECKING),
                onBack = {},
                onEnable = {},
                onSkip = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.NotificationsDecisionHandled, ScreenshotSceneGroup.ONBOARDING, onboardingStepVariants) {
            OnboardingNotificationsContent(
                onBack = {},
                onPrimaryAction = {},
                onSkip = {},
                recommendationsEnabled = false,
                hasDecision = true,
            )
        },
        sharedScene(SharedScreenshotSceneId.CloudAccountSelectedSignIn, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            CloudAccountSignInContent(
                username = "alex",
                onUsernameChange = {},
                onSignIn = {},
                onAccountRecovery = {},
                onPrivacyPolicy = {},
                onTermsOfService = {},
                onBack = {},
                serverDisplayName = "LogDate Cloud",
                serverHandleDomain = "logdate.app",
            )
        },
        sharedScene(
            SharedScreenshotSceneId.CloudAccountAdaptiveLargeScreen,
            ScreenshotSceneGroup.ONBOARDING,
            largeScreenAuditVariants,
        ) {
            CloudAccountWelcomeContent(
                onContinue = {},
                onSignIn = {},
                onBack = {},
                serverSelectionState = ServerSelectionState(),
                isPasskeySupported = true,
            )
        },
        sharedScene(
            SharedScreenshotSceneId.MemorySelectionAdaptiveLargeScreen,
            ScreenshotSceneGroup.ONBOARDING,
            largeScreenAuditVariants,
        ) {
            val memories = sampleMemories(videoEvery = 4, videoDuration = Duration.parse("45s"))
            MemorySelectionScreen(
                uiState =
                    MemorySelectionUiState(
                        allMemories = memories,
                        aiCuratedMemories = memories.take(4),
                        selectedMemoryIds = setOf("sample1", "sample2", "sample7"),
                        isLoading = false,
                        hasMoreMemories = false,
                    ),
                onBack = {},
                onContinue = {},
                onToggleMemorySelection = {},
                onLoadMoreMemories = {},
                onRefreshMemories = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.CloudAccountWelcome, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            CloudAccountWelcomeContent(
                onContinue = {},
                onSignIn = {},
                onBack = {},
                serverSelectionState = ServerSelectionState(),
                isPasskeySupported = true,
            )
        },
        sharedScene(SharedScreenshotSceneId.CloudAccountSignIn, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            CloudAccountSignInContent(
                username = "alex",
                onUsernameChange = {},
                onSignIn = {},
                onAccountRecovery = {},
                onPrivacyPolicy = {},
                onTermsOfService = {},
                onBack = {},
                serverDisplayName = "LogDate Cloud",
                serverHandleDomain = "logdate.app",
            )
        },
        sharedScene(SharedScreenshotSceneId.PasskeyAccountCreationFinal, ScreenshotSceneGroup.ONBOARDING, standardMatrixVariants) {
            PasskeyAccountCreationFinalContent(
                displayName = "Alex Rivera",
                username = "alex",
                onCreateAccount = {},
                onBack = {},
                isCreatingAccount = false,
                errorMessage = null,
                onClearError = {},
                isPasskeySupported = true,
                serverDisplayName = "LogDate Cloud",
                stepNumber = 2,
                stepCount = 2,
            )
        },
    )
