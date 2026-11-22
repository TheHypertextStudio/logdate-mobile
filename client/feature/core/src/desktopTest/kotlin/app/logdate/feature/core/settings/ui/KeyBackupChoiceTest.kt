package app.logdate.feature.core.settings.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.crypto.KeyBackupPreference
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class KeyBackupChoiceTest {
    private val flag = KeyBackupFlag()
    private val preference = FakeKeyBackupPreference()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no row appears while the flag is off or the platform has no choice`() =
        runTest {
            val flagged = KeyBackupChoiceViewModel(preference, flag)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flagged.state.collect {} }
            assertNull(flagged.state.value)

            val unsupported = KeyBackupChoiceViewModel(null, flag.apply { enabled.value = true })
            assertNull(unsupported.state.value)
        }

    @Test
    fun `keeping the key on this device turns cloud backup off`() =
        runTest {
            flag.enabled.value = true
            val viewModel = KeyBackupChoiceViewModel(preference, flag)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }
            assertEquals(KeyBackupChoiceState(keptOnDevice = false), viewModel.state.value)

            viewModel.setKeepOnDevice(true)

            assertEquals(listOf(false), preference.requests)
            assertEquals(KeyBackupChoiceState(keptOnDevice = true), viewModel.state.value)
        }

    @Test
    fun `a failed change is reported on the row`() =
        runTest {
            flag.enabled.value = true
            preference.failing = true
            val viewModel = KeyBackupChoiceViewModel(preference, flag)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect {} }

            viewModel.setKeepOnDevice(true)

            assertEquals(KeyBackupChoiceState(keptOnDevice = false, failed = true), viewModel.state.value)
        }

    @Test
    fun `the row names what is lost and toggles the choice`() =
        runDesktopComposeUiTest(width = 411, height = 600) {
            var requested: Boolean? = null
            setContent {
                LogDateTheme {
                    KeyBackupChoiceSection(KeyBackupChoiceState(keptOnDevice = false), { requested = it })
                }
            }

            onNodeWithText("Keep my key on this device only").assertExists()
            onNodeWithText("a lost phone needs your recovery phrase", substring = true).assertExists()
            onNode(isToggleable()).assertIsOff().performClick()
            assertEquals(true, requested)
        }

    @Test
    fun `a kept key says moving directly to a new phone still works`() =
        runDesktopComposeUiTest(width = 411, height = 600) {
            setContent {
                LogDateTheme {
                    KeyBackupChoiceSection(KeyBackupChoiceState(keptOnDevice = true), {})
                }
            }

            onNode(isToggleable()).assertIsOn()
            onNodeWithText("Moving directly to a new phone still works", substring = true).assertExists()
        }
}

private class KeyBackupFlag : FeatureFlagStore {
    val enabled = MutableStateFlow(false)

    override fun observe(flag: FeatureFlag): Flow<Boolean> = if (flag == FeatureFlag.PLATFORM_KEY_BACKUP) enabled else emptyFlow()

    override suspend fun setEnabled(
        flag: FeatureFlag,
        enabled: Boolean,
    ) {
        check(flag == FeatureFlag.PLATFORM_KEY_BACKUP)
        this.enabled.value = enabled
    }
}

private class FakeKeyBackupPreference : KeyBackupPreference {
    private val allowed = MutableStateFlow(true)
    val requests = mutableListOf<Boolean>()
    var failing = false

    override val cloudBackupAllowed: Flow<Boolean> = allowed

    override suspend fun setCloudBackupAllowed(allowed: Boolean) {
        requests += allowed
        check(!failing) { "Play services unavailable" }
        this.allowed.value = allowed
    }
}
