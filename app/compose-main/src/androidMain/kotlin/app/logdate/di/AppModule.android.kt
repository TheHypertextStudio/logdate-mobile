package app.logdate.di

import android.app.Application
import app.logdate.client.ambient.AmbientPromptNotificationCoordinator
import app.logdate.client.ambient.AmbientPromptScheduler
import app.logdate.client.ambient.AmbientPromptSchedulingObserver
import app.logdate.client.ambient.AmbientPromptWorker
import app.logdate.client.bubble.FlagRecordingBubbleSwitch
import app.logdate.client.bubble.RecordingBubbleNotifier
import app.logdate.client.calendar.AndroidCalendarImportLauncher
import app.logdate.client.calendar.CalendarImportScheduler
import app.logdate.client.calendar.CalendarImportWorker
import app.logdate.client.calendar.di.calendarSyncModule
import app.logdate.client.data.di.appDataModule
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.di.deviceModule
import app.logdate.client.domain.di.accountDomainModule
import app.logdate.client.domain.di.domainModule
import app.logdate.client.domain.di.locationDomainModule
import app.logdate.client.domain.di.quotaDomainModule
import app.logdate.client.domain.events.CalendarImportLauncher
import app.logdate.client.domain.events.EventInferenceLauncher
import app.logdate.client.domain.journals.GetCurrentUserJournalsUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.rewind.GetWeekRewindUseCase
import app.logdate.client.events.AndroidEventInferenceLauncher
import app.logdate.client.events.EventInferenceScheduler
import app.logdate.client.events.EventInferenceWorker
import app.logdate.client.feature.widgets.di.widgetModule
import app.logdate.client.feature.widgets.shortcuts.DynamicShortcutPublisher
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
import app.logdate.client.platform.FeatureFlagState
import app.logdate.client.platform.PackageManagerComponentSwitch
import app.logdate.client.platform.PlatformComponentToggler
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.restore.NativeMediaQuarantineWorker
import app.logdate.client.rewind.MilestoneRewindCoordinator
import app.logdate.client.rewind.RewindGenerationWorker
import app.logdate.client.rewind.RewindNotificationCoordinator
import app.logdate.client.sensor.di.sensorModule
import app.logdate.client.shortcuts.AndroidCaptureUsageReporter
import app.logdate.client.shortcuts.AndroidDynamicShortcutApplier
import app.logdate.client.shortcuts.AndroidHomeScreenPins
import app.logdate.client.shortcuts.DynamicShortcutRefreshObserver
import app.logdate.client.shortcuts.DynamicShortcutRefreshWorker
import app.logdate.client.shortcuts.DynamicShortcutScheduler
import app.logdate.client.shortcuts.JournalShortcutIcons
import app.logdate.client.sync.AndroidPhoneAudioStreamOpener
import app.logdate.client.sync.AndroidWatchNoteNotificationPoster
import app.logdate.client.sync.DefaultPhoneWearSyncBridge
import app.logdate.client.sync.FileWatchAudioStore
import app.logdate.client.sync.GooglePhoneWearTransport
import app.logdate.client.sync.GoogleWatchMessageSender
import app.logdate.client.sync.MessageWatchNoteAcknowledger
import app.logdate.client.sync.PhoneAudioStreamOpener
import app.logdate.client.sync.PhoneWearSyncBridge
import app.logdate.client.sync.PhoneWearTransport
import app.logdate.client.sync.WatchAudioStore
import app.logdate.client.sync.WatchJournalIngestor
import app.logdate.client.sync.WatchMessageSender
import app.logdate.client.sync.WatchNoteAcknowledger
import app.logdate.client.sync.WatchNoteArrivalNotifier
import app.logdate.client.sync.WatchNoteIngestor
import app.logdate.client.sync.WatchNoteNotifier
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.client.sync.peer.state.LocalCapabilities
import app.logdate.client.sync.peer.state.PeerCapabilityPresence
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.StateDocumentChannel
import app.logdate.client.sync.v2.PhonePeerLink
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import app.logdate.client.tiles.RecordVoiceTileUpdater
import app.logdate.client.tiles.RecordingWindowTracker
import app.logdate.client.updates.PlayInAppUpdateController
import app.logdate.client.watch.AndroidCompanionDeviceClient
import app.logdate.client.watch.AndroidWatchConnectionManager
import app.logdate.client.watch.AndroidWatchDiagnosticsAccess
import app.logdate.client.watch.CompanionDeviceClient
import app.logdate.client.watch.DefaultWatchAssociationRequestFactory
import app.logdate.client.watch.DefaultWatchCompanionAssociationManager
import app.logdate.client.watch.WatchAssociationRequestFactory
import app.logdate.client.watch.WatchCompanionAssociationManager
import app.logdate.client.watch.presence.WatchPresenceRegistration
import app.logdate.dynamic.DynamicFeatureLoader
import app.logdate.dynamic.PlayDynamicFeatureLoader
import app.logdate.feature.core.export.CloudBackupScheduler
import app.logdate.feature.core.export.CloudBackupWorker
import app.logdate.feature.core.restore.CloudRestoreScheduler
import app.logdate.feature.core.settings.ui.watch.PeerStateWatchSnapshotSource
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchPeerSnapshotSource
import app.logdate.feature.core.settings.ui.watch.v2.DefaultWatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticsAccess
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupCoordinator
import app.logdate.feature.core.settings.updates.AppUpdateController
import app.logdate.feature.editor.handoff.DraftRefresher
import app.logdate.feature.editor.handoff.HandoffDraftReceiver
import app.logdate.feature.editor.ui.editor.CaptureUsageReporter
import app.logdate.ui.common.HomeScreenPins
import coil3.SingletonImageLoader
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

