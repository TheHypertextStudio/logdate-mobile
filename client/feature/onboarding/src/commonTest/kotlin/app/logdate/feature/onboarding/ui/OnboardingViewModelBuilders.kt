package app.logdate.feature.onboarding.ui

import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.dayboundary.ObserveHealthConnectStatusUseCase
import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.domain.streak.CalculateStreakUseCase
import app.logdate.client.domain.streak.RefreshStreakUseCase
import app.logdate.feature.core.settings.ui.watch.v2.NoWatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupCoordinator
import app.logdate.feature.onboarding.flow.OnboardingCompletionCoordinator

/**
 * Shared across [OnboardingViewModelTest] and `WelcomeBackScreenTest` (in `desktopTest`) so a
 * change to [OnboardingViewModel]'s constructor only needs updating here.
 */
internal fun buildOnboardingViewModel(
    notesRepository: FakeJournalNotesRepository,
    userStateRepository: FakeUserStateRepository,
    memoriesSettingsRepository: FakeMemoriesSettingsRepository,
    locationSettingsRepository: FakeLocationTrackingSettingsRepository,
    dayBoundarySettingsRepository: FakeDayBoundarySettingsRepository,
    healthRepository: FakeLocalFirstHealthRepository,
    profileRepository: FakeProfileRepository,
    accountRepository: FakeAccountRepository,
    sessionStorage: FakeSessionStorage,
    streakSettingsRepository: FakeStreakSettingsRepository,
    onboardingDeviceStateRepository: FakeOnboardingDeviceStateRepository,
    identityKeyManager: IdentityKeyManager,
    watchSetup: WatchSetupCoordinator = NoWatchSetupCoordinator,
): OnboardingViewModel =
    OnboardingViewModel(
        userStateRepository = userStateRepository,
        memoriesSettingsRepository = memoriesSettingsRepository,
        locationTrackingSettingsRepository = locationSettingsRepository,
        dayBoundarySettingsRepository = dayBoundarySettingsRepository,
        observeHealthConnectStatus = ObserveHealthConnectStatusUseCase(healthRepository),
        observeUserIdentity =
            ObserveUserIdentityUseCase(
                profileRepository = profileRepository,
                userStateRepository = userStateRepository,
                accountRepository = accountRepository,
                sessionStorage = sessionStorage,
            ),
        onboardingDeviceStateRepository = onboardingDeviceStateRepository,
        refreshStreakUseCase =
            RefreshStreakUseCase(
                calculateStreakUseCase = CalculateStreakUseCase(notesRepository),
                streakSettingsRepository = streakSettingsRepository,
            ),
        identityKeyManager = identityKeyManager,
        watchSetup = watchSetup,
    )

internal fun buildOnboardingCompletionCoordinator(
    notesRepository: FakeJournalNotesRepository,
    userStateRepository: FakeUserStateRepository,
    streakSettingsRepository: FakeStreakSettingsRepository,
): OnboardingCompletionCoordinator =
    OnboardingCompletionCoordinator(
        userStateRepository = userStateRepository,
        refreshStreakUseCase =
            RefreshStreakUseCase(
                calculateStreakUseCase = CalculateStreakUseCase(notesRepository),
                streakSettingsRepository = streakSettingsRepository,
            ),
    )
