package app.logdate.di

import app.logdate.client.ambient.AmbientPromptScheduler
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.reminders.ReminderSignalObserver
import app.logdate.client.domain.watch.SharedPreferenceKey
import app.logdate.client.domain.watch.WatchPreferencesRepository
import app.logdate.client.permissions.PermissionManager
import app.logdate.client.reminders.MomentReminderWorker
import app.logdate.client.reminders.reminderPlaceAvailability
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.moments.MarkedMomentRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.dsl.module

val phoneReminderSchedulingModule =
    module {
        workerOf(::MomentReminderWorker)
        single {
            val flags = get<FeatureFlagStore>()
            val shared = get<WatchPreferencesRepository>()
            val locations = get<LocationHistoryRepository>()
            val marks = get<MarkedMomentRepository>()
            val permissions = get<PermissionManager>()
            val scheduler = get<AmbientPromptScheduler>()
            val enabled =
                combine(flags.observe(FeatureFlag.WATCH_EXPERIENCE_V2), shared.observe(SharedPreferenceKey.RemindersEnabled)) {
                    rollout,
                    reminders,
                    ->
                    rollout && reminders
                }.distinctUntilChanged()
            ReminderSignalObserver(
                enabled = enabled,
                placesEnabled = reminderPlaceAvailability(enabled, shared.observe(SharedPreferenceKey.CapturePlaces), permissions),
                markChanges = { marks.observeOpenMarks().distinctUntilChanged().map { } },
                locationChanges = { locations.observeLastLocation().distinctUntilChanged().map { } },
                enqueue = scheduler::enqueueMomentEvaluation,
                scope = get(),
            )
        }
    }
