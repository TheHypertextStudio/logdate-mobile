package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.recommendation.AmbientPromptTime
import app.logdate.client.domain.recommendation.MemoriesSettings
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.domain.recommendation.RecallMode
import app.logdate.client.domain.recommendation.WidgetContentType
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ViewModel for the memories settings screens.
 */
class MemoriesSettingsViewModel(
    private val settingsRepository: MemoriesSettingsRepository,
    private val widgetInstallController: MemoriesWidgetInstallController,
    private val preferences: LogdatePreferencesDataSource,
) : ViewModel() {
    private val mutableCaptureModeState = MutableStateFlow<DefaultCaptureModeState>(DefaultCaptureModeState.Loading)
    val captureModeState: StateFlow<DefaultCaptureModeState> = mutableCaptureModeState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                preferences.observeEntryStartMode().collect { mode ->
                    val previous = mutableCaptureModeState.value as? DefaultCaptureModeState.Ready
                    mutableCaptureModeState.value =
                        DefaultCaptureModeState.Ready(
                            mode,
                            saving = previous?.saving == true,
                            saveFailed = previous?.saveFailed == true,
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not read the default capture mode", error)
                mutableCaptureModeState.value = DefaultCaptureModeState.Unavailable
            }
        }
    }

    fun setDefaultCaptureMode(mode: EntryStartMode) {
        val previous = mutableCaptureModeState.value as? DefaultCaptureModeState.Ready ?: return
        if (previous.saving || previous.mode == mode) return
        mutableCaptureModeState.value = previous.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            try {
                preferences.setEntryStartMode(mode)
                mutableCaptureModeState.value = DefaultCaptureModeState.Ready(mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Could not save the default capture mode", error)
                val current = mutableCaptureModeState.value as? DefaultCaptureModeState.Ready ?: previous
                mutableCaptureModeState.value = current.copy(saving = false, saveFailed = true)
            }
        }
    }

    /**
     * @property saveFailed The last change could not be saved; the controls show the stored values,
     *   and the next successful save clears it.
     */
    data class UiState(
        val settings: MemoriesSettings = MemoriesSettings(),
        val widgetInstallUiState: MemoriesWidgetInstallUiState = MemoriesWidgetInstallUiState.Hidden,
        val saveFailed: Boolean = false,
    )

    private val saveFailed = MutableStateFlow(false)
    private val saving = Mutex()

    val uiState: StateFlow<UiState> =
        combine(
            settingsRepository.observeSettings(),
            widgetInstallController.uiState,
            saveFailed,
        ) { settings, widgetInstallUiState, saveFailed ->
            UiState(
                settings = settings,
                widgetInstallUiState = widgetInstallUiState,
                saveFailed = saveFailed,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiState(),
        )

    fun toggleContextualRecommendations(enabled: Boolean) =
        save("contextual recommendations") { settingsRepository.setContextualRecommendationsEnabled(enabled) }

    fun toggleAiRecall(enabled: Boolean) = save("AI recall") { settingsRepository.setAiRecallEnabled(enabled) }

    fun toggleAmbientPrompts(enabled: Boolean) = save("ambient prompts") { settingsRepository.setAmbientPromptsEnabled(enabled) }

    fun toggleCaptureNudges(enabled: Boolean) = save("capture nudges") { settingsRepository.setCaptureNudgesEnabled(enabled) }

    fun toggleDraftRescue(enabled: Boolean) = save("draft rescue") { settingsRepository.setDraftRescueEnabled(enabled) }

    fun toggleMemoryRecallNotifications(enabled: Boolean) =
        save("memory recall notifications") { settingsRepository.setMemoryRecallNotificationsEnabled(enabled) }

    fun toggleMorningPrompt(enabled: Boolean) = save("morning prompt") { settingsRepository.setMorningPromptEnabled(enabled) }

    fun toggleEveningPrompt(enabled: Boolean) = save("evening prompt") { settingsRepository.setEveningPromptEnabled(enabled) }

    fun setMorningPromptTime(time: AmbientPromptTime) = save("morning prompt time") { settingsRepository.setMorningPromptTime(time) }

    fun setEveningPromptTime(time: AmbientPromptTime) = save("evening prompt time") { settingsRepository.setEveningPromptTime(time) }

    fun setRecallMode(mode: RecallMode) = save("recall mode") { settingsRepository.setRecallMode(mode) }

    fun toggleWidgetContentType(
        type: WidgetContentType,
        enabled: Boolean,
    ) = save("widget content types") {
        val current = settingsRepository.getSettings().widgetContentTypes
        val updated = if (enabled) current + type else (current - type).ifEmpty { setOf(type) }
        settingsRepository.setWidgetContentTypes(updated)
    }

    /** Applies [change] after every earlier one, so a read-modify-write never loses a concurrent toggle. */
    private fun save(
        setting: String,
        change: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                saving.withLock { change() }
                saveFailed.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Failed to save $setting", error)
                saveFailed.value = true
            }
        }
    }

    fun addWidgetToHomeScreen(kind: HomeWidgetKind = HomeWidgetKind.RECALL) {
        viewModelScope.launch {
            try {
                widgetInstallController.requestAddToHomeScreen(kind)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Napier.e("Failed to request widget pinning", e)
            }
        }
    }
}
