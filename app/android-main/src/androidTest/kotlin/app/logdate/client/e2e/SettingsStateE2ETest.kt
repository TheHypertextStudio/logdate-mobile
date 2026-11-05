package app.logdate.client.e2e

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.logdate.client.MainActivity
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.dayboundary.DayBoundarySettings
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.dayboundary.ObserveHealthConnectStatusUseCase
import app.logdate.client.health.HealthDataAvailability
import app.logdate.client.health.LocalFirstHealthRepository
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.feature.core.settings.account.ConnectedServer
import app.logdate.feature.core.settings.account.ConnectedServerInfo
import app.logdate.feature.core.settings.account.ServerHealth
import app.logdate.feature.core.settings.account.hosting.HostingScreen
import app.logdate.feature.core.settings.account.hosting.HostingViewModel
import app.logdate.feature.core.settings.account.signin.AddPasskeyFailure
import app.logdate.feature.core.settings.account.signin.AddPasskeyState
import app.logdate.feature.core.settings.account.signin.PasskeyRow
import app.logdate.feature.core.settings.account.signin.SignInMethodsContent
import app.logdate.feature.core.settings.account.signin.SignInMethodsLoadError
import app.logdate.feature.core.settings.account.signin.SignInMethodsRefreshState
import app.logdate.feature.core.settings.account.signin.SignInMethodsUiState
import app.logdate.feature.core.settings.ui.DayBoundarySettingsScreen
import app.logdate.feature.core.settings.ui.DayBoundarySettingsViewModel
import app.logdate.feature.core.settings.ui.LocationIntervalScreen
import app.logdate.feature.core.settings.ui.LocationSettingsViewModel
import app.logdate.feature.core.settings.ui.PrivacyPreferenceFailure
import app.logdate.feature.core.settings.ui.PrivacyPreferenceOperation
import app.logdate.feature.core.settings.ui.PrivacyPreferenceState
import app.logdate.feature.core.settings.ui.PrivacySettingsContent
import app.logdate.feature.core.settings.ui.PrivacySettingsState
import app.logdate.feature.core.settings.ui.devices.DeviceAction
import app.logdate.client.text.toUiText
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.devices_platform_android
import logdate.client.feature.core.generated.resources.devices_platform_ios
import app.logdate.feature.core.settings.ui.devices.DeviceInfoUiState
import app.logdate.feature.core.settings.ui.devices.DeviceListAvailability
import app.logdate.feature.core.settings.ui.devices.DeviceOperationState
import app.logdate.feature.core.settings.ui.devices.DeviceRefreshState
import app.logdate.feature.core.settings.ui.devices.DevicesScreenContent
import app.logdate.feature.core.settings.ui.devices.DevicesUiState
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** Production settings composables and ViewModels with disposable, deterministic account data. */
@RunWith(AndroidJUnit4::class)
class SettingsStateE2ETest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun emulatorOnly() {
        require(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use an Android emulator" }
    }

