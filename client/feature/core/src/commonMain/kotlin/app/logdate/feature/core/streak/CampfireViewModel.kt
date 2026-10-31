package app.logdate.feature.core.streak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.streak.CampfireState
import app.logdate.client.domain.streak.FirePhase
import app.logdate.client.domain.streak.FireSize
import app.logdate.client.domain.streak.ObserveCampfireUseCase
import app.logdate.ui.streak.CampfirePhase
import app.logdate.ui.streak.CampfirePresentation
import app.logdate.ui.streak.CampfireSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Exposes the journaling campfire for any surface that draws it, or `null` when there is nothing
 * to draw: streak tracking is off or the fire could not be read.
 */
class CampfireViewModel(
    campfireFlow: Flow<CampfireState?>,
) : ViewModel() {
    constructor(observeCampfire: ObserveCampfireUseCase) : this(campfireFlow = observeCampfire())

    val presentation: StateFlow<CampfirePresentation?> =
        campfireFlow
            .map { it?.toPresentation() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = null,
            )
}

internal fun CampfireState.toPresentation(): CampfirePresentation =
    CampfirePresentation(
        phase =
            when (phase) {
                FirePhase.UNLIT -> CampfirePhase.UNLIT
                FirePhase.BURNING -> CampfirePhase.BURNING
                FirePhase.EMBERS -> CampfirePhase.EMBERS
                FirePhase.OUT -> CampfirePhase.OUT
            },
        loggedToday = loggedToday,
        runDays = runDays,
        size =
            when (size) {
                null -> null
                FireSize.SPARK -> CampfireSize.SPARK
                FireSize.SMALL -> CampfireSize.SMALL
                FireSize.CAMPFIRE -> CampfireSize.CAMPFIRE
                FireSize.BONFIRE -> CampfireSize.BONFIRE
                FireSize.BEACON -> CampfireSize.BEACON
            },
        longestRunDays = longestRunDays,
        totalDaysJournaled = totalDaysJournaled,
        isRekindled = isRekindled,
    )
