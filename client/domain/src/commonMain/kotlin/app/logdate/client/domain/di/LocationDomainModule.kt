package app.logdate.client.domain.di

import app.logdate.client.domain.location.CaptureLocationForTimelineReviewUseCase
import app.logdate.client.domain.location.DeleteLocationEntryUseCase
import app.logdate.client.domain.location.DeleteLocationRangeUseCase
import app.logdate.client.domain.location.GetLocationHistoryUseCase
import app.logdate.client.domain.location.LocationRetryWorker
import app.logdate.client.domain.location.LogCurrentLocationUseCase
import app.logdate.client.domain.location.ObserveLocationHistoryUseCase
import app.logdate.client.domain.location.ObserveLocationRetryStatusUseCase
import app.logdate.client.domain.location.ObserveLocationStopsUseCase
import app.logdate.client.domain.location.history.LocationHistoryService
import app.logdate.client.domain.world.GetLocationUseCase
import app.logdate.client.domain.world.LogLocationUseCase
import app.logdate.client.domain.world.ObserveLocationUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Module for location-related domain use cases.
 * Separated to avoid circular dependencies with data modules.
 */
val locationDomainModule: Module =
    module {
        single {
            LocationHistoryService(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                activityHistory = get(),
                placeFamiliarity = getOrNull(),
                placeResolutionCache = getOrNull(),
            )
        }
        // Location History
        factory { GetLocationHistoryUseCase(get()) }
        factory { DeleteLocationEntryUseCase(get()) }
        factory { DeleteLocationRangeUseCase(get()) }
        factory { ObserveLocationHistoryUseCase(get()) }
        factory { ObserveLocationStopsUseCase(get()) }

        // World
        factory { GetLocationUseCase(get()) }
        factory { ObserveLocationUseCase(get()) }
        factory { LogLocationUseCase(get(), get()) }

        // Location retry system
        single { LocationRetryWorker(get(), get(), get()) }
        factory { LogCurrentLocationUseCase(get(), get(), get(), get(), get()) }
        factory { CaptureLocationForTimelineReviewUseCase(get(), get()) }
        factory { ObserveLocationRetryStatusUseCase(get()) }
    }
