package app.logdate.feature.core.settings.ui

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.recommendation.MemoriesSettings
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.domain.recommendation.RecallMode
import app.logdate.client.domain.recommendation.WidgetContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [MemoriesSettingsViewModel].
 *
 * Tests the configuration of "Memories" features, including widget installation
 * control, content type filtering for home screen widgets, and recall mode
 * settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoriesSettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `widget install state is exposed from controller`() =
        runTest {
            val widgetInstallController =
                FakeMemoriesWidgetInstallController(
                    initialState = MemoriesWidgetInstallUiState.Unsupported,
                )
            val viewModel =
                MemoriesSettingsViewModel(
                    preferences = LogdatePreferencesDataSource(CapturePreferenceStore()),
                    settingsRepository = FakeMemoriesSettingsRepository(),
                    widgetInstallController = widgetInstallController,
                )
            backgroundScope.launch { viewModel.uiState.collect { } }

            advanceUntilIdle()

            assertEquals(
                MemoriesWidgetInstallUiState.Unsupported,
                viewModel.uiState.value.widgetInstallUiState,
            )
        }

    @Test
    fun `add widget delegates to controller`() =
        runTest {
            val widgetInstallController = FakeMemoriesWidgetInstallController()
            val viewModel =
                MemoriesSettingsViewModel(
                    preferences = LogdatePreferencesDataSource(CapturePreferenceStore()),
                    settingsRepository = FakeMemoriesSettingsRepository(),
                    widgetInstallController = widgetInstallController,
                )

            viewModel.addWidgetToHomeScreen()
            advanceUntilIdle()

            assertEquals(1, widgetInstallController.requestCount)
        }

    @Test
    fun `toggle widget content type keeps at least one enabled type`() =
        runTest {
            val settingsRepository =
                FakeMemoriesSettingsRepository(
                    initialSettings =
                        MemoriesSettings(
                            widgetContentTypes = setOf(WidgetContentType.TEXT),
                        ),
                )
            val viewModel =
                MemoriesSettingsViewModel(
                    preferences = LogdatePreferencesDataSource(CapturePreferenceStore()),
                    settingsRepository = settingsRepository,
                    widgetInstallController = FakeMemoriesWidgetInstallController(),
                )

            viewModel.toggleWidgetContentType(WidgetContentType.TEXT, enabled = false)
            advanceUntilIdle()

            assertEquals(setOf(WidgetContentType.TEXT), settingsRepository.settings.value.widgetContentTypes)
        }

    @Test
    fun `a failed save is shown until the next change saves`() =
        runTest {
            val settingsRepository = FakeMemoriesSettingsRepository()
            val viewModel = memoriesViewModel(settingsRepository)
            backgroundScope.launch { viewModel.uiState.collect { } }

            settingsRepository.failWrites = true
            viewModel.toggleAiRecall(true)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.saveFailed)

            settingsRepository.failWrites = false
            viewModel.toggleAiRecall(true)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.saveFailed)
            assertTrue(settingsRepository.settings.value.aiRecallEnabled)
        }

    @Test
    fun `toggling widget content types quickly keeps every change`() =
        runTest {
            val settingsRepository =
                FakeMemoriesSettingsRepository(
                    initialSettings = MemoriesSettings(widgetContentTypes = setOf(WidgetContentType.TEXT)),
                    readDelayMillis = 10,
                )
            val viewModel = memoriesViewModel(settingsRepository)

            viewModel.toggleWidgetContentType(WidgetContentType.PHOTOS, enabled = true)
            viewModel.toggleWidgetContentType(WidgetContentType.AUDIO, enabled = true)
            advanceUntilIdle()

            assertEquals(WidgetContentType.ALL, settingsRepository.settings.value.widgetContentTypes)
        }

    private fun memoriesViewModel(settingsRepository: FakeMemoriesSettingsRepository) =
        MemoriesSettingsViewModel(
            preferences = LogdatePreferencesDataSource(CapturePreferenceStore()),
            settingsRepository = settingsRepository,
            widgetInstallController = FakeMemoriesWidgetInstallController(),
        )

    private class FakeMemoriesSettingsRepository(
        initialSettings: MemoriesSettings =
            MemoriesSettings(
                recallMode = RecallMode.ON_THIS_DAY,
                widgetContentTypes = WidgetContentType.ALL,
            ),
        private val readDelayMillis: Long = 0,
    ) : MemoriesSettingsRepository {
        val settings = MutableStateFlow(initialSettings)
        var failWrites = false

        override suspend fun getSettings(): MemoriesSettings {
            delay(readDelayMillis)
            return settings.value
        }

        override fun observeSettings(): Flow<MemoriesSettings> = settings

        override suspend fun updateSettings(settings: MemoriesSettings) {
            if (failWrites) throw IllegalStateException("settings store unavailable")
            this.settings.value = settings
        }
    }

    private class FakeMemoriesWidgetInstallController(
        initialState: MemoriesWidgetInstallUiState = MemoriesWidgetInstallUiState.Available,
    ) : MemoriesWidgetInstallController {
        private val _uiState = MutableStateFlow(initialState)

        var requestCount = 0

        override val uiState: StateFlow<MemoriesWidgetInstallUiState> = _uiState.asStateFlow()

        override suspend fun requestAddToHomeScreen() {
            requestCount++
        }
    }
}

private class CapturePreferenceStore : DataStore<Preferences> {
    private val values = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = values

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(values.value).also {
            values.value =
                it
        }
}
