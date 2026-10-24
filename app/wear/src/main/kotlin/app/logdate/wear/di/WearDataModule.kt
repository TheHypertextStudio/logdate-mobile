package app.logdate.wear.di

import android.content.Context
import androidx.work.WorkManager
import app.logdate.client.data.journals.LocalFirstDraftRepository
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.data.journals.RemoteJournalDataSource
import app.logdate.client.data.location.OfflineFirstLocationHistoryRepository
import app.logdate.client.data.moments.OfflineFirstMarkedMomentRepository
import app.logdate.client.data.notes.DatabaseNotePlaceResolver
import app.logdate.client.data.notes.NotePlaceResolver
import app.logdate.client.data.notes.OfflineFirstJournalNotesRepository
import app.logdate.client.data.notes.StoredMediaReferenceMigration
import app.logdate.client.data.notes.StoredMediaReferenceMigrationLauncher
import app.logdate.client.data.rewind.OfflineFirstRewindRepository
import app.logdate.client.database.databaseModule
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.di.deviceInstanceModule
import app.logdate.client.di.datastoreModule
import app.logdate.client.domain.watch.StampedPreferenceStore
import app.logdate.client.location.di.locationModule
import app.logdate.client.media.storage.AndroidMediaClaimFence
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.media.storage.MediaRescuer
import app.logdate.client.permissions.di.permissionsModule
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.moments.MarkedMomentRepository
import app.logdate.client.repository.moments.SyncableMarkedMomentRepository
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.sync.DefaultSyncManager
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.HealthSnapshotDataMapper
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.client.sync.di.conflictResolverModule
import app.logdate.client.sync.metadata.KeyValueSyncDeadLetterStore
import app.logdate.client.sync.metadata.KeyValueSyncRetryScheduleStore
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.client.sync.peer.state.LocalCapabilities
import app.logdate.client.sync.peer.state.PeerCapabilityPresence
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.StateDocumentChannel
import app.logdate.client.sync.peer.state.StateMerger
import app.logdate.shared.config.configModule
import app.logdate.shared.model.Journal
import app.logdate.wear.capture.WatchCapturePreferences
import app.logdate.wear.health.HealthServicesWearHealthSensorManager
import app.logdate.wear.health.NoteHealthAnnotator
import app.logdate.wear.health.UnavailableWearHealthSensorManager
import app.logdate.wear.health.WearHealthSensorManager
import app.logdate.wear.location.WearLocationCaptureCoordinator
import app.logdate.wear.reminders.SharedWatchReminderSettings
import app.logdate.wear.reminders.WatchReminderHours
import app.logdate.wear.reminders.WatchReminderPreferences
import app.logdate.wear.reminders.WatchReminders
import app.logdate.wear.sync.CapabilityPhoneReachability
import app.logdate.wear.sync.GoogleWearDataLayerClient
import app.logdate.wear.sync.NoteDataMapper
import app.logdate.wear.sync.PhoneReachability
import app.logdate.wear.sync.WatchSyncFacade
import app.logdate.wear.sync.WatchSyncScheduler
import app.logdate.wear.sync.WatchSyncWork
import app.logdate.wear.sync.WearDataLayerClient
import app.logdate.wear.sync.WearDataLayerSyncManager
import app.logdate.wear.sync.WearDeadLetterMigration
import app.logdate.wear.sync.WearNoteAckHandler
import app.logdate.wear.sync.WearNoteRemovalNotifier
import app.logdate.wear.sync.WearPhoneMessageHandler
import app.logdate.wear.sync.WorkManagerWatchSyncWork
import app.logdate.wear.sync.v2.WatchOwnState
import app.logdate.wear.sync.v2.WatchPeerLink
import app.logdate.wear.sync.v2.WatchStateMirror
import app.logdate.wear.sync.v2.cloud.watchCloudPathOf
import app.logdate.wear.sync.v2.cloud.watchCloudStatus
import app.logdate.wear.sync.v2.delivery.WatchCaptureDelivery
import app.logdate.wear.sync.v2.delivery.WatchCaptureProtocol
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import app.logdate.wear.sync.v2.delivery.WatchNotesV2
import app.logdate.wear.sync.v2.delivery.WatchReceiptApplier
import app.logdate.wear.sync.v2.link.WatchLinkStore
import app.logdate.wear.sync.v2.window.WindowReconciler
import com.google.android.gms.wearable.Wearable
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Koin module providing the full data stack for Wear OS.
 *
 * Includes Room database with SqlCipher encryption, DAOs, DataStore preferences,
 * and offline-first repository implementations. Provides local-only account, networking,
 * and Firebase bindings that are not applicable on Wear OS.
 */
