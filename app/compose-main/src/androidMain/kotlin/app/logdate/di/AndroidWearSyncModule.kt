package app.logdate.di

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
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
import app.logdate.client.watch.AndroidCompanionDeviceClient
import app.logdate.client.watch.AndroidWatchConnectionManager
import app.logdate.client.watch.AndroidWatchDiagnosticsAccess
import app.logdate.client.watch.CompanionDeviceClient
import app.logdate.client.watch.DefaultWatchAssociationRequestFactory
import app.logdate.client.watch.DefaultWatchCompanionAssociationManager
import app.logdate.client.watch.WatchAssociationRequestFactory
import app.logdate.client.watch.WatchCompanionAssociationManager
import app.logdate.client.watch.presence.WatchPresenceRegistration
import app.logdate.feature.core.settings.ui.watch.PeerStateWatchSnapshotSource
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchPeerSnapshotSource
import app.logdate.feature.core.settings.ui.watch.v2.DefaultWatchSetupCoordinator
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticsAccess
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupCoordinator
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module

private val watchConnectionIoDispatcherQualifier = named("watch-connection-io-dispatcher")

internal val androidWearSyncModule: Module =
    module {
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
