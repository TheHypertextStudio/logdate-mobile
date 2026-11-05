package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.location.settings.DefaultLocation
import app.logdate.client.location.settings.LocationCaptureMode
import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.location.settings.withFailureReporting
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LocationSettingsViewModel(
    settingsRepository: LocationTrackingSettingsRepository,
) : ViewModel() {
    private val settingsRepository = settingsRepository.withFailureReporting()

    private val mutableState = MutableStateFlow<LocationSettingsState>(LocationSettingsState.Loading)
    val uiState: StateFlow<LocationSettingsState> = mutableState.asStateFlow()
    private var observationJob: Job? = null
    private var observationVersion = 0L

    init {
        reload()
    }

    fun reload() {
        if (observationJob?.isActive == true) return
        mutableState.update { current ->
            (current as? LocationSettingsState.Available)?.copy(isRefreshing = true, refreshFailed = false)
                ?: LocationSettingsState.Loading
        }
        observationJob =
            viewModelScope.launch {
                try {
                    settingsRepository.observeSettings().collect { settings ->
                        observationVersion++
                        mutableState.update { current ->
                            (current as? LocationSettingsState.Available)?.copy(
                                settings = settings,
                                isRefreshing = false,
                                refreshFailed = false,
                            ) ?: LocationSettingsState.Available(settings)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Unable to observe location settings", error)
                    mutableState.update { current ->
                        (current as? LocationSettingsState.Available)?.copy(isRefreshing = false, refreshFailed = true)
                            ?: LocationSettingsState.Unavailable
                    }
                }
            }
    }

    fun toggleBackgroundTracking(enabled: Boolean) = save(LocationSettingsChange.BackgroundTracking(enabled))

    fun toggleJournalTracking(enabled: Boolean) = save(LocationSettingsChange.JournalTracking(enabled))

    fun toggleTimelineTracking(enabled: Boolean) = save(LocationSettingsChange.TimelineTracking(enabled))

    fun updateTrackingInterval(intervalMinutes: Long) = save(LocationSettingsChange.Interval(intervalMinutes.coerceAtLeast(2)))

    fun toggleActiveTracking(enabled: Boolean) = setCaptureMode(if (enabled) LocationCaptureMode.ACTIVE else LocationCaptureMode.PASSIVE)

    fun setCaptureMode(mode: LocationCaptureMode) = save(LocationSettingsChange.CaptureMode(mode))

    fun toggleServerAssist(enabled: Boolean) = save(LocationSettingsChange.ServerAssist(enabled))

    fun setDefaultLocation(location: DefaultLocation?) = save(LocationSettingsChange.DefaultFallback(location))

    fun retry(setting: LocationSetting) {
        val current = mutableState.value as? LocationSettingsState.Available ?: return
        val failed = current.operation(setting) as? LocationSettingOperation.Failed ?: return
        save(failed.change)
    }

    private fun save(change: LocationSettingsChange) {
        val current = mutableState.value as? LocationSettingsState.Available ?: return
        if (current.isSaving(change.setting)) return
        if (change.applyTo(current.settings) == current.settings) {
            setOperation(change.setting, LocationSettingOperation.Idle)
            return
        }
        val observedVersion = observationVersion
        setOperation(change.setting, LocationSettingOperation.Saving(change))
        viewModelScope.launch {
            try {
                persist(change)
                mutableState.update { latest ->
                    if (latest !is LocationSettingsState.Available) return@update latest
                    latest.copy(
                        settings = if (observedVersion == observationVersion) change.applyTo(latest.settings) else latest.settings,
                        operations = latest.operations - change.setting,
                    )
                }
            } catch (cancelled: CancellationException) {
                setOperation(change.setting, LocationSettingOperation.Idle)
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Unable to save location preference ${change.setting}", error)
                setOperation(change.setting, LocationSettingOperation.Failed(change))
            }
        }
    }

    private suspend fun persist(change: LocationSettingsChange) {
        when (change) {
            is LocationSettingsChange.BackgroundTracking -> settingsRepository.setBackgroundTrackingEnabled(change.enabled)
            is LocationSettingsChange.JournalTracking -> settingsRepository.setJournalTrackingEnabled(change.enabled)
            is LocationSettingsChange.TimelineTracking -> settingsRepository.setTimelineTrackingEnabled(change.enabled)
            is LocationSettingsChange.Interval -> settingsRepository.setTrackingInterval(change.minutes)
            is LocationSettingsChange.CaptureMode -> settingsRepository.setCaptureMode(change.mode)
            is LocationSettingsChange.ServerAssist -> settingsRepository.setServerAssistEnabled(change.enabled)
            is LocationSettingsChange.DefaultFallback -> settingsRepository.setDefaultLocation(change.location)
        }
    }

    private fun LocationSettingsChange.applyTo(settings: LocationTrackingSettings): LocationTrackingSettings =
        when (this) {
            is LocationSettingsChange.BackgroundTracking -> settings.copy(backgroundTrackingEnabled = enabled)
            is LocationSettingsChange.JournalTracking -> settings.copy(autoTrackForJournalEntries = enabled)
            is LocationSettingsChange.TimelineTracking -> settings.copy(autoTrackForTimelineReview = enabled)
            is LocationSettingsChange.Interval -> settings.copy(minimumPersistIntervalMinutes = minutes)
            is LocationSettingsChange.CaptureMode -> settings.copy(captureMode = mode)
            is LocationSettingsChange.ServerAssist -> settings.copy(serverAssistEnabled = enabled)
            is LocationSettingsChange.DefaultFallback -> settings.copy(defaultLocation = location)
        }

    private fun setOperation(
        setting: LocationSetting,
        operation: LocationSettingOperation,
    ) {
        mutableState.update { current ->
            if (current !is LocationSettingsState.Available) return@update current
            current.copy(
                operations =
                    if (operation == LocationSettingOperation.Idle) {
                        current.operations - setting
                    } else {
                        current.operations + (setting to operation)
                    },
            )
        }
    }
}
