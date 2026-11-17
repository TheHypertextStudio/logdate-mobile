package app.logdate.feature.onboarding.ui

import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.domain.streak.CalculateStreakUseCase
import app.logdate.client.domain.streak.RefreshStreakUseCase
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [WelcomeBackViewModel.nameState] used to be hardcoded to the literal word "user" -- every
 * returning user saw "Welcome back, user!" regardless of who they were.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeBackViewModelTest {
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
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `name state resolves to the signed-in user's actual display name`() =
        runTest {
            fakeProfileRepository.setProfile(LogDateProfile(displayName = "Alex"))

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals("Alex", viewModel.nameState.value)
        }

    @Test
    fun `name state falls back to the generic placeholder when no name is known`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals("user", viewModel.nameState.value)
        }

    private fun createViewModel(): WelcomeBackViewModel =
        WelcomeBackViewModel(
            refreshStreakUseCase =
                RefreshStreakUseCase(
                    calculateStreakUseCase = CalculateStreakUseCase(fakeNotesRepository),
                    streakSettingsRepository = fakeStreakSettingsRepository,
                ),
            observeUserIdentity =
                ObserveUserIdentityUseCase(
                    profileRepository = fakeProfileRepository,
                    userStateRepository = fakeUserStateRepository,
                    accountRepository = fakeAccountRepository,
                    sessionStorage = fakeSessionStorage,
                ),
            completionCoordinator =
                buildOnboardingCompletionCoordinator(
                    notesRepository = fakeNotesRepository,
                    userStateRepository = fakeUserStateRepository,
                    streakSettingsRepository = fakeStreakSettingsRepository,
                ),
        )
}
