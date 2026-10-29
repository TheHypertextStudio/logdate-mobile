@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.client.domain.dayboundary.HealthConnectStatus
import app.logdate.feature.core.account.CloudAccountOnboardingScreen
import app.logdate.feature.core.account.CloudAccountOnboardingViewModel
import app.logdate.feature.onboarding.flow.OnboardingEntryMode
import app.logdate.feature.onboarding.flow.OnboardingStep
import app.logdate.feature.onboarding.flow.nextOnboardingStepAfter
import app.logdate.feature.onboarding.ui.CloudAccountSetupScreen
import app.logdate.feature.onboarding.ui.FirstEntryImportOfferScreen
import app.logdate.feature.onboarding.ui.MemoriesImportInfoScreen
import app.logdate.feature.onboarding.ui.MemorySelectionScreen
import app.logdate.feature.onboarding.ui.MemorySelectionViewModel
import app.logdate.feature.onboarding.ui.OnboardingBirthdayScreen
import app.logdate.feature.onboarding.ui.OnboardingCompletionScreen
import app.logdate.feature.onboarding.ui.OnboardingDayBoundariesScreen
import app.logdate.feature.onboarding.ui.OnboardingLocationScreen
import app.logdate.feature.onboarding.ui.OnboardingNotificationsScreen
import app.logdate.feature.onboarding.ui.OnboardingOverviewScreen
import app.logdate.feature.onboarding.ui.OnboardingRecommendationsScreen
import app.logdate.feature.onboarding.ui.OnboardingStartScreen
import app.logdate.feature.onboarding.ui.OnboardingViewModel
import app.logdate.feature.onboarding.ui.OnboardingWatchStep
import app.logdate.feature.onboarding.ui.PersonalIntroScreen
import app.logdate.feature.onboarding.ui.RecoveryPhraseEntryScreen
import app.logdate.feature.onboarding.ui.RecoveryPhraseSetupScreen
import app.logdate.feature.onboarding.ui.RecoveryPhraseViewModel
import app.logdate.feature.onboarding.ui.WelcomeBackScreen
import app.logdate.ui.navigation.taggedEntry
import app.logdate.ui.theme.FirstLaunchTheme
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import app.logdate.feature.core.account.OnboardingStep as CloudAccountEntryStep

/**
 * Registers every onboarding entry. Internal step-to-step navigation is computed by this
 * orchestrator (using [nextOnboardingStepAfter]); the host only
 * supplies callbacks for transitions that exit the onboarding flow.
 *
 * @param onNavigateBack pop the current onboarding entry
 * @param onWelcomeBack invoked from `WelcomeBackScreen` once the resume-onboarding flow
 *   completes
 * @param onOnboardingComplete invoked from `OnboardingCompletionScreen` once the user
 *   finishes onboarding
 * @param onGoToItem advance to a specific onboarding sub-route (typically backed by
 *   `backStack.add(route)`)
 */
