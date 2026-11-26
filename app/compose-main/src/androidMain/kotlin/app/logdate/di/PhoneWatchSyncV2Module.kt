package app.logdate.di

import app.logdate.client.database.LogDateDatabase
import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.datastore.featureflags.PHONE_ACTIVITY_CONSENT_STORE
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.device.crypto.link.BouncyCastleLinkHandshake
import app.logdate.client.device.restore.createAndroidInstallationWatchStorage
import app.logdate.client.domain.activity.ActivityTracker
import app.logdate.client.domain.activity.PhoneActivityRegistration
import app.logdate.client.domain.activity.PhoneActivityRegistrationState
import app.logdate.client.domain.activity.toSnapshot
import app.logdate.client.domain.reminders.LearnedReminderHours
import app.logdate.client.domain.reminders.ReminderPostedHistory
import app.logdate.client.domain.streak.DEFAULT_STREAK_DAY_START_HOUR
import app.logdate.client.domain.streak.ObserveCampfireUseCase
import app.logdate.client.domain.watch.StampedPreferenceStore
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.sync.CreateCollisionPolicy
import app.logdate.client.sync.FileWatchAudioStore
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.WatchCaptureReceipt
import app.logdate.client.sync.WatchNoteIngestor
import app.logdate.client.sync.WatchNoteNotifier
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.peer.delivery.CaptureIdentity
import app.logdate.client.sync.peer.delivery.DeliveryDirection
import app.logdate.client.sync.peer.delivery.PeerDeliveryLedger
import app.logdate.client.sync.peer.delivery.PeerScopes
import app.logdate.client.sync.peer.link.PairKeyVault
import app.logdate.client.sync.peer.link.PhoneLinkExchange
import app.logdate.client.sync.peer.link.SecureStoragePairKeyVault
import app.logdate.client.sync.peer.state.PeerCapabilityPresence
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.ReminderHours
import app.logdate.client.sync.v2.PhoneStateParts
import app.logdate.client.sync.v2.PhoneStatePublisher
import app.logdate.client.sync.v2.link.AccountKeySource
import app.logdate.client.sync.v2.link.AndroidWatchLinkNotifier
import app.logdate.client.sync.v2.link.CapabilityLinkTargets
import app.logdate.client.sync.v2.link.CloudLinkBundleSource
import app.logdate.client.sync.v2.link.DeviceAccountKeySource
import app.logdate.client.sync.v2.link.LinkBundleSource
import app.logdate.client.sync.v2.link.LinkCodeMinter
import app.logdate.client.sync.v2.link.LinkRequester
import app.logdate.client.sync.v2.link.LinkTargets
import app.logdate.client.sync.v2.link.MessageClientLinkRequester
import app.logdate.client.sync.v2.link.PhoneLinkClientFactory
import app.logdate.client.sync.v2.link.PhoneLinkInitiator
import app.logdate.client.sync.v2.link.PhoneWatchLinkStore
import app.logdate.client.sync.v2.link.WatchLinkNotifier
import app.logdate.client.sync.v2.localNodeIdOrNull
import app.logdate.client.sync.v2.receipts.GoogleWatchPeerMessageSender
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import app.logdate.client.sync.v2.receipts.PhoneReceiptBook
import app.logdate.client.sync.v2.receipts.ReceiptAcknowledger
import app.logdate.client.sync.v2.receipts.RecordRejection
import app.logdate.client.sync.v2.receipts.ScopedCaptureIngestion
import app.logdate.client.sync.v2.receipts.WatchCaptureCollisionPolicy
import app.logdate.client.sync.v2.receipts.WatchCaptureNotes
import app.logdate.client.sync.v2.receipts.WatchCaptureProvenance
import app.logdate.client.sync.v2.receipts.WatchCaptureRemovals
import app.logdate.client.sync.v2.receipts.WatchMomentIngestor
import app.logdate.client.sync.v2.receipts.WatchPeerMessageSender
import app.logdate.client.sync.v2.receipts.WatchReceiptNotifier
import app.logdate.client.sync.v2.receipts.WatchReceiptResponder
import app.logdate.client.sync.v2.receipts.WatchRecordIngestorV2
import app.logdate.client.sync.v2.toSnapshot
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.io.File
import kotlin.time.Clock

