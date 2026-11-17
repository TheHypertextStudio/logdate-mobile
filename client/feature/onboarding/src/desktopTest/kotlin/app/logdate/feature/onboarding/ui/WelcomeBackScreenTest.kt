package app.logdate.feature.onboarding.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.domain.streak.CalculateStreakUseCase
import app.logdate.client.domain.streak.RefreshStreakUseCase
import app.logdate.feature.onboarding.flow.OnboardingStep
import app.logdate.shared.model.profile.LogDateProfile
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The "sign in with an existing account" onboarding path finishes on [WelcomeBackScreen]. Unlike
 * [OnboardingCompletionScreen] (the brand-new-account path), it used to navigate to Home without
 * ever marking onboarding complete, so returning users were sent right back into onboarding on
 * their next launch despite having a valid, authenticated session.
 */
@OptIn(ExperimentalTestApi::class)
class WelcomeBackScreenTest {
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
    private lateinit var welcomeBackViewModel: WelcomeBackViewModel

    @BeforeTest
    fun setup() {
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

        welcomeBackViewModel =
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

    @AfterTest
    fun tearDown() {
        runBlocking { identityKeyManager.clearIdentityKey() }
    }

    @Test
    fun `finishing welcome back marks onboarding complete when all required steps are done`() =
        runComposeUiTest {
            fakeProfileRepository.setProfile(LogDateProfile(displayName = "Alex", bio = "Bio"))
            runBlocking {
                fakeUserStateRepository.setBirthday(Instant.fromEpochMilliseconds(946684800000))
                fakeOnboardingDeviceStateRepository.markRecommendationsHandled()
                fakeOnboardingDeviceStateRepository.markLocationHandled()
                fakeOnboardingDeviceStateRepository.markDayBoundariesHandled()
                fakeOnboardingDeviceStateRepository.markNotificationsHandled()
            }

            val outcome = runWelcomeBack()

            assertTrue(outcome.finished, "expected onFinish to be called")
            assertNull(outcome.incompleteStep)
            assertTrue(fakeUserStateRepository.isOnboardingComplete)
        }

    @Test
    fun `welcome back does not require an optional birthday`() =
        runComposeUiTest {
            // Birthday deliberately left unset.
            fakeProfileRepository.setProfile(LogDateProfile(displayName = "Alex", bio = "Bio"))
            runBlocking {
                fakeOnboardingDeviceStateRepository.markRecommendationsHandled()
                fakeOnboardingDeviceStateRepository.markLocationHandled()
                fakeOnboardingDeviceStateRepository.markDayBoundariesHandled()
                fakeOnboardingDeviceStateRepository.markNotificationsHandled()
            }

            val outcome = runWelcomeBack()

            assertNull(outcome.incompleteStep)
            assertTrue(outcome.finished)
            assertTrue(fakeUserStateRepository.isOnboardingComplete)
        }

    @Test
    fun `a failed save stays on screen with a retry that finishes once saving works`() =
        runComposeUiTest {
            completeRequiredSteps()
            fakeUserStateRepository.setOnboardingCompleteFailure = IllegalStateException("disk full")
            var finished = false

            setContent {
                WelcomeBackScreen(
                    onFinish = { finished = true },
                    viewModel = welcomeBackViewModel,
                )
            }

            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithTag(ONBOARDING_SAVE_FAILED_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            assertFalse(finished, "onFinish must not be called when saving fails")
            assertFalse(fakeUserStateRepository.isOnboardingComplete)

            fakeUserStateRepository.setOnboardingCompleteFailure = null
            onNodeWithTag(ONBOARDING_SAVE_RETRY_TAG).performClick()

            waitUntil(timeoutMillis = 10_000) { finished }
            assertTrue(fakeUserStateRepository.isOnboardingComplete)
        }

    private fun completeRequiredSteps() {
        fakeProfileRepository.setProfile(LogDateProfile(displayName = "Alex", bio = "Bio"))
        runBlocking {
            fakeUserStateRepository.setBirthday(Instant.fromEpochMilliseconds(946684800000))
            fakeOnboardingDeviceStateRepository.markRecommendationsHandled()
            fakeOnboardingDeviceStateRepository.markLocationHandled()
            fakeOnboardingDeviceStateRepository.markDayBoundariesHandled()
            fakeOnboardingDeviceStateRepository.markNotificationsHandled()
        }
    }

    private data class WelcomeBackOutcome(
        val finished: Boolean,
        val incompleteStep: OnboardingStep?,
    )

    private fun ComposeUiTest.runWelcomeBack(): WelcomeBackOutcome {
        var finished = false
        var incompleteStep: OnboardingStep? = null

        setContent {
            WelcomeBackScreen(
                onFinish = { finished = true },
                onRequirementsIncomplete = { incompleteStep = it },
                viewModel = welcomeBackViewModel,
            )
        }

        waitUntil(timeoutMillis = 10_000) { finished || incompleteStep != null }
        return WelcomeBackOutcome(finished, incompleteStep)
    }
}
