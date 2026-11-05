package app.logdate.feature.core.settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.dayboundary.HealthConnectGateKind
import app.logdate.client.domain.dayboundary.HealthConnectGateState
import app.logdate.client.domain.dayboundary.HealthConnectStatus
import app.logdate.client.domain.dayboundary.ObserveHealthConnectStatusUseCase
import app.logdate.client.domain.dayboundary.reduceHealthConnectGateState
import app.logdate.client.domain.dayboundary.withFailureReporting
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DayBoundarySettingsViewModel(
    settingsRepository: DayBoundarySettingsRepository,
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val observeHealthConnectStatus: ObserveHealthConnectStatusUseCase,
) : ViewModel() {
    private val settingsRepository = settingsRepository.withFailureReporting()

    data class UiState(
        val preferences: DaySchedulePreferencesState = DaySchedulePreferencesState.Loading(),
        val healthConnectStatus: HealthConnectStatus = HealthConnectStatus.CHECKING,
        val healthCheck: SleepAccessCheckState = SleepAccessCheckState.Checking,
        val sleepWrite: DaySettingWriteState<Boolean> = DaySettingWriteState.Idle,
        val fallbackWrite: DaySettingWriteState<Int> = DaySettingWriteState.Idle,
        val gateState: HealthConnectGateState = HealthConnectGateState(HealthConnectGateKind.CHECKING),
    ) {
        val dayBoundarySettings get() = preferences.values?.settings
        val fallbackStartHour get() = preferences.values?.fallbackStartHour
    }

    private val mutableState = MutableStateFlow(UiState())
    val uiState = mutableState.asStateFlow()
    private var preferencesJob: Job? = null
    private var healthStatusJob: Job? = null
    private var hasPermission: Boolean? = null
    private var permissionRequested = false
    private var permissionRequestPending = false
    private var enableAfterPreferencesLoad = false
    private var previousResolvedGate: HealthConnectGateState? = null

    init {
        retryPreferences()
        refreshHealthStatus()
    }

    fun retryPreferences() {
        if (preferencesJob?.isActive == true) return
        update { it.copy(preferences = DaySchedulePreferencesState.Loading(it.preferences.values)) }
        preferencesJob =
            viewModelScope.launch {
                try {
                    observeDayScheduleValues(settingsRepository, preferencesDataSource).collect { values ->
                        update { it.copy(preferences = DaySchedulePreferencesState.Available(values)) }
                        if (enableAfterPreferencesLoad) {
                            enableAfterPreferencesLoad = false
                            if (!values.settings.sleepBasedBoundariesEnabled) toggleSleepBasedBoundaries(true)
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read day schedule preferences", error)
                    update { it.copy(preferences = DaySchedulePreferencesState.Unavailable(it.preferences.values)) }
                }
            }
    }

    fun toggleSleepBasedBoundaries(enabled: Boolean) {
        enableAfterPreferencesLoad = false
        permissionRequestPending = false
        val current = uiState.value.dayBoundarySettings ?: return
        if (uiState.value.sleepWrite is DaySettingWriteState.Saving) return
        if (current.sleepBasedBoundariesEnabled == enabled) {
            update { it.copy(sleepWrite = DaySettingWriteState.Idle) }
            return
        }
        update { it.copy(sleepWrite = DaySettingWriteState.Saving(enabled)) }
        viewModelScope.launch {
            try {
                settingsRepository.setSleepBasedBoundariesEnabled(enabled)
                update { state ->
                    state.copy(
                        sleepWrite = DaySettingWriteState.Idle,
                        preferences =
                            if (preferencesJob?.isActive != true ||
                                state.preferences is DaySchedulePreferencesState.Unavailable
                            ) {
                                state.preferences.withConfirmedValues {
                                    it.copy(
                                        settings = it.settings.copy(sleepBasedBoundariesEnabled = enabled),
                                    )
                                }
                            } else {
                                state.preferences
                            },
                    )
                }
            } catch (cancelled: CancellationException) {
                update { it.copy(sleepWrite = DaySettingWriteState.Idle) }
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Could not save sleep-based boundaries", error)
                update { it.copy(sleepWrite = DaySettingWriteState.Failed(enabled)) }
            }
        }
    }

    fun retrySleepWrite() {
        (uiState.value.sleepWrite as? DaySettingWriteState.Failed)?.let { toggleSleepBasedBoundaries(it.requested) }
    }

    fun setFallbackStartHour(hour: Int) {
        val current = uiState.value.fallbackStartHour ?: return
        if (hour !in 0..23 || uiState.value.fallbackWrite is DaySettingWriteState.Saving) return
        if (current == hour) {
            update { it.copy(fallbackWrite = DaySettingWriteState.Idle) }
            return
        }
        update { it.copy(fallbackWrite = DaySettingWriteState.Saving(hour)) }
        viewModelScope.launch {
            try {
                preferencesDataSource.setDayBounds(startHour = hour, endHour = hour)
                update { state ->
                    state.copy(
                        fallbackWrite = DaySettingWriteState.Idle,
                        preferences =
                            if (preferencesJob?.isActive != true ||
                                state.preferences is DaySchedulePreferencesState.Unavailable
                            ) {
                                state.preferences.withConfirmedValues { it.copy(fallbackStartHour = hour) }
                            } else {
                                state.preferences
                            },
                    )
                }
            } catch (cancelled: CancellationException) {
                update { it.copy(fallbackWrite = DaySettingWriteState.Idle) }
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Could not save fallback day boundary", error)
                update { it.copy(fallbackWrite = DaySettingWriteState.Failed(hour)) }
            }
        }
    }

    fun retryFallbackWrite() {
        (uiState.value.fallbackWrite as? DaySettingWriteState.Failed)?.let { setFallbackStartHour(it.requested) }
    }

    fun onPermissionRequestStarted() {
        permissionRequestPending = true
    }

    /** Result callbacks cannot be lost when Compose conflates identical permission snapshots. */
    fun onPermissionResult(granted: Boolean) {
        if (!permissionRequestPending) return
        permissionRequestPending = false
        if (!granted) return
        val preferences = uiState.value.dayBoundarySettings
        if (preferences == null) {
            enableAfterPreferencesLoad = true
        } else if (!preferences.sleepBasedBoundariesEnabled) {
            toggleSleepBasedBoundaries(true)
        }
        refreshHealthStatus()
    }

    fun onPermissionStatus(
        hasPermission: Boolean,
        requested: Boolean,
    ) {
        val changed = this.hasPermission != null && this.hasPermission != hasPermission
        this.hasPermission = hasPermission
        permissionRequested = requested
        update { it }
        if (changed) refreshHealthStatus()
    }

    fun refreshHealthStatus() {
        if (healthStatusJob?.isActive == true) return
        update { it.copy(healthCheck = SleepAccessCheckState.Checking) }
        healthStatusJob =
            viewModelScope.launch {
                try {
                    observeHealthConnectStatus().collect { status ->
                        update { it.copy(healthConnectStatus = status) }
                    }
                    update { it.copy(healthCheck = SleepAccessCheckState.Idle) }
                } catch (cancelled: CancellationException) {
                    update { it.copy(healthCheck = SleepAccessCheckState.Idle) }
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not check sleep schedule access", error)
                    update { it.copy(healthCheck = SleepAccessCheckState.Failed) }
                }
            }
    }

    private fun update(transform: (UiState) -> UiState) {
        val next = transform(mutableState.value)
        val values = next.preferences.values
        val gate =
            if (values == null) {
                HealthConnectGateState(HealthConnectGateKind.CHECKING)
            } else {
                reduceHealthConnectGateState(
                    sleepBasedPreferenceEnabled = values.settings.sleepBasedBoundariesEnabled,
                    healthConnectStatus = next.healthConnectStatus,
                    hasPermission = hasPermission == true,
                    permissionRequested = permissionRequested,
                    previousResolvedGateState = previousResolvedGate,
                )
            }
        if (gate.kind != HealthConnectGateKind.CHECKING) previousResolvedGate = gate
        mutableState.value = next.copy(gateState = gate)
    }
}

sealed interface SleepAccessCheckState {
    data object Idle : SleepAccessCheckState

    data object Checking : SleepAccessCheckState

    data object Failed : SleepAccessCheckState
}
