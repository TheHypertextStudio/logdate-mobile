package app.logdate.feature.onboarding.di

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.dayboundary.ObserveHealthConnectStatusUseCase
import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.domain.streak.CalculateStreakUseCase
import app.logdate.client.domain.streak.RefreshStreakUseCase
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupState
import app.logdate.feature.onboarding.flow.OnboardingDeviceStateRepository
import app.logdate.feature.onboarding.ui.FakeAccountRepository
import app.logdate.feature.onboarding.ui.FakeCryptoManager
import app.logdate.feature.onboarding.ui.FakeDayBoundarySettingsRepository
import app.logdate.feature.onboarding.ui.FakeJournalNotesRepository
import app.logdate.feature.onboarding.ui.FakeLocalFirstHealthRepository
import app.logdate.feature.onboarding.ui.FakeLocationTrackingSettingsRepository
import app.logdate.feature.onboarding.ui.FakeMemoriesSettingsRepository
import app.logdate.feature.onboarding.ui.FakeOnboardingDeviceStateRepository
import app.logdate.feature.onboarding.ui.FakeProfileRepository
import app.logdate.feature.onboarding.ui.FakeSessionStorage
import app.logdate.feature.onboarding.ui.FakeStreakSettingsRepository
import app.logdate.feature.onboarding.ui.FakeUserStateRepository
import app.logdate.feature.onboarding.ui.InMemorySecureStorage
import app.logdate.feature.onboarding.ui.OnboardingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Resolves onboarding from the modules the Android app loads, so a binding that the watch step
 * depends on cannot silently fall back to the no-watch defaults.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingAndroidGraphTest {
    private val dispatcher = StandardTestDispatcher()
    private var koin: KoinApplication? = null

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        koin?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `onboarding offers watch setup when a paired watch is connected and the watch experience is on`() =
        runTest(dispatcher) {
            val viewModel = onboardingFromAndroidGraph(watchExperienceEnabled = true)

            advanceUntilIdle()

            assertTrue(viewModel.progressSnapshot.value.watchAvailable)
        }

    @Test
    fun `onboarding leaves watch setup out while the watch experience is off`() =
        runTest(dispatcher) {
            val viewModel = onboardingFromAndroidGraph(watchExperienceEnabled = false)

            advanceUntilIdle()

            assertFalse(viewModel.progressSnapshot.value.watchAvailable)
        }

    private suspend fun onboardingFromAndroidGraph(watchExperienceEnabled: Boolean): OnboardingViewModel {
        val userState = FakeUserStateRepository()
        val notes = FakeJournalNotesRepository()
        val identityKeys = IdentityKeyManager(InMemorySecureStorage(), FakeCryptoManager())
        identityKeys.setupNewIdentity()
        val appBindings =
            module {
                single<UserStateRepository> { userState }
                single<MemoriesSettingsRepository> { FakeMemoriesSettingsRepository() }
                single<LocationTrackingSettingsRepository> { FakeLocationTrackingSettingsRepository() }
                single<DayBoundarySettingsRepository> { FakeDayBoundarySettingsRepository() }
                single { ObserveHealthConnectStatusUseCase(FakeLocalFirstHealthRepository()) }
                single {
                    ObserveUserIdentityUseCase(
                        profileRepository = FakeProfileRepository(),
                        userStateRepository = userState,
                        accountRepository = FakeAccountRepository(),
                        sessionStorage = FakeSessionStorage(),
                    )
                }
                single<OnboardingDeviceStateRepository> { FakeOnboardingDeviceStateRepository() }
                single { RefreshStreakUseCase(CalculateStreakUseCase(notes), FakeStreakSettingsRepository()) }
                single { identityKeys }
                single<WatchSetupCoordinator> { ConnectedWatch }
                single<FeatureFlagStore> { WatchExperienceFlags(watchExperienceEnabled) }
            }
        val app =
            koinApplication {
                allowOverride(true)
                modules(onboardingFeatureModule, appBindings)
            }
        koin = app
        return app.koin.get()
    }

    private object ConnectedWatch : WatchSetupCoordinator {
        override fun connection(): Flow<WatchConnectionState> = flowOf(WatchConnectionState.Connected("Watch", "watch-node"))

        override fun addToWatch(): Flow<WatchSetupState> = emptyFlow()
    }

    private class WatchExperienceFlags(
        private val watchExperienceEnabled: Boolean,
    ) : FeatureFlagStore {
        override fun observe(flag: FeatureFlag): Flow<Boolean> =
            flowOf(if (flag == FeatureFlag.WATCH_EXPERIENCE_V2) watchExperienceEnabled else flag.defaultEnabled)

        override suspend fun setEnabled(
            flag: FeatureFlag,
            enabled: Boolean,
        ) = Unit
    }
}
