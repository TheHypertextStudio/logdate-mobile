package app.logdate.di

import android.app.Application
import app.logdate.client.bubble.FlagRecordingBubbleSwitch
import app.logdate.client.bubble.RecordingBubbleNotifier
import app.logdate.client.calendar.AndroidCalendarImportLauncher
import app.logdate.client.calendar.CalendarImportScheduler
import app.logdate.client.calendar.di.calendarSyncModule
import app.logdate.client.data.di.appDataModule
import app.logdate.client.device.di.deviceModule
import app.logdate.client.domain.di.accountDomainModule
import app.logdate.client.domain.di.domainModule
import app.logdate.client.domain.di.locationDomainModule
import app.logdate.client.domain.di.quotaDomainModule
import app.logdate.client.domain.events.CalendarImportLauncher
import app.logdate.client.domain.events.EventInferenceLauncher
import app.logdate.client.events.AndroidEventInferenceLauncher
import app.logdate.client.events.EventInferenceScheduler
import app.logdate.client.feature.widgets.di.widgetModule
import app.logdate.client.handoff.SyncDraftRefresher
import app.logdate.client.health.di.androidHealthModule
import app.logdate.client.health.di.healthModule
import app.logdate.client.intelligence.di.intelligenceModule
import app.logdate.client.location.di.locationModule
import app.logdate.client.media.di.audioModule
import app.logdate.client.networking.di.networkingModule
import app.logdate.client.notifications.FlagLiveUpdatesSwitch
import app.logdate.client.notifications.LiveUpdatesSwitch
import app.logdate.client.notifications.RecordingBubbleSwitch
import app.logdate.client.platform.PackageManagerComponentSwitch
import app.logdate.client.platform.PlatformComponentToggler
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.rewind.MilestoneRewindCoordinator
import app.logdate.client.rewind.RewindNotificationCoordinator
import app.logdate.client.sensor.di.sensorModule
import app.logdate.client.tiles.RecordVoiceTileUpdater
import app.logdate.client.tiles.RecordingWindowTracker
import app.logdate.client.updates.PlayInAppUpdateController
import app.logdate.dynamic.DynamicFeatureLoader
import app.logdate.dynamic.PlayDynamicFeatureLoader
import app.logdate.feature.core.export.CloudBackupScheduler
import app.logdate.feature.core.restore.CloudRestoreScheduler
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.editor.handoff.DraftRefresher
import app.logdate.feature.editor.handoff.HandoffDraftReceiver
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

actual val appModule: Module =
    module {
        // Base modules first
        includes(appDataModule)
        includes(sensorModule)
        includes(networkingModule)
        includes(deviceModule)

        // Domain modules in correct dependency order
        includes(accountDomainModule)
        includes(quotaDomainModule)
        includes(locationDomainModule)
        includes(healthModule)
        includes(androidHealthModule)
        includes(calendarSyncModule)
        includes(domainModule)

        // Feature modules
        includes(defaultModules)
        includes(intelligenceModule)
        includes(locationModule)
        includes(audioModule)
        includes(windowingModule)
        includes(widgetModule)
        includes(phoneWatchSyncV2Module)

        // Android submodules
        includes(androidWorkersModule)
        includes(androidAmbientModule)
        includes(androidShortcutsModule)
        includes(androidWearSyncModule)

        // Platform services & coordinators
        single { RewindNotificationCoordinator(androidContext()) }
        single {
            MilestoneRewindCoordinator(
                detectors = get(),
                rewindRepository = get(),
                generateRewind = get(),
                shortcutScheduler = get(),
            )
        }

        single { EventInferenceScheduler(androidContext()) }
        single<EventInferenceLauncher> { AndroidEventInferenceLauncher(get()) }

        single { CalendarImportScheduler(androidContext()) }
        single<CalendarImportLauncher> { AndroidCalendarImportLauncher(get()) }

        single { CloudBackupScheduler(androidContext()) }
        single { CloudRestoreScheduler(androidContext(), get()) }

        single { RecordVoiceTileUpdater(androidContext(), get()) }
        single { RecordingWindowTracker(get()) }
        single<LiveUpdatesSwitch> { FlagLiveUpdatesSwitch(get(), get()) }
        single<RecordingBubbleSwitch> { FlagRecordingBubbleSwitch(get(), get()) }
        single { RecordingBubbleNotifier(androidContext(), get()) }
        single<DraftRefresher> { SyncDraftRefresher(get()) }
        factory {
            val drafts = get<EntryDraftRepository>()
            HandoffDraftReceiver(hasDraft = { id -> drafts.getDraft(id).first().isSuccess }, refresher = get())
        }
        single {
            PlatformComponentToggler(
                flags = get(),
                components = PlatformComponentToggler.osIntegrationComponents,
                switch = PackageManagerComponentSwitch(androidContext()),
            )
        }

        single { PlayInAppUpdateController(androidContext(), get()) }
        single<AppUpdateController> { get<PlayInAppUpdateController>() }

        single<DynamicFeatureLoader> { PlayDynamicFeatureLoader(androidContext()) }
    }

/**
 * Initializes global Koin context with the application module.
 */
internal fun Application.initializeKoin() {
    startKoin {
        androidLogger()
        androidContext(this@initializeKoin)
        modules(appModule)
    }
    initializeLogDateWorkManager(this)
}
