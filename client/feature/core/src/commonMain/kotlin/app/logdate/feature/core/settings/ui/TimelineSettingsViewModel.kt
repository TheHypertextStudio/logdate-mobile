package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.dayboundary.HealthConnectStatus
import app.logdate.client.domain.dayboundary.ObserveHealthConnectStatusUseCase
import app.logdate.client.domain.dayboundary.withFailureReporting
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TimelineSettingsViewModel(
    settingsRepository: DayBoundarySettingsRepository,
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val observeHealthConnectStatus: ObserveHealthConnectStatusUseCase,
) : ViewModel() {
    private val settingsRepository = settingsRepository.withFailureReporting()

    data class UiState(
        val preferences: DaySchedulePreferencesState = DaySchedulePreferencesState.Loading(),
        val healthConnectStatus: HealthConnectStatus = HealthConnectStatus.CHECKING,
    ) {
        val dayBoundarySettings get() = preferences.values?.settings
        val fallbackStartHour get() = preferences.values?.fallbackStartHour
    }

    private val mutableState = MutableStateFlow(UiState())
    val uiState = mutableState.asStateFlow()
    private var preferencesJob: Job? = null
    private var healthJob: Job? = null
    private var hasResumed = false

    init {
        retryPreferences()
        refreshHealthStatus()
    }

    fun onScreenResumed() {
        if (hasResumed) refreshHealthStatus() else hasResumed = true
    }

    private fun refreshHealthStatus() {
        if (healthJob?.isActive == true) return
        healthJob =
            viewModelScope.launch {
                try {
                    observeHealthConnectStatus().collect { status ->
                        mutableState.update { it.copy(healthConnectStatus = status) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not check sleep schedule access", error)
                }
            }
    }

    fun retryPreferences() {
        if (preferencesJob?.isActive == true) return
        mutableState.update { it.copy(preferences = DaySchedulePreferencesState.Loading(it.preferences.values)) }
        preferencesJob =
            viewModelScope.launch {
                try {
                    observeDayScheduleValues(settingsRepository, preferencesDataSource).collect { values ->
                        mutableState.update { it.copy(preferences = DaySchedulePreferencesState.Available(values)) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read day schedule preferences", error)
                    mutableState.update { it.copy(preferences = DaySchedulePreferencesState.Unavailable(it.preferences.values)) }
                }
            }
    }
}