private val watchConnectionIoDispatcherQualifier = named("watch-connection-io-dispatcher")

actual val appModule: Module =
    module {
        // Base modules first
        includes(appDataModule)
        includes(sensorModule)
        includes(networkingModule)
        includes(deviceModule)

        // Domain modules in correct dependency order
        includes(accountDomainModule) // Account domain depends on data layers
        includes(quotaDomainModule) // Quota domain depends on sync layer
        includes(locationDomainModule) // Location domain depends on data layers
        includes(healthModule) // Common Health Connect implementation
        includes(androidHealthModule) // Android-specific Health Connect implementation
        includes(calendarSyncModule) // OS calendar reader for the import worker
        includes(domainModule) // Main domain module with no circular deps

        // Feature modules
        includes(defaultModules)
        includes(intelligenceModule)
        includes(locationModule)
        includes(audioModule)
        includes(windowingModule)
        includes(widgetModule)
        includes(phoneWatchSyncV2Module)

        single {
            val quickReply = FeatureFlagState(get(), FeatureFlag.NOTIFICATION_QUICK_REPLY, get())
            AmbientPromptNotificationCoordinator(androidContext(), quickReplyEnabled = quickReply::enabled)
        }
        single { AmbientPromptScheduler(androidContext(), get(), get(), get()) }
        single { AmbientPromptSchedulingObserver(get(), get(), get(), get(), get()) }
        workerOf(::AmbientPromptWorker)

        single { RewindNotificationCoordinator(androidContext()) }
        single {
            MilestoneRewindCoordinator(
                detectors = get(),
                rewindRepository = get(),
                generateRewind = get(),
                shortcutScheduler = get(),
            )
        }
        workerOf(::RewindGenerationWorker)

        single { EventInferenceScheduler(androidContext()) }
        single<EventInferenceLauncher> { AndroidEventInferenceLauncher(get()) }
        workerOf(::EventInferenceWorker)

        single { CalendarImportScheduler(androidContext()) }
        single<CalendarImportLauncher> { AndroidCalendarImportLauncher(get()) }
        workerOf(::CalendarImportWorker)

        single { CloudBackupScheduler(androidContext()) }
        single { CloudRestoreScheduler(androidContext(), get()) }
        workerOf(::CloudBackupWorker)
        workerOf(::NativeMediaQuarantineWorker)

        single {
            val fetchMostRecentDraft: FetchMostRecentDraftUseCase = get()
            val getCurrentUserJournals: GetCurrentUserJournalsUseCase = get()
            DynamicShortcutPublisher(
                fetchMostRecentDraft = { fetchMostRecentDraft() },
                currentWeekRewind = { get<GetWeekRewindUseCase>().invoke() },
                observeJournals = { getCurrentUserJournals() },
            )
        }
        single {
            AndroidDynamicShortcutApplier(
                context = androidContext(),
                imageLoader = SingletonImageLoader.get(androidContext()),
            )
        }
        single { DynamicShortcutScheduler(androidContext()) }
        single {
            DynamicShortcutRefreshObserver(
                fetchMostRecentDraft = get(),
                currentWeekRewind = get(),
                observeJournals = get(),
                shortcutScheduler = get(),
                applicationScope = get(),
            )
        }
        workerOf(::DynamicShortcutRefreshWorker)
        single { RecordVoiceTileUpdater(androidContext(), get()) }
        single { RecordingWindowTracker(get()) }
        single<LiveUpdatesSwitch> { FlagLiveUpdatesSwitch(get(), get()) }
        single<RecordingBubbleSwitch> { FlagRecordingBubbleSwitch(get(), get()) }
        single { RecordingBubbleNotifier(androidContext(), get()) }
        single<CaptureUsageReporter> { AndroidCaptureUsageReporter(androidContext()) }
        single<DraftRefresher> { SyncDraftRefresher(get()) }
        factory {
            val drafts = get<EntryDraftRepository>()
            HandoffDraftReceiver(hasDraft = { id -> drafts.getDraft(id).first().isSuccess }, refresher = get())
        }
        single<HomeScreenPins> {
            AndroidHomeScreenPins(
                context = androidContext(),
                icons = JournalShortcutIcons(androidContext(), SingletonImageLoader.get(androidContext())),
                journals = get(),
                scope = get(),
            )
        }
        single {
            PlatformComponentToggler(
                flags = get(),
                components = PlatformComponentToggler.osIntegrationComponents,
                switch = PackageManagerComponentSwitch(androidContext()),
            )
        }

        single { NoteDataMapper() }
        single<WatchNoteNotifier> {
            WatchNoteArrivalNotifier(
                context = androidContext(),
                settingsRepository = get(),
                poster = AndroidWatchNoteNotificationPoster(androidContext()),
            )
        }
        single<PhoneWearTransport> { GooglePhoneWearTransport(androidContext()) }
        single<PhoneAudioStreamOpener> { AndroidPhoneAudioStreamOpener(androidContext()) }
        single<WatchAudioStore> { FileWatchAudioStore(androidContext()) }
        single<WatchMessageSender> { GoogleWatchMessageSender(androidContext()) }
        single<WatchNoteAcknowledger> { MessageWatchNoteAcknowledger(get()) }
        single {
            WatchNoteIngestor(
                notesRepository = get(),
                audioStore = get(),
                acknowledger = get(),
                notifier = get(),
                noteDataMapper = get(),
            )
        }
        single {
            WatchJournalIngestor(
                journalRepository = get(),
                contentRepository = get(),
                journalContentDao = get(),
            )
        }
        single<PhoneWearSyncBridge> {
            DefaultPhoneWearSyncBridge(
                notesRepository = get(),
                noteDataMapper = get(),
                transport = get(),
                audioStreamOpener = get(),
            )
        }
        single { PeerStateStore() }
        single {
            PhonePeerLink(
                transport = get(),
                dataClient = Wearable.getDataClient(androidContext()),
                capabilityClient = Wearable.getCapabilityClient(androidContext()),
                nodeClient = Wearable.getNodeClient(androidContext()),
            )
        } binds arrayOf(StateDocumentChannel::class, LocalCapabilities::class, PeerCapabilityPresence::class)
        single {
            WearProtocolGate(
                flagEnabled = get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
                peerHasV2Capability = get<PeerCapabilityPresence>().observe(WearProtocol.WATCH_CAPABILITY_V2),
                peerFeatures = get<PeerStateStore>().features(PeerRole.WATCH),
            )
        }
        single<WatchPeerSnapshotSource> { PeerStateWatchSnapshotSource(gate = get(), peerStates = get()) }

        single { PlayInAppUpdateController(androidContext(), get()) }
        single<AppUpdateController> { get<PlayInAppUpdateController>() }

        single<DynamicFeatureLoader> { PlayDynamicFeatureLoader(androidContext()) }

        single<CoroutineDispatcher>(watchConnectionIoDispatcherQualifier) { Dispatchers.IO }
        single { WatchPresenceRegistration(androidContext(), get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2)) }
        single {
            app.logdate.client.watch
                .WatchAssociationBindings(get())
        }
        single<CompanionDeviceClient> { AndroidCompanionDeviceClient(androidContext(), get(), get()) }
        single<WatchAssociationRequestFactory> { DefaultWatchAssociationRequestFactory() }
        single<WatchCompanionAssociationManager> {
            DefaultWatchCompanionAssociationManager(
                companionDeviceClient = get(),
                applicationScope = get(),
                associationRequestFactory = get(),
            )
        }

        single<app.logdate.client.sync.peer.removal.WatchRemovalAccess> {
            app.logdate.client.sync.v2.removal
                .AndroidWatchRemovalAccess(androidContext(), get(), get(), get(), get(), get(), get())
        }
        single<WatchDiagnosticsAccess> { AndroidWatchDiagnosticsAccess(androidContext(), get(), get(), get(), get(), get()) }
        single<WatchSetupCoordinator> {
            val peers = get<PhoneDeliveryPeers>()
            DefaultWatchSetupCoordinator(get(), verifiedReady = { state -> state.nodeId?.let { peers.current(it) } != null })
        }

        // Watch connection manager for Wear OS settings
        single<WatchConnectionManager> {
            AndroidWatchConnectionManager(
                context = androidContext(),
                associationManager = get(),
                messageSender = get(),
                ioDispatcher = get(qualifier = watchConnectionIoDispatcherQualifier),
                verifiedAppNodes =
                    combine(
                        get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
                        get<app.logdate.client.sync.v2.link.PhoneWatchLinkStore>().observe(),
                        get<app.logdate.client.datastore.SessionStorage>().getSessionFlow(),
                        get<app.logdate.shared.config.LogDateConfigRepository>().backendUrl,
                    ) { enabled, links, _, _ ->
                        if (enabled) links.keys.filter { get<PhoneDeliveryPeers>().current(it) != null }.toSet() else null
                    },
                prepareWatch = { node ->
                    get<app.logdate.client.sync.v2.PhoneStatePublisher>().refresh()
                    get<app.logdate.client.sync.v2.link.PhoneWatchLinkStore>().allowRelink(node)
                    get<app.logdate.client.sync.v2.link.PhoneLinkInitiator>().reconcileNow()
                },
            )
        }
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
