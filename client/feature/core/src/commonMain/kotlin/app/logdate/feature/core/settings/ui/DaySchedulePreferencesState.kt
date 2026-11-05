package app.logdate.feature.core.settings.ui

import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.dayboundary.DayBoundarySettings
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import kotlinx.coroutines.flow.combine

data class DayScheduleValues(
    val settings: DayBoundarySettings,
    val fallbackStartHour: Int,
)

sealed interface DaySchedulePreferencesState {
    val values: DayScheduleValues?

    data class Loading(
        override val values: DayScheduleValues? = null,
    ) : DaySchedulePreferencesState

    data class Available(
        override val values: DayScheduleValues,
    ) : DaySchedulePreferencesState

    data class Unavailable(
        override val values: DayScheduleValues? = null,
    ) : DaySchedulePreferencesState
}

sealed interface DaySettingWriteState<out T> {
    data object Idle : DaySettingWriteState<Nothing>

    data class Saving<T>(
        val requested: T,
    ) : DaySettingWriteState<T>

    data class Failed<T>(
        val requested: T,
    ) : DaySettingWriteState<T>
}

internal fun observeDayScheduleValues(
    settings: DayBoundarySettingsRepository,
    preferences: LogdatePreferencesDataSource,
) = combine(settings.observeSettings(), preferences.observeDayStartHour()) { stored, hour ->
    // The default is real only after the preference store has emitted an absent value.
    DayScheduleValues(stored, hour?.takeIf { it in 0..23 } ?: 4)
}

internal fun DaySchedulePreferencesState.withConfirmedValues(
    transform: (DayScheduleValues) -> DayScheduleValues,
): DaySchedulePreferencesState {
    val current = values ?: return this
    val confirmed = transform(current)
    return when (this) {
        is DaySchedulePreferencesState.Loading -> copy(values = confirmed)
        is DaySchedulePreferencesState.Available -> copy(values = confirmed)
        is DaySchedulePreferencesState.Unavailable -> copy(values = confirmed)
    }
}