@Suppress("ktlint:standard:function-naming")
fun EntryProviderScope<NavKey>.onboardingEntries(
    onNavigateBack: () -> Unit,
    onWelcomeBack: () -> Unit,
    onOnboardingComplete: () -> Unit,
    onCreateFirstEntry: () -> Unit,
    onGoToItem: (route: OnboardingBaseRoute) -> Unit,
    metadata: Map<String, Any> = emptyMap(),
    onContinueWithBluesky: (() -> Unit)? = null,
) {
    firstLaunchEntry<OnboardingRoute>(metadata = metadata) {
        // Marker entry — bounce immediately to the real start screen so legacy callers that
        // navigate to `OnboardingRoute` end up on `OnboardingStart`.
        LaunchedEffect(Unit) { onGoToItem(OnboardingStart) }
    }
    firstLaunchEntry<OnboardingStart>(metadata = metadata) {
        OnboardingStartScreen(
            onNext = onCreateFirstEntry,
            onSignIn = { onGoToItem(SignIn) },
        )
    }
    firstLaunchEntry<PersonalIntro> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()

        PersonalIntroScreen(
            onNext = {
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.PERSONAL_INTRO,
                            entryMode = entryMode,
                            snapshot = progressSnapshot.copy(hasPersonalIntro = true),
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
            onBack = onNavigateBack,
        )
    }
    firstLaunchEntry<AppOverview> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()

        OnboardingOverviewScreen(
            onBack = onNavigateBack,
            onNext = {
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.APP_OVERVIEW,
                            entryMode = entryMode,
                            snapshot = progressSnapshot,
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
        )
    }
    firstLaunchEntry<MemoryImport> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()

        MemoriesImportInfoScreen(
            onBack = onNavigateBack,
            onContinue = {
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.MEMORY_IMPORT,
                            entryMode = entryMode,
                            snapshot = progressSnapshot,
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
        )
    }
    firstLaunchEntry<MemorySelection> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()
        val viewModel = koinViewModel<MemorySelectionViewModel>()
        val coroutineScope = rememberCoroutineScope()
        MemorySelectionScreen(
            uiState = viewModel.uiState.collectAsState().value,
            onBack = onNavigateBack,
            onContinue = {
                coroutineScope.launch {
                    viewModel
                        .processSelectedMemories()
                        .onSuccess {
                            onGoToItem(
                                routeForStep(
                                    nextOnboardingStepAfter(
                                        currentStep = OnboardingStep.MEMORY_SELECTION,
                                        entryMode = entryMode,
                                        snapshot = progressSnapshot,
                                    ) ?: terminalStepFor(entryMode),
                                ),
                            )
                        }
                }
            },
            onToggleMemorySelection = viewModel::toggleMemorySelection,
            onLoadMoreMemories = viewModel::loadMoreMemories,
            onRefreshMemories = viewModel::refreshMemories,
        )
    }
    firstLaunchEntry<FirstEntryImportOffer>(metadata = metadata) {
        OnboardingInsets {
            FirstEntryImportOfferScreen(
                onImport = { onGoToItem(FirstEntryMemorySelection) },
                onContinue = onOnboardingComplete,
            )
        }
    }
    firstLaunchEntry<FirstEntryMemorySelection>(metadata = metadata) {
        FirstEntryMemorySelectionEntry(
            onBack = onNavigateBack,
            onComplete = onOnboardingComplete,
        )
    }
    firstLaunchEntry<AccountCreation>(metadata = metadata) {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()
        val coroutineScope = rememberCoroutineScope()

        CloudAccountSetupScreen(
            onContinueWithBluesky = onContinueWithBluesky,
            onBack = onNavigateBack,
            onContinue = {
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.ACCOUNT,
                            entryMode = entryMode,
                            snapshot = progressSnapshot.copy(hasCloudAccount = true),
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
            onSkip = {
                coroutineScope.launch {
                    flowViewModel
                        .markAccountHandled()
                        .onFailure { error -> Napier.e("Failed to persist skipped account setup", error) }
                    onGoToItem(
                        routeForStep(
                            nextOnboardingStepAfter(
                                currentStep = OnboardingStep.ACCOUNT,
                                entryMode = entryMode,
                                snapshot = progressSnapshot.copy(accountHandledOnThisDevice = true),
                            ) ?: terminalStepFor(entryMode),
                        ),
                    )
                }
            },
        )
    }
    firstLaunchEntry<RecoveryPhrase> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()
        val recoveryViewModel = koinViewModel<RecoveryPhraseViewModel>()
        val setupState by recoveryViewModel.setupState.collectAsState()

        fun continueAfterRecovery() {
            flowViewModel.provisionIdentityKey()
            onGoToItem(
                routeForStep(
                    nextOnboardingStepAfter(
                        currentStep = OnboardingStep.RECOVERY_PHRASE,
                        entryMode = entryMode,
                        snapshot = progressSnapshot,
                    ) ?: terminalStepFor(entryMode),
                ),
            )
        }

        // Advances without claiming the identity key was restored -- the user can still do that
        // from Settings, and their entries sync in the meantime.
        fun continueWithoutRecovery() {
            onGoToItem(
                routeForStep(
                    nextOnboardingStepAfter(
                        currentStep = OnboardingStep.RECOVERY_PHRASE,
                        entryMode = entryMode,
                        snapshot = progressSnapshot,
                    ) ?: terminalStepFor(entryMode),
                ),
            )
        }

        if (entryMode == OnboardingEntryMode.CONTINUE_SETUP) {
            OnboardingInsets {
                RecoveryPhraseEntryScreen(
                    onRecoverPhrase = recoveryViewModel::recoverIdentity,
                    onRecovered = ::continueAfterRecovery,
                    onSkip = ::continueWithoutRecovery,
                )
            }
        } else {
            OnboardingInsets {
                RecoveryPhraseSetupScreen(
                    words = setupState.words,
                    isLoading = setupState.isLoading,
                    errorMessage = setupState.errorMessage,
                    onRetry = recoveryViewModel::prepareRecoveryPhrase,
                    onPhraseContinue = { words ->
                        recoveryViewModel.confirmSaved(words)
                        continueAfterRecovery()
                    },
                    onLater = ::continueAfterRecovery,
                )
            }
        }
    }
    firstLaunchEntry<BirthdayIntro> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()

        OnboardingBirthdayScreen(
            onBack = onNavigateBack,
            persistBirthday = flowViewModel::persistBirthday,
            onNext = {
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.BIRTHDAY,
                            entryMode = entryMode,
                            snapshot = progressSnapshot.copy(hasBirthday = true),
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
        )
    }
    firstLaunchEntry<FeatureRecommendations> {
        val viewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by viewModel.progressSnapshot.collectAsState()
        val entryMode by viewModel.activeEntryMode.collectAsState()
        val healthConnectStatus by viewModel.healthConnectStatus.collectAsState()
        var pendingRecommendationsEnabled by rememberSaveable { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(pendingRecommendationsEnabled, healthConnectStatus) {
            if (
                pendingRecommendationsEnabled == null ||
                healthConnectStatus == HealthConnectStatus.CHECKING
            ) {
                return@LaunchedEffect
            }

            val recommendationsEnabled = pendingRecommendationsEnabled ?: return@LaunchedEffect
            pendingRecommendationsEnabled = null
            onGoToItem(
                routeForStep(
                    nextOnboardingStepAfter(
                        currentStep = OnboardingStep.RECOMMENDATIONS,
                        entryMode = entryMode,
                        snapshot =
                            progressSnapshot.copy(
                                recommendationsHandledOnThisDevice = true,
                                contextualRecommendationsEnabled = recommendationsEnabled,
                            ),
                    ) ?: terminalStepFor(entryMode),
                ),
            )
        }

        OnboardingRecommendationsScreen(
            viewModel = viewModel,
            onBack = onNavigateBack,
            onNext = { recommendationsEnabled ->
                if (
                    healthConnectStatus == HealthConnectStatus.CHECKING
                ) {
                    pendingRecommendationsEnabled = recommendationsEnabled
                } else {
                    onGoToItem(
                        routeForStep(
                            nextOnboardingStepAfter(
                                currentStep = OnboardingStep.RECOMMENDATIONS,
                                entryMode = entryMode,
                                snapshot =
                                    progressSnapshot.copy(
                                        recommendationsHandledOnThisDevice = true,
                                        contextualRecommendationsEnabled = recommendationsEnabled,
                                    ),
                            ) ?: terminalStepFor(entryMode),
                        ),
                    )
                }
            },
        )
    }
    firstLaunchEntry<FeatureDayBoundaries> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()

        OnboardingDayBoundariesScreen(
            onBack = onNavigateBack,
            onNext = { dayBoundariesEnabled ->
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.DAY_BOUNDARIES,
                            entryMode = entryMode,
                            snapshot =
                                progressSnapshot.copy(
                                    dayBoundariesHandledOnThisDevice = true,
                                    sleepBasedDayBoundariesEnabled = dayBoundariesEnabled,
                                ),
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
        )
    }
    firstLaunchEntry<FeatureLocationTimeline> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()

        OnboardingLocationScreen(
            onBack = onNavigateBack,
            onNext = { locationEnabled ->
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.LOCATION,
                            entryMode = entryMode,
                            snapshot =
                                progressSnapshot.copy(
                                    locationHandledOnThisDevice = true,
                                    locationTrackingEnabled = locationEnabled,
                                ),
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
        )
    }
    firstLaunchEntry<FeatureNotifications> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val progressSnapshot by flowViewModel.progressSnapshot.collectAsState()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()

        OnboardingNotificationsScreen(
            onBack = onNavigateBack,
            onNext = {
                onGoToItem(
                    routeForStep(
                        nextOnboardingStepAfter(
                            currentStep = OnboardingStep.NOTIFICATIONS,
                            entryMode = entryMode,
                            snapshot = progressSnapshot.copy(notificationsHandledOnThisDevice = true),
                        ) ?: terminalStepFor(entryMode),
                    ),
                )
            },
        )
    }
    firstLaunchEntry<FeatureWatch> {
        val flowViewModel = koinViewModel<OnboardingViewModel>()
        val entryMode by flowViewModel.activeEntryMode.collectAsState()
        OnboardingWatchStep(
            onBack = onNavigateBack,
            onNext = { onGoToItem(routeForStep(terminalStepFor(entryMode))) },
            viewModel = flowViewModel,
        )
    }
    firstLaunchEntry<SignIn>(metadata = metadata) {
        val cloudAccountViewModel = koinViewModel<CloudAccountOnboardingViewModel>()

        LaunchedEffect(Unit) {
            cloudAccountViewModel.resetFlow()
            cloudAccountViewModel.setInitialStep(CloudAccountEntryStep.SignIn)
        }

        CloudAccountOnboardingScreen(
            onContinueWithBluesky = onContinueWithBluesky,
            viewModel = cloudAccountViewModel,
            onAccountCreated = { onGoToItem(WelcomeBack) },
            onSkipOnboarding = onNavigateBack,
            onBack = onNavigateBack,
        )
    }
    firstLaunchEntry<OnboardingComplete>(metadata = metadata) {
        OnboardingCompletionScreen(
            onFinish = onOnboardingComplete,
            finishImmediately = true,
            onRequirementsIncomplete = { step ->
                onGoToItem(routeForStep(step))
            },
        )
    }
    firstLaunchEntry<WelcomeBack>(metadata = metadata) {
        OnboardingInsets {
            WelcomeBackScreen(
                onFinish = onWelcomeBack,
                onRequirementsIncomplete = { step -> onGoToItem(routeForStep(step)) },
            )
        }
    }
}

/**
 * An onboarding step, drawn in LogDate's own look from edge to edge. Every step in this flow is
 * registered through here, so none of them falls back to the device's colors.
 */
private inline fun <reified T : NavKey> EntryProviderScope<NavKey>.firstLaunchEntry(
    metadata: Map<String, Any> = emptyMap(),
    noinline content: @Composable (T) -> Unit,
) {
    taggedEntry<T>(metadata = metadata) { route ->
        FirstLaunchTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                content(route)
            }
        }
    }
}
