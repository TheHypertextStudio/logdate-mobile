package app.logdate.client.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.logdate.client.MainActivity
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.watch.WatchNotificationSettings
import app.logdate.client.domain.watch.WatchSettingsRepository
import app.logdate.client.testing.launch.ActivityLaunchTestOverrides
import app.logdate.client.testing.navigation.NavigationTestDestination
import app.logdate.client.testing.onboarding.OnboardingTestFixture
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.java.KoinJavaComponent
import kotlin.test.assertEquals

/**
 * Instrumented integration coverage for the phone-side watch settings flow.
 *
 * These tests exercise the real [MainActivity], navigation graph, DI wiring,
 * shared watch ViewModel, and Compose UI. The actual Companion Device chooser
 * remains outside test control, so the watch services are replaced with fakes.
 */
@RunWith(AndroidJUnit4::class)
class WatchPairingSettingsE2ETest {
    private val fakeConnectionManager = RecordingWatchConnectionManager()
    private val fakeSettingsRepository = FakeWatchSettingsRepository()

    private val overrideModule: Module =
        module {
            single<WatchConnectionManager> { fakeConnectionManager }
            single<WatchSettingsRepository> { fakeSettingsRepository }
        }

    private val koinRule = OnboardingKoinModuleOverrideRule(overrideModule)
    private val launchOverrideRule =
        WatchLaunchOverrideRule(
            onboardingFixture = OnboardingTestFixture.ONBOARDED_HOME,
            navigationDestination = NavigationTestDestination.WatchSettings,
        )
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(koinRule).around(launchOverrideRule).around(composeRule)

    @Before
    fun resetFakes() {
        fakeConnectionManager.reset()
        fakeSettingsRepository.reset()
    }

    @Test(timeout = 60_000)
    fun `companion page navigates real tile and microphone details with request failure`() {
        val flags = KoinJavaComponent.getKoin().get<FeatureFlagStore>()
        val oldFlag = runBlocking { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) }
        try {
            runBlocking { flags.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, true) }
            fakeConnectionManager.updateState(WatchConnectionState.Connected(watchName = "Pixel Watch"))
            composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Tiles & complications"))
            composeRule.onNodeWithText("Tiles & complications").performClick()
            composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Watch face shortcuts"))
            composeRule.onNodeWithText("Watch face shortcuts").assertIsDisplayed()
            composeRule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Microphone"))
            composeRule.onNodeWithText("Microphone").performClick()
            fakeConnectionManager.openResult = WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND)
            composeRule.onNodeWithText("Open on watch").performClick()
            composeRule
                .onNodeWithText("Your watch didn't respond. Unlock it, move it close to your phone, and try again.")
                .assertIsDisplayed()
            assertEquals(1, fakeConnectionManager.openCalls)
        } finally {
            runBlocking { flags.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, oldFlag) }
        }
    }

    @Test
    fun `watch settings pair watch action uses connection manager`() {
        fakeConnectionManager.updateState(WatchConnectionState.NeedsAssociation("Pixel Watch"))

        composeRule.onNodeWithText("Pair LogDate with your watch").assertIsDisplayed()
        composeRule.onNodeWithText("Pair Watch").performClick()

        assertEquals(1, fakeConnectionManager.beginAssociationCalls)
    }

    @Test
    fun `watch settings pending association state is rendered`() {
        fakeConnectionManager.updateState(WatchConnectionState.AssociationPending)

        composeRule.onNodeWithText("Allow the connection").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Tap Allow on the prompt. Your watch connects right after.",
            ).assertIsDisplayed()
    }

    @Test
    fun `a connected watch that reports nothing shows no sync status`() {
        fakeConnectionManager.updateState(WatchConnectionState.Connected(watchName = "Pixel Watch"))

        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithText("Not yet synced").assertDoesNotExist()
        composeRule.onNodeWithText("Up to date").assertDoesNotExist()
    }

    @Test
    fun `watch settings offer no sync options and title the help section`() {
        fakeConnectionManager.updateState(
            WatchConnectionState.Connected(watchName = "Pixel Watch"),
        )

        composeRule.onNodeWithText("Sync settings").assertDoesNotExist()
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Help"))
        composeRule.onNodeWithText("Help").assertIsDisplayed()
    }

    @Test
    fun `troubleshooting install and open actions use connection manager`() {
        fakeConnectionManager.updateState(
            WatchConnectionState.Connected(watchName = "Pixel Watch"),
        )

        composeRule.onNodeWithText("Troubleshooting").performClick()
        composeRule.onNodeWithText("Install or update on watch").performClick()
        composeRule.onNodeWithText("Open LogDate on watch").performClick()

        assertEquals(1, fakeConnectionManager.installCalls)
        assertEquals(1, fakeConnectionManager.openCalls)
    }

    @Test
    fun `troubleshooting says why the watch did not open LogDate`() {
        fakeConnectionManager.updateState(
            WatchConnectionState.Connected(watchName = "Pixel Watch"),
        )
        fakeConnectionManager.openResult = WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND)

        composeRule.onNodeWithText("Troubleshooting").performClick()
        composeRule.onNodeWithText("Open LogDate on watch").performClick()

        composeRule
            .onNodeWithText("Your watch didn't respond. Unlock it, move it close to your phone, and try again.")
            .assertIsDisplayed()
    }
}

private class WatchLaunchOverrideRule(
    private val onboardingFixture: OnboardingTestFixture,
    private val navigationDestination: NavigationTestDestination,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                ActivityLaunchTestOverrides.onboardingFixture = onboardingFixture
                ActivityLaunchTestOverrides.navigationDestination = navigationDestination
                try {
                    base.evaluate()
                } finally {
                    ActivityLaunchTestOverrides.clear()
                }
            }
        }
}

private class RecordingWatchConnectionManager : WatchConnectionManager {
    private val state = MutableStateFlow<WatchConnectionState>(WatchConnectionState.Loading)

    var beginAssociationCalls: Int = 0
        private set
    var requestSyncCalls: Int = 0
        private set
    var installCalls: Int = 0
        private set
    var openCalls: Int = 0
        private set
    var installResult: WatchRequestResult = WatchRequestResult.Sent
    var openResult: WatchRequestResult = WatchRequestResult.Sent

    fun reset() {
        state.value = WatchConnectionState.Loading
        beginAssociationCalls = 0
        requestSyncCalls = 0
        installCalls = 0
        openCalls = 0
        installResult = WatchRequestResult.Sent
        openResult = WatchRequestResult.Sent
    }

    fun updateState(connectionState: WatchConnectionState) {
        state.value = connectionState
    }

    override fun observeConnectionState(): Flow<WatchConnectionState> = state.asStateFlow()

    override suspend fun beginAssociation() {
        beginAssociationCalls++
    }

    override suspend fun requestSync() {
        requestSyncCalls++
    }

    override suspend fun installAppOnWatch(): WatchRequestResult {
        installCalls++
        return installResult
    }

    override suspend fun openAppOnWatch(): WatchRequestResult {
        openCalls++
        return openResult
    }
}

private class FakeWatchSettingsRepository : WatchSettingsRepository {
    private val notificationSettings = MutableStateFlow(WatchNotificationSettings())

    fun reset() {
        notificationSettings.value = WatchNotificationSettings()
    }

    override fun observeNotificationSettings(): StateFlow<WatchNotificationSettings> = notificationSettings.asStateFlow()

    override suspend fun setShowEntryNotifications(enabled: Boolean) {
        notificationSettings.value = notificationSettings.value.copy(showEntryNotifications = enabled)
    }
}