val wearDataModule =
    module {
        includes(databaseModule)
        includes(deviceInstanceModule)
        includes(datastoreModule)
        includes(configModule)
        includes(conflictResolverModule)
        includes(permissionsModule)
        includes(locationModule)
        single<MediaClaimFence> { AndroidMediaClaimFence(androidContext().noBackupFilesDir) }
        single {
            OfflineFirstMarkedMomentRepository(get(), get<SyncTransactionManager>(WatchCloud), get())
        } binds arrayOf(MarkedMomentRepository::class, SyncableMarkedMomentRepository::class)

        // JSON serialization
        single {
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
                encodeDefaults = true
            }
        }

        // Data Layer sync: watch <-> phone via Wearable Data API
        single<WearDataLayerClient> { GoogleWearDataLayerClient(get(), get()) }
        single { NoteDataMapper(get()) }
        single { JournalDataMapper(get()) }
        single { AssociationDataMapper() }
        single { HealthSnapshotDataMapper(get()) }
        single<PhoneReachability> { CapabilityPhoneReachability(Wearable.getCapabilityClient(get<Context>()), get()) }
        single<SyncDeadLetterStore> { KeyValueSyncDeadLetterStore(get()) }
        single<SyncRetryScheduleStore> { KeyValueSyncRetryScheduleStore(get()) }
        single {
            WearDataLayerSyncManager(
                dataLayerClient = get(),
                syncMetadataService = get(),
                retryScheduleStore = get(),
                deadLetterStore = get(),
                notesRepository = get(),
                journalRepository = get(),
                healthSnapshotDao = get(),
                noteDataMapper = get(),
                journalDataMapper = get(),
                associationDataMapper = get(),
                healthSnapshotDataMapper = get(),
                mediaFiles = get(),
                phoneReachability = get(),
                scope = get(),
                notesV2 = get<WatchNotesV2>(),
                deliveryStore = get(),
            )
        } binds arrayOf(WearNoteAckHandler::class, WearNoteRemovalNotifier::class)
        single<SyncManager> {
            val koin = getKoin()
            WatchSyncFacade(
                phone = get<WearDataLayerSyncManager>(),
                cloudStatus =
                    watchCloudStatus(
                        path =
                            combine(
                                get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
                                get<WatchLinkStore>().observe(),
                                ::watchCloudPathOf,
                            ),
                        engine = { koin.get<DefaultSyncManager>(WatchCloud) },
                    ),
                scope = get(),
            )
        }
        single {
            val receipts = get<WatchReceiptApplier>()
            val protocol = get<WatchCaptureProtocol>()
            WearPhoneMessageHandler(
                ackHandler = get(),
                syncManager = get(),
                receiptsPushed = receipts::apply,
                legacyAcksAllowed = { !protocol.active() },
                phoneReturned = { get<WindowReconciler>().resync() },
            )
        }
        single { WearDeadLetterMigration(deadLetterStore = get(), syncMetadataService = get()) }
        single<WatchSyncWork> { WorkManagerWatchSyncWork(WorkManager.getInstance(get<Context>())) }
        single {
            WatchSyncScheduler(
                work = get(),
                syncStatus = get<SyncManager>().syncStatusFlow,
                syncMetadataService = get(),
                retryScheduleStore = get(),
                dataLayerClient = get(),
                noteSchedule = get<WatchCaptureDelivery>()::schedule,
                deadLetterStore = get(),
            )
        }
        single { PeerStateStore() }
        single { StateMerger() }
        single { StampedPreferenceStore(storage = get(), localRole = PeerRole.WATCH) }
        single {
            val peers = get<WatchDeliveryPeer>()
            SharedWatchReminderSettings(get(), get(), acceptsPhone = { peers.current()?.phoneNode == it })
        }
        single {
            val peers = get<WatchDeliveryPeer>()
            WatchReminderHours(get(), acceptsPhone = { peers.current()?.phoneNode == it })
        }
        single {
            WatchPeerLink(
                dataLayerClient = get(),
                dataClient = Wearable.getDataClient(get<Context>()),
                capabilityClient = Wearable.getCapabilityClient(get<Context>()),
                nodeClient = Wearable.getNodeClient(get<Context>()),
            )
        } binds arrayOf(StateDocumentChannel::class, LocalCapabilities::class, PeerCapabilityPresence::class)
        single {
            val shared = get<StampedPreferenceStore>()
            val reminders = get<SharedWatchReminderSettings>()
            val controller = get<WatchReminders>()
            val hours = get<WatchReminderHours>()
            WatchStateMirror(
                acceptsPhone = { !get<app.logdate.wear.sync.v2.removal.WatchRemovalLock>().removed() },
                featureFlags = get(),
                channel = get(),
                capabilities = get(),
                peerStates = get(),
                merger = get(),
                ownState = get<WatchOwnState>().observe(),
                sharedPreferences = shared.observe(),
                initializeShared = {
                    reminders.initialize()
                    hours.initialize()
                },
                adoptPhonePreferences = { node, document ->
                    val changed = reminders.adopt(node, document)
                    val hoursChanged = hours.adopt(node, document)
                    if (changed || hoursChanged) controller.restore(rescheduleEvening = hoursChanged)
                },
                lastReminderAt = get<WatchReminderPreferences>().observeLastReminderAt().map { it?.toEpochMilliseconds() },
            )
        }
        single {
            WearProtocolGate(
                flagEnabled = get<FeatureFlagStore>().observe(FeatureFlag.WATCH_EXPERIENCE_V2),
                peerHasV2Capability = get<PeerCapabilityPresence>().observe(WearProtocol.PHONE_CAPABILITY_V2),
                peerFeatures = get<PeerStateStore>().features(PeerRole.PHONE),
            )
        }

        // Health sensor manager: use Health Services when available, then degrade gracefully.
        single<WearHealthSensorManager> {
            try {
                HealthServicesWearHealthSensorManager(get())
            } catch (e: Exception) {
                Napier.w("Health Services not available; health capture disabled for this device", e)
                UnavailableWearHealthSensorManager()
            }
        }
        single { NoteHealthAnnotator(get(), get()) }

        // Remote data source unavailable (no Firebase on Wear)
        factory<RemoteJournalDataSource> { NoOpRemoteJournalDataSource }

        // Draft storage
        single<DraftRepository> { LocalFirstDraftRepository(get(), get()) }

        // Location stack required for standalone Wear journaling and activity tracking.
        single<LocationHistoryRepository> { OfflineFirstLocationHistoryRepository(get()) }
        single {
            val flags = get<FeatureFlagStore>()
            WatchCapturePreferences(get()) { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) }
        }
        single { WearLocationCaptureCoordinator(get(), get(), get<WatchCapturePreferences>()::capturePlaces) }

        // Rewinds are cached locally so the watch can browse content synced from phone.
        single<RewindRepository> { OfflineFirstRewindRepository(get()) }

        // Journals
        single<JournalRepository> {
            OfflineFirstJournalRepository(
                journalDao = get(),
                remoteDataSource = get(),
                draftRepository = get(),
                syncManagerProvider = { get() },
                syncMetadataService = get(),
            )
        }

        // Notes
        single<NotePlaceResolver> { DatabaseNotePlaceResolver(get()) }
        single<JournalNotesRepository> {
            OfflineFirstJournalNotesRepository(
                textNoteDao = get(),
                imageNoteDao = get(),
                audioNoteDao = get(),
                videoNoteDao = get(),
                journalContentDao = get(),
                journalRepository = get(),
                mediaCaptionDao = get(),
                notePlaceResolver = get(),
                syncManagerProvider = { get() },
                syncMetadataService = get(),
                transcriptionRepository = get<TranscriptionRepository>(),
                mediaReferences = get(),
                mediaClaimFence = get(),
            )
        }
        single {
            StoredMediaReferenceMigration(
                imageNoteDao = get(),
                audioNoteDao = get(),
                videoNoteDao = get(),
                journalDao = get(),
                mediaReferences = get(),
                rescuer = getOrNull<MediaRescuer>() ?: MediaRescuer.None,
                transcriptions = getOrNull<TranscriptionRepository>(),
                transactions = get<SyncTransactionManager>(WatchCloud),
            )
        }
        single { StoredMediaReferenceMigrationLauncher { get() } }
    }

/**
 * No-op remote journal data source for Wear OS standalone mode.
 */
private object NoOpRemoteJournalDataSource : RemoteJournalDataSource {
    override suspend fun observeAllJournals(): List<Journal> = emptyList()

    override suspend fun addJournal(journal: Journal): String {
        Napier.d("Wear: Remote journal creation skipped (standalone mode)")
        return journal.id.toString()
    }

    override suspend fun editJournal(journal: Journal) {
        Napier.d("Wear: Remote journal edit skipped (standalone mode)")
    }

    override suspend fun deleteJournal(journalId: String) {
        Napier.d("Wear: Remote journal deletion skipped (standalone mode)")
    }
}