/** Koin module for the phone's side of the version 2 watch protocol. */
val phoneWatchSyncV2Module =
    module {
        includes(phoneContinuityModule)
        includes(phoneMomentSyncModule)
        includes(phoneWatchWindowModule)
        includes(phoneReminderClaimsModule)
        single {
            val clients = PhoneLinkClientFactory(get(), get())
            LinkCodeMinter(sessions = get(), clientsFor = clients::capture)
        }
        single<AccountKeySource> { DeviceAccountKeySource(get(), get(), get()) }
        single<LinkBundleSource> {
            val flags = get<FeatureFlagStore>()
            CloudLinkBundleSource(get(), get(), get(), get(), v2Enabled = { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) })
        }
        single {
            val sessions = get<SessionStorage>()
            val watchPreferences = get<StampedPreferenceStore>()
            val preferences = get<LogdatePreferencesDataSource>()
            val campfire = get<ObserveCampfireUseCase>()
            val peers = get<PhoneDeliveryPeers>()
            val history = get<ReminderPostedHistory>()
            val hours = get<LearnedReminderHours>()
            val appInfo = runCatching { get<AppInfoProvider>().getAppInfo() }.getOrNull()
            val phoneActivity =
                combine(
                    get<FeatureFlagStore>(named(PHONE_ACTIVITY_CONSENT_STORE)).observe(FeatureFlag.ACTIVITY_AWARENESS),
                    get<PhoneActivityRegistration>().state,
                    get<ActivityTracker>().local,
                ) { optedIn, link, local ->
                    // The watch may have its own activity source. Keep the user's preference separate
                    // from whether this phone has a currently registered source to contribute.
                    optedIn to local.toSnapshot().takeIf { link == PhoneActivityRegistrationState.Linked }
                }
            PhoneStatePublisher(
                v2Enabled = get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
                channel = get(),
                capabilities = get(),
                peerStates = get(),
                sharedPreferences = watchPreferences.observe(),
                lastReminderAt = history.observe().map { it?.toEpochMilliseconds() },
                adoptWatchReminderAt = { node, at -> history.adoptPeer(node, at) },
                adoptWatchPreferences = { shared -> watchPreferences.adopt(shared) },
                acceptsWatchPreferences = { node, document ->
                    peers.current(node)?.let { it.linkId == document.watch?.linkId } == true
                },
                features = phoneWatchFeatures,
                parts =
                    combine(
                        sessions.getSessionFlow(),
                        campfire(),
                        hours.observe(),
                        phoneActivity,
                    ) { session, fire, waking, (aware, activity) ->
                        PhoneStateParts(
                            cloud = cloudPresence(session),
                            dayStartHour = preferences.getPreferences().dayStartHour ?: DEFAULT_STREAK_DAY_START_HOUR,
                            campfire = fire?.toSnapshot(Clock.System.now()),
                            reminderHours = waking?.let { ReminderHours(it.wake.toSecondOfDay(), it.sleep.toSecondOfDay()) },
                            activityAwareness = aware,
                            activity = activity,
                        )
                    },
                appVersionName = appInfo?.versionName,
                appVersionCode = appInfo?.versionCode,
                protocolVersion = WearProtocol.PROTOCOL_VERSION,
                minPeerProtocolVersion = WearProtocol.MIN_COMPATIBLE_PROTOCOL_VERSION,
            )
        }
        single<PairKeyVault> { SecureStoragePairKeyVault(get(), prefix = "phone.watch_link.pair_key.") }
        single<KeyValueStorage>(named("installationWatchStorage")) {
            createAndroidInstallationWatchStorage(androidContext())
        }
        single { PhoneWatchLinkStore(storage = get(named("installationWatchStorage")), pairKeys = get(), choices = get()) }
        single { PhoneLinkExchange(BouncyCastleLinkHandshake()) }
        single<LinkRequester> { MessageClientLinkRequester(Wearable.getMessageClient(androidContext())) }
        single<LinkTargets> { CapabilityLinkTargets(Wearable.getCapabilityClient(androidContext())) }
        single<WatchLinkNotifier> { AndroidWatchLinkNotifier(androidContext()) }
        single {
            val nodeClient = Wearable.getNodeClient(androidContext())
            PhoneLinkInitiator(
                v2Enabled = get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
                watchCapable = get<PeerCapabilityPresence>().observe(WearProtocol.WATCH_CAPABILITY_V2),
                watchDocuments = get<PeerStateStore>().documents(PeerRole.WATCH),
                bundles = get(),
                targets = get(),
                requester = get(),
                exchange = get(),
                store = get(),
                notifier = get(),
                localNodeId = { nodeClient.localNodeIdOrNull() },
            )
        }
        single { PeerDeliveryLedger(get()) }
        single<CreateCollisionPolicy> { WatchCaptureCollisionPolicy(get()) }

        single {
            val nodeClient = Wearable.getNodeClient(androidContext())
            val gate = get<WearProtocolGate>()
            PhoneDeliveryPeers(
                links = get(),
                sessions = get(),
                config = get(),
                localNodeId = { nodeClient.localNodeIdOrNull() },
                active = { gate.isFeatureActive(WearFeatures.RECEIPTS).first() },
            )
        }
        single { PhoneReceiptBook(notes = get(), outbox = get(), sessions = get(), ledger = get(), peers = get(), momentReceipts = get()) }
        single { WatchReceiptResponder(get()) }
        single<WatchPeerMessageSender> { GoogleWatchPeerMessageSender(Wearable.getMessageClient(androidContext()), get()) }
        single {
            val gate = get<WearProtocolGate>()
            WatchReceiptNotifier(
                book = get(),
                ledger = get(),
                sender = get(),
                active = { gate.isFeatureActive(WearFeatures.RECEIPTS).first() },
                peers = get(),
            )
        }
        single { WatchCaptureProvenance(get<LogDateDatabase>().peerDeliveryDao()) }
        single {
            val peers = get<PhoneDeliveryPeers>()
            val notes = get<JournalNotesRepository>() as SyncableJournalNotesRepository
            val transactions = get<SyncTransactionManager>()
            val outbox = get<SyncMetadataService>()
            val ledger = get<PeerDeliveryLedger>()
            val receipts = get<WatchReceiptNotifier>()
            val provenance = get<WatchCaptureProvenance>()
            val notifier = get<WatchNoteNotifier>()
            val mapper = get<NoteDataMapper>()
            val files = androidContext().filesDir
            val mediaFiles = get<MediaFileResolver>()
            val removals =
                WatchCaptureRemovals(
                    notes,
                    transactions,
                    get<LogDateDatabase>().peerDeliveryDao(),
                    peers,
                    receipts,
                    get(),
                    outbox,
                    onUpdated = { get<SyncManager>().sync() },
                )
            ScopedCaptureIngestion(peers, removals, get<WatchMomentIngestor>()) { peer, operation ->
                val guarded =
                    WatchCaptureNotes(
                        notes,
                        transactions,
                        outbox,
                        { peers.isCurrent(peer) },
                        { get<SyncManager>().sync() },
                        provenance::remember,
                        canStore = { !removals.isRemoved(peer, it) },
                    )
                val acknowledger = ReceiptAcknowledger(ledger, receipts, peer, operation)
                val ingestor =
                    WatchNoteIngestor(
                        guarded,
                        FileWatchAudioStore.scoped(
                            File(files, "audio_notes"),
                            File(files, "watch_incoming"),
                            peer,
                            operationId = operation,
                        ),
                        acknowledger,
                        notifier,
                        mapper,
                        proveExistingAudio =
                            if (operation == null) {
                                null
                            } else {
                                { note, data ->
                                    WatchCaptureReceipt.existingAudioMatches(note, data, mediaFiles::filePath)
                                }
                            },
                    )
                WatchRecordIngestorV2(
                    ingestMetadata = ingestor::onNoteMetadata,
                    ingestAudio = { id, input -> ingestor.onAudioBytes(id, input) },
                    notes = guarded,
                    acknowledger = acknowledger,
                    rejection =
                        RecordRejection { kind, id ->
                            receipts.reject(peer, kind, id, operation)
                        },
                    mapper = mapper,
                    operationId = operation,
                    receivedVersion = { id ->
                        val key = operation?.let { CaptureIdentity.of(id.toString(), it)?.ledgerKey } ?: id.toString()
                        ledger.get(peer.ledgerScope, DeliveryDirection.FROM_WATCH, EntityType.NOTE, key)?.contentVersion
                    },
                )
            }
        }
        single { ReceiptAcknowledger(get(), get()) }
        single {
            val acknowledger = get<ReceiptAcknowledger>()
            val ledger = get<PeerDeliveryLedger>()
            val ingestor =
                WatchNoteIngestor(
                    notesRepository = get(),
                    audioStore = get(),
                    acknowledger = acknowledger,
                    notifier = get(),
                    noteDataMapper = get(),
                )
            WatchRecordIngestorV2(
                ingestMetadata = ingestor::onNoteMetadata,
                ingestAudio = { id, input -> ingestor.onAudioBytes(id, input) },
                notes = get(),
                acknowledger = acknowledger,
                rejection = get<WatchReceiptNotifier>(),
                receivedVersion = { id ->
                    ledger.get(PeerScopes.WATCHES, DeliveryDirection.FROM_WATCH, EntityType.NOTE, id.toString())?.contentVersion
                },
                mapper = get(),
                scoped = get(),
            )
        }
    }

internal fun cloudPresence(session: UserSession?): CloudPresence =
    if (session == null) CloudPresence.NO_ACCOUNT else CloudPresence.SIGNED_IN

val phoneWatchFeatures =
    setOf(
        WearFeatures.LIVE,
        WearFeatures.PHONE_PLAYBACK,
        WearFeatures.PHONE_SETTINGS_LINK,
        WearFeatures.PREFS,
        WearFeatures.LINK_CLOUD,
        WearFeatures.RECEIPTS,
        WearFeatures.OPERATION_RECEIPTS,
        WearFeatures.MOMENTS,
        WearFeatures.REMINDERS,
        WearFeatures.WINDOW,
        WearFeatures.MEDIA_RANGE,
    )
