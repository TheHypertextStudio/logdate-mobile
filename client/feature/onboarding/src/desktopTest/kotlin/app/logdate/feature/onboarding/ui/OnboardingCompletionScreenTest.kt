package app.logdate.feature.onboarding.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.feature.onboarding.flow.OnboardingCompletionCoordinator
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The last onboarding screen shows its campfire as soon as it is composed, without a loading state. */
@OptIn(ExperimentalTestApi::class)
class OnboardingCompletionScreenTest {
    private lateinit var completionCoordinator: OnboardingCompletionCoordinator

    @BeforeTest
    fun setup() {
        val fakeNotesRepository = FakeJournalNotesRepository()
        val fakeStreakSettingsRepository = FakeStreakSettingsRepository()
        val identityKeyManager = IdentityKeyManager(InMemorySecureStorage(), FakeCryptoManager())
        runBlocking { identityKeyManager.setupNewIdentity() }

        completionCoordinator =
            buildOnboardingCompletionCoordinator(
                notesRepository = fakeNotesRepository,
                userStateRepository = FakeUserStateRepository(),
                streakSettingsRepository = fakeStreakSettingsRepository,
            )
    }

    @Test
    fun `shows the campfire content straight away`() =
        runComposeUiTest {
            setContent {
                OnboardingCompletionScreen(
                    onFinish = {},
                    completionCoordinator = completionCoordinator,
                )
            }

            onNodeWithTag(ONBOARDING_COMPLETION_LOADING_TAG).assertDoesNotExist()
            onNodeWithTag(ONBOARDING_COMPLETION_ROOT_TAG).assertExists()
        }
}
