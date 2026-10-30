package app.logdate.feature.onboarding.ui

import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.dayboundary.HealthConnectStatus
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.v2.NoWatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupState
import app.logdate.feature.onboarding.flow.OnboardingEntryMode
import app.logdate.feature.onboarding.flow.OnboardingStep
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests the central [OnboardingViewModel] orchestrating the multi-step setup flow.
 *
 * These tests verify the state machine and cross-repository delegation for:
 * - Managing user identity and profile setup (e.g., birthday, profile photos)
 * - Toggling high-level system preferences for recommendations, location tracking, and day boundaries
 * - Handling Health Connect availability and its impact on day boundary choices
 * - Enforcing required completion steps before transitioning out of onboarding
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeNotesRepository: FakeJournalNotesRepository
    private lateinit var fakeUserStateRepository: FakeUserStateRepository
    private lateinit var fakeMemoriesSettingsRepository: FakeMemoriesSettingsRepository
    private lateinit var fakeLocationSettingsRepository: FakeLocationTrackingSettingsRepository
    private lateinit var fakeDayBoundarySettingsRepository: FakeDayBoundarySettingsRepository
    private lateinit var fakeHealthRepository: FakeLocalFirstHealthRepository
    private lateinit var fakeProfileRepository: FakeProfileRepository
    private lateinit var fakeAccountRepository: FakeAccountRepository
    private lateinit var fakeSessionStorage: FakeSessionStorage
    private lateinit var fakeStreakSettingsRepository: FakeStreakSettingsRepository
    private lateinit var fakeOnboardingDeviceStateRepository: FakeOnboardingDeviceStateRepository
    private lateinit var identityKeyManager: IdentityKeyManager
    private lateinit var viewModel: OnboardingViewModel

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        fakeNotesRepository = FakeJournalNotesRepository()
        fakeUserStateRepository = FakeUserStateRepository()
        fakeMemoriesSettingsRepository = FakeMemoriesSettingsRepository()
        fakeLocationSettingsRepository = FakeLocationTrackingSettingsRepository()
        fakeDayBoundarySettingsRepository = FakeDayBoundarySettingsRepository()
        fakeHealthRepository = FakeLocalFirstHealthRepository()
        fakeProfileRepository = FakeProfileRepository()
        fakeAccountRepository = FakeAccountRepository()
        fakeSessionStorage = FakeSessionStorage()
        fakeStreakSettingsRepository = FakeStreakSettingsRepository()
        fakeOnboardingDeviceStateRepository = FakeOnboardingDeviceStateRepository()
        identityKeyManager = IdentityKeyManager(InMemorySecureStorage(), FakeCryptoManager())
        runBlocking { identityKeyManager.setupNewIdentity() }
        viewModel = createViewModel()
    }

    private fun createViewModel(watchSetup: WatchSetupCoordinator = NoWatchSetupCoordinator): OnboardingViewModel =
        buildOnboardingViewModel(
            notesRepository = fakeNotesRepository,
            userStateRepository = fakeUserStateRepository,
            memoriesSettingsRepository = fakeMemoriesSettingsRepository,
            locationSettingsRepository = fakeLocationSettingsRepository,
            dayBoundarySettingsRepository = fakeDayBoundarySettingsRepository,
            healthRepository = fakeHealthRepository,
            profileRepository = fakeProfileRepository,
            accountRepository = fakeAccountRepository,
            sessionStorage = fakeSessionStorage,
            streakSettingsRepository = fakeStreakSettingsRepository,
            onboardingDeviceStateRepository = fakeOnboardingDeviceStateRepository,
            identityKeyManager = identityKeyManager,
            watchSetup = watchSetup,
        )

    @Test
    fun `skipping cancels pending setup and durably advances once`() =
        runTest {
            var cancelled = false
            var next = 0
            val setup =
                object : WatchSetupCoordinator {
                    override fun connection() = flowOf(WatchConnectionState.Connected("Watch", "watch-node"))

                    override fun addToWatch() =
                        flow {
                            try {
                                emit(WatchSetupState.SettingUp)
                                awaitCancellation()
                            } finally {
                                cancelled = true
                            }
                        }
                }
            val actions = WatchOnboardingActions(createViewModel(setup), this) { next++ }
            actions.prepare()
            runCurrent()
            assertTrue(actions.busy)
            actions.skip()
            actions.skip()
            advanceUntilIdle()
            assertTrue(cancelled)
            assertTrue(fakeOnboardingDeviceStateRepository.deviceState.value.watchHandledOnThisDevice)
            assertEquals(1, next)
            assertEquals(false, actions.busy)
        }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `update birthday delegates to repository`() =
        runTest {
            val birthday = Instant.fromEpochMilliseconds(946684800000) // 2000-01-01

            viewModel.updateBirthday(birthday)
            advanceUntilIdle()

            assertEquals(birthday, fakeUserStateRepository.lastBirthday)
        }

    @Test
    fun `set recommendations enabled true delegates to repository`() =
        runTest {
            viewModel.persistRecommendationsEnabled(true)

            assertTrue(fakeMemoriesSettingsRepository.contextualRecommendationsEnabled)
        }

    @Test
    fun `set recommendations enabled false delegates to repository`() =
        runTest {
            viewModel.persistRecommendationsEnabled(false)

            assertEquals(false, fakeMemoriesSettingsRepository.contextualRecommendationsEnabled)
        }

    @Test
    fun `enable location tracking delegates to repository`() =
        runTest {
            viewModel.persistLocationTrackingEnabled()

            assertTrue(fakeLocationSettingsRepository.backgroundTrackingEnabled)
        }

    @Test
    fun `enable sleep based day boundaries delegates to repository`() =
        runTest {
            fakeDayBoundarySettingsRepository.setSleepBasedBoundariesEnabled(false)

            viewModel.persistSleepBasedDayBoundariesEnabled(enabled = true)

            assertTrue(fakeDayBoundarySettingsRepository.sleepBasedBoundariesEnabled)
        }

    @Test
    fun `disable sleep based day boundaries delegates to repository`() =
        runTest {
            viewModel.persistSleepBasedDayBoundariesEnabled(enabled = false)

            assertEquals(false, fakeDayBoundarySettingsRepository.sleepBasedBoundariesEnabled)
        }

    @Test
    fun `refresh health status when unavailable preserves sleep based day boundaries preference`() =
        runTest {
            fakeHealthRepository.isAvailable = false
            fakeDayBoundarySettingsRepository.setSleepBasedBoundariesEnabled(true)

            viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(HealthConnectStatus.NOT_AVAILABLE, viewModel.healthConnectStatus.value)
            assertEquals(true, fakeDayBoundarySettingsRepository.sleepBasedBoundariesEnabled)
        }

    @Test
    fun `complete onboarding delegates to repository`() =
        runTest {
            fakeProfileRepository.setProfile(
                LogDateProfile(
                    displayName = "Alex",
                    bio = "Bio",
                ),
            )
            fakeUserStateRepository.setBirthday(Instant.fromEpochMilliseconds(946684800000))
            fakeOnboardingDeviceStateRepository.markRecommendationsHandled()
            fakeOnboardingDeviceStateRepository.markLocationHandled()
            fakeOnboardingDeviceStateRepository.markDayBoundariesHandled()
            fakeOnboardingDeviceStateRepository.markNotificationsHandled()
            advanceUntilIdle()

            viewModel.completeOnboardingIfEligible()

            assertTrue(fakeUserStateRepository.isOnboardingComplete)
        }

    @Test
    fun `complete onboarding if eligible fails when birthday missing`() =
        runTest {
            fakeProfileRepository.setProfile(
                LogDateProfile(
                    displayName = "Alex",
                    bio = "Bio",
                ),
            )
            fakeOnboardingDeviceStateRepository.markRecommendationsHandled()
            fakeOnboardingDeviceStateRepository.markLocationHandled()
            fakeOnboardingDeviceStateRepository.markDayBoundariesHandled()
            fakeOnboardingDeviceStateRepository.markNotificationsHandled()

            val result = viewModel.completeOnboardingIfEligible()

            assertTrue(result.isFailure)
            assertEquals(false, fakeUserStateRepository.isOnboardingComplete)
        }

    @Test
    fun `complete onboarding if eligible fails when location step not handled`() =
        runTest {
            fakeProfileRepository.setProfile(
                LogDateProfile(
                    displayName = "Alex",
                    bio = "Bio",
                ),
            )
            fakeUserStateRepository.setBirthday(Instant.fromEpochMilliseconds(946684800000))
            fakeOnboardingDeviceStateRepository.markRecommendationsHandled()
            fakeOnboardingDeviceStateRepository.markDayBoundariesHandled()
            fakeOnboardingDeviceStateRepository.markNotificationsHandled()
            advanceUntilIdle()

            val result = viewModel.completeOnboardingIfEligible()

            assertTrue(result.isFailure)
            assertEquals(false, fakeUserStateRepository.isOnboardingComplete)
            assertEquals(OnboardingStep.LOCATION, viewModel.firstIncompleteRequiredOnboardingStep())
        }

    @Test
    fun `onboarding provisions an identity key when the device has none`() =
        runTest {
            // Sync encrypts every note with a key derived from this one, so it has to exist before
            // the first upload. Nothing else in production creates it.
            identityKeyManager.clearIdentityKey()

            viewModel.provisionIdentityKey()
            advanceUntilIdle()

            assertTrue(identityKeyManager.hasIdentityKey())
        }

    @Test
    fun `complete onboarding if eligible is never blocked on a recovery phrase`() =
        runTest {
            // A returning user must never be stopped by a phrase prompt: the key is provisioned
            // for them, and the phrase is theirs to read from settings whenever they want it.
            identityKeyManager.clearIdentityKey()
            viewModel.provisionIdentityKey()
            fakeProfileRepository.setProfile(
                LogDateProfile(
                    displayName = "Alex",
                    bio = "Bio",
                ),
            )
            fakeUserStateRepository.setBirthday(Instant.fromEpochMilliseconds(946684800000))
            fakeOnboardingDeviceStateRepository.markRecommendationsHandled()
            fakeOnboardingDeviceStateRepository.markLocationHandled()
            fakeOnboardingDeviceStateRepository.markDayBoundariesHandled()
            fakeOnboardingDeviceStateRepository.markNotificationsHandled()
            advanceUntilIdle()

            val result = viewModel.completeOnboardingIfEligible()

            assertTrue(result.isSuccess)
            assertTrue(fakeUserStateRepository.isOnboardingComplete)
        }

    @Test
    fun `mark notifications handled updates progress snapshot`() =
        runTest {
            viewModel.markNotificationsHandled()
            advanceUntilIdle()

            assertTrue(viewModel.progressSnapshot.value.notificationsHandledOnThisDevice)
        }

    @Test
    fun `handled markers update progress snapshot`() =
        runTest {
            viewModel.markRecommendationsHandled()
            viewModel.markDayBoundariesHandled()
            viewModel.markLocationHandled()
            advanceUntilIdle()

            assertTrue(viewModel.progressSnapshot.value.recommendationsHandledOnThisDevice)
            assertTrue(viewModel.progressSnapshot.value.dayBoundariesHandledOnThisDevice)
            assertTrue(viewModel.progressSnapshot.value.locationHandledOnThisDevice)
        }

    @Test
    fun `set active entry mode updates state`() =
        runTest {
            viewModel.setActiveEntryMode(OnboardingEntryMode.CONTINUE_SETUP)
            advanceUntilIdle()

            assertEquals(OnboardingEntryMode.CONTINUE_SETUP, viewModel.activeEntryMode.value)
        }
}

// region Fakes