    @Test
    fun privacyLoadingAndAuthenticationOnlyAffectTheirOwnPreference() {
        var state by mutableStateOf(
            PrivacySettingsState(
                systemSearchVisibility = PrivacyPreferenceState.Available(true),
                showSystemSearchVisibilityToggle = true,
            ),
        )
        var searchWrites = 0
        render {
            PrivacySettingsContent(
                state = state,
                onBack = {},
                onSetBiometricsEnabled = {
                    state = state.copy(biometrics = PrivacyPreferenceState.Available(false, PrivacyPreferenceOperation.Authenticating))
                },
                onSetSystemSearchVisibilityEnabled = {
                    searchWrites++
                    state = state.copy(systemSearchVisibility = PrivacyPreferenceState.Available(it))
                },
            )
        }
        compose.onNodeWithText("Loading setting…").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        capture("privacy-loading")
        compose.runOnUiThread { state = state.copy(biometrics = PrivacyPreferenceState.Available(false)) }
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].assertIsNotEnabled()
        compose.onAllNodes(isToggleable())[1].assertIsEnabled()
        capture("privacy-authenticating")
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        assertEquals(1, searchWrites)
        compose.runOnUiThread {
            state =
                state.copy(
                    biometrics =
                        PrivacyPreferenceState.Available(
                            false,
                            PrivacyPreferenceOperation.Failed(true, PrivacyPreferenceFailure.SAVE),
                        ),
                )
        }
        compose.onNodeWithText("Couldn’t save this change. Try again.").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(isToggleable())[1].assertIsEnabled()
        capture("privacy-failed")
    }

    @Test
    fun devicesKeepTheirRowsThroughRefreshAndScopeRenameFailureToTheDialog() {
        val devices =
            listOf(
                DeviceInfoUiState(Uuid.parse("10000000-0000-0000-0000-000000000001"), "This emulator".toUiText(), Res.string.devices_platform_android, "Today".toUiText(), "0.1.0", true),
                DeviceInfoUiState(Uuid.parse("10000000-0000-0000-0000-000000000002"), "Tablet".toUiText(), Res.string.devices_platform_ios, "Yesterday".toUiText(), "0.1.0", false),
            )
        var state by mutableStateOf(DevicesUiState())
        var rename by mutableStateOf(false)
        render {
            DevicesScreenContent(
                onBackClick = {},
                uiState = state,
                connectionAction = {},
                showRenameDialog = rename,
                selectedDevice = devices.first(),
                newDeviceName = "Renamed emulator",
                onDismissRenameDialog = { rename = false },
            )
        }
        compose.onNodeWithText("This emulator").assertDoesNotExist()
        capture("devices-loading")
        compose.runOnUiThread {
            state = DevicesUiState(DeviceListAvailability.Available, devices, DeviceRefreshState.Running)
        }
        compose.onNodeWithText("This emulator").assertIsDisplayed()
        compose.onNodeWithText("Tablet").assertIsDisplayed()
        capture("devices-refreshing")
        compose.runOnUiThread { state = state.copy(refresh = DeviceRefreshState.Failed) }
        compose.onNodeWithText("Couldn’t load your devices. Try again.").assertIsDisplayed()
        compose.onNodeWithText("This emulator").assertIsDisplayed()
        capture("devices-refresh-failed")
        compose.runOnUiThread {
            state =
                state.copy(
                    refresh = DeviceRefreshState.Idle,
                    operations = mapOf(devices.first().id to DeviceOperationState.Failed(DeviceAction.Rename)),
                )
            rename = true
        }
        compose.onNodeWithText("Couldn’t rename this device. Try again.").assertIsDisplayed()
        capture("device-rename-failed")
        compose.runOnUiThread {
            state = state.copy(operations = mapOf(devices.first().id to DeviceOperationState.Running(DeviceAction.Rename)))
        }
        compose.onNodeWithText("Rename").assertIsNotEnabled()
        capture("device-rename-saving")
    }

    @Test
    fun dayBoundaryWaitsForPreferencesAndKeepsTimeUsableDuringSleepPersistence() {
        val repository = DayRepository()
        val saved = CompletableDeferred<Unit>()
        repository.pending = saved
        val health =
            Proxy.newProxyInstance(
                LocalFirstHealthRepository::class.java.classLoader,
                arrayOf(LocalFirstHealthRepository::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "getHealthDataAvailability" -> HealthDataAvailability.AVAILABLE
                    "hasSleepPermissions" -> true
                    "toString" -> "Disposable health provider"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected health operation: ${method.name}")
                }
            } as LocalFirstHealthRepository
        lateinit var model: DayBoundarySettingsViewModel
        compose.runOnUiThread {
            model =
                DayBoundarySettingsViewModel(
                    repository,
                    LogdatePreferencesDataSource(PreferenceStore()),
                    ObserveHealthConnectStatusUseCase(health),
                )
        }
        render { DayBoundarySettingsScreen(onBack = {}, viewModel = model) }
        compose.onNodeWithText("Checking…").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        capture("day-boundary-loading")
        compose.runOnUiThread { repository.settings.value = DayBoundarySettings() }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
        compose.onAllNodes(isToggleable())[0].assertIsNotEnabled()
        compose.onNodeWithText("Saving…").assertIsDisplayed()
        compose.onNodeWithText("Default start time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("4:00 AM").assertIsEnabled()
        capture("day-boundary-sleep-saving")
        compose.runOnUiThread { saved.complete(Unit) }
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onAllNodes(isToggleable())[0].assertIsEnabled()
        assertEquals(1, repository.writes)
        capture("day-boundary-saved")
    }

    @Test
    fun hostingHasOneInitialCheckAndKeepsItsAddressDuringRetry() {
        val server = TestServer()
        lateinit var model: HostingViewModel
        compose.runOnUiThread { model = HostingViewModel(server) }
        render { HostingScreen(onBack = {}, onMoveAccount = {}, viewModel = model) }
        compose.onNodeWithText("cloud.logdate.app").assertIsDisplayed()
        assertEquals(1, server.checks)
        val pending = CompletableDeferred<ServerHealth>()
        server.pending = pending
        compose.onNodeWithText("Check again").performScrollTo().performClick()
        compose.onNodeWithText("cloud.logdate.app").assertIsDisplayed()
        assertEquals(2, server.checks)
        capture("hosting-checking")
        compose.runOnUiThread { pending.complete(ServerHealth.Reachable("1.4.0")) }
        compose.onNodeWithText("Connected · version 1.4.0").assertIsDisplayed()
        capture("hosting-connected")
    }

    @Test
    fun signInMethodsStayVisibleWithRefreshAndAddFailures() {
        val passkeys =
            listOf(PasskeyRow("phone", "Phone passkey", null, null, true), PasskeyRow("tablet", "Tablet passkey", null, null, true))
        var state by mutableStateOf(SignInMethodsUiState.Loaded(passkeys, emptyList(), true))
        render { SignInMethodsContent(state, {}, {}, {}, {}) }
        compose.onNodeWithText("Phone passkey").assertIsDisplayed()
        compose.runOnUiThread { state = state.copy(refresh = SignInMethodsRefreshState.Running) }
        compose.onNodeWithText("Phone passkey").assertIsDisplayed()
        capture("sign-in-refreshing")
        compose.runOnUiThread { state = state.copy(refresh = SignInMethodsRefreshState.Failed(SignInMethodsLoadError.OFFLINE)) }
        compose.onNodeWithText("Couldn’t reach the server. Check your connection and try again.").performScrollTo().assertIsDisplayed()
        capture("sign-in-refresh-failed")
        compose.onNodeWithText("Phone passkey").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tablet passkey").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread {
            state =
                state.copy(refresh = SignInMethodsRefreshState.Idle, add = AddPasskeyState.Failed(AddPasskeyFailure.OFFLINE))
        }
        compose.onNodeWithText("Couldn’t reach the server. Check your connection and try again.").performScrollTo().assertIsDisplayed()
        capture("sign-in-add-failed")
        compose.onNodeWithText("Tablet passkey").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun locationIntervalPreviewsDraggingAndPersistsOnceOnRelease() {
        val repository = LocationRepository()
        lateinit var model: LocationSettingsViewModel
        compose.runOnUiThread { model = LocationSettingsViewModel(repository) }
        render { LocationIntervalScreen(onBack = {}, viewModel = model) }
        val slider = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        slider.performScrollTo().performTouchInput {
            down(center)
            moveTo(centerRight)
        }
        try {
            compose.runOnIdle { assertEquals(0, repository.intervalWrites) }
            capture("location-interval-preview")
        } finally {
            slider.performTouchInput { up() }
        }
        compose.runOnIdle { assertEquals(1, repository.intervalWrites) }
        slider.assertIsNotEnabled()
        capture("location-interval-saving")
        compose.runOnUiThread { repository.pending.complete(Unit) }
        slider.assertIsEnabled()
        compose.runOnIdle { assertEquals(1, repository.intervalWrites) }
        capture("location-interval-saved")
    }

    private fun render(content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                LogDateTheme(
                    dynamicColor = false,
                    darkTheme = InstrumentationRegistry.getArguments().getString("settingsDark") == "true",
                    content = content,
                )
            }
        }
    }

    private fun capture(name: String) {
        val label = InstrumentationRegistry.getArguments().getString("settingsCapture") ?: return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "settings-state/$label").apply { mkdirs() }
        compose.waitForIdle()
        SystemClock.sleep(600)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private class PreferenceStore : DataStore<Preferences> {
        private val values = MutableStateFlow(emptyPreferences())
        override val data = values

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            values.value = transform(values.value)
            return values.value
        }
    }

    private class DayRepository : DayBoundarySettingsRepository {
        val settings = MutableStateFlow<DayBoundarySettings?>(null)
        var pending: CompletableDeferred<Unit>? = null
        var writes = 0

        override fun observeSettings() = settings.filterNotNull()

        override suspend fun getSettings() = checkNotNull(settings.value)

        override suspend fun setSleepBasedBoundariesEnabled(enabled: Boolean) {
            writes++
            pending?.await()
            settings.value = DayBoundarySettings(enabled)
        }
    }

    private class LocationRepository : LocationTrackingSettingsRepository {
        val settings = MutableStateFlow(LocationTrackingSettings(backgroundTrackingEnabled = true))
        val pending = CompletableDeferred<Unit>()
        var intervalWrites = 0

        override suspend fun getSettings() = settings.value

        override fun observeSettings() = settings

        override suspend fun updateSettings(settings: LocationTrackingSettings) {
            this.settings.value = settings
        }

        override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
            settings.value =
                settings.value.copy(backgroundTrackingEnabled = enabled)
        }

        override suspend fun setTrackingInterval(intervalMinutes: Long) {
            intervalWrites++
            pending.await()
            settings.value = settings.value.copy(minimumPersistIntervalMinutes = intervalMinutes)
        }
    }

    private class TestServer : ConnectedServer {
        override val info = MutableStateFlow(ConnectedServerInfo("https://cloud.logdate.app", "LogDate Cloud", true, false))
        var pending: CompletableDeferred<ServerHealth>? = null
        var checks = 0

        override suspend fun refresh(): ServerHealth {
            checks++
            return pending?.await() ?: ServerHealth.Unreachable
        }
    }
}
