package app.logdate.feature.location.timeline.di

import app.logdate.client.domain.location.history.SuggestNearbyHistoryPlacesUseCase
import app.logdate.feature.location.timeline.ui.LocationTimelineViewModel
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val locationTimelineFeatureModule: Module =
    module {
        single { SuggestNearbyHistoryPlacesUseCase(get()) }
        viewModel { HumanLocationHistoryViewModel(get(), get(), get(), get(), get()) }
        viewModel { LocationTimelineViewModel(get(), get(), get(), get(), get(), get()) }
    }

@Deprecated(
    message = "Use locationTimelineFeatureModule instead",
    replaceWith = ReplaceWith("locationTimelineFeatureModule"),
)
val locationTimelineModule: Module get() = locationTimelineFeatureModule
