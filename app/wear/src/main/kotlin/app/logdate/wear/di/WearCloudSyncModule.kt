package app.logdate.wear.di

import android.content.Context
import app.logdate.client.data.journals.OfflineFirstJournalContentRepository
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.datastore.PrefixedKeyValueStorage
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.crypto.ContentEncryptionService
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.media.AndroidMediaManager
import app.logdate.client.media.MediaManager
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.DefaultDataUsagePolicy
import app.logdate.client.networking.httpClient
import app.logdate.client.networking.saver.NetworkSaverModeProvider
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.sensor.battery.AndroidBatteryInfoProvider
import app.logdate.client.sensor.battery.BatteryInfoProvider
import app.logdate.client.sensor.network.AndroidNetworkSaverModeProvider
import app.logdate.client.sync.DefaultSyncManager
import app.logdate.client.sync.RecentCaptureFilter
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.SyncProfile
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.DefaultCloudAssociationDataSource
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.cloud.DefaultCloudMomentDataSource
import app.logdate.client.sync.cloud.DeviceSessionRevocationListener
import app.logdate.client.sync.cloud.LogDateCloudApiClient
import app.logdate.client.sync.cloud.account.ServerAccountStorageMode
import app.logdate.client.sync.cloud.account.ServerAccountStore
import app.logdate.client.sync.conflict.KeyValueSyncConflictStore
import app.logdate.client.sync.crypto.MarkedMomentPayloadCodec
import app.logdate.client.sync.crypto.MediaPayloadCrypto
import app.logdate.client.sync.crypto.StoredMediaPayloadCrypto
import app.logdate.client.sync.crypto.SyncPayloadCipher
import app.logdate.client.sync.di.SyncQualifiers
import app.logdate.client.sync.metadata.CloudEligibility
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.KeyValueFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.KeyValueIdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.KeyValueLastSyncErrorStore
import app.logdate.client.sync.metadata.KeyValueMediaSyncRefStore
import app.logdate.client.sync.metadata.KeyValueSyncDeadLetterStore
import app.logdate.client.sync.metadata.KeyValueSyncRetryScheduleStore
import app.logdate.client.sync.metadata.KeyValueUnreadableCloudRecordStore
import app.logdate.client.sync.metadata.RoutedSyncMetadataService
import app.logdate.client.sync.moments.MomentSyncConfig
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.DurableCloudApiClient
import app.logdate.client.sync.space.RoomSpaceSwapStore
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceMediaDataSource
import app.logdate.client.sync.space.SpaceRecordRegistry
import app.logdate.client.sync.space.SpaceRoute
import app.logdate.client.sync.space.SpaceRoutingMediaDataSource
import app.logdate.client.sync.space.SpaceSyncDependencies
import app.logdate.client.sync.space.SpaceTransportGate
import app.logdate.client.sync.space.XrpcSpaceDataHomes
import app.logdate.client.sync.space.spaceLocation
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.ServerProtocolFeature
import app.logdate.wear.sync.v2.cloud.WatchCloudAccountRepository
import app.logdate.wear.sync.v2.delivery.ReleasedToCloud
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** The qualifier of every binding that belongs to the watch's own cloud engine. */
val WatchCloud = named("watchCloud")

/** Notes the server sends a linked watch are stored only from this far back; the phone's library window adds the rest. */
private val RECENT_CAPTURES = 14.days

/**
 * Koin module for the watch's own cloud engine: the shared engine with the linked-watch profile, its own
 * stores under the `watch_cloud.` prefix, and only the outbox entries its router releases.
 */
val wearCloudSyncModule =
    module {
        single { ContentEncryptionService(get(), get(), get()) }
        single<NetworkSaverModeProvider> { AndroidNetworkSaverModeProvider(get<Context>()) }
        single<DataUsagePolicy> { DefaultDataUsagePolicy(get()) }
        single<BatteryInfoProvider> { AndroidBatteryInfoProvider(get<Context>()) }
        single<MediaManager> { AndroidMediaManager(get<Context>().contentResolver, get<Context>()) }
        single<MediaPayloadCrypto> { StoredMediaPayloadCrypto(get()) }
        single<KeyValueStorage>(WatchCloud) { PrefixedKeyValueStorage(get(), prefix = "watch_cloud.") }
        single<SyncTransactionManager>(WatchCloud) { RoomSyncTransactionManager(get<LogDateDatabase>()) }
        single<CloudApiClient>(WatchCloud) {
            LogDateCloudApiClient(configRepository = get(), httpClient = httpClient, sessionStorage = get())
        }
        single(WatchCloud) {
            val sessions = get<SessionStorage>()
            DownloadInbox(
                get<LogDateDatabase>().downloadInboxDao(),
                get(WatchCloud),
                {
                    val bound = sessions.getOriginBoundSession()
                    DownloadScope(bound?.session?.accountId.orEmpty(), bound?.origin.orEmpty())
                },
                { Clock.System.now().toEpochMilliseconds() },
            )
        }
        single(WatchCloud) { DurableCloudApiClient(get<CloudApiClient>(WatchCloud), get<DownloadInbox>(WatchCloud)) }
        single(WatchCloud) { SyncPayloadCipher(get(), get(), get()) }
        single(WatchCloud) {
            val flags = get<FeatureFlagStore>()
            val config = get<LogDateConfigRepository>()
            MomentSyncConfig(
                DefaultCloudMomentDataSource(get<DurableCloudApiClient>(WatchCloud), MarkedMomentPayloadCodec(get(WatchCloud))),
                get(),
            ) {
                flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) &&
                    config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.MOMENTS_V1) == true
            }
        }
        single { ServerAccountStore(get()) }
        single { ServerAccountStorageMode(get(), get()) }
        single<CloudAccountRepository>(WatchCloud) {
            WatchCloudAccountRepository(api = get(WatchCloud), config = get(), sessions = get(), accounts = get())
        }
        single { ReleasedToCloud(ledger = get(), peers = get(), outbox = get()) }
        single<CloudEligibility>(WatchCloud) { get<ReleasedToCloud>() }
        single<JournalContentRepository>(WatchCloud) {
            OfflineFirstJournalContentRepository(get(), get(), get(), syncMetadataService = get())
        }
        single(WatchCloud) {
            val flags = get<FeatureFlagStore>()
            val config = get<LogDateConfigRepository>()
            val accounts = get<CloudAccountRepository>(WatchCloud)
            val identity = get<IdentityKeyManager>()
            SpaceSyncDependencies(
                registry = SpaceRecordRegistry(getAll<SpaceKind<*>>()),
                gate =
                    SpaceTransportGate(
                        enabledInThisBuild = { flags.isEnabled(FeatureFlag.PERSONAL_SPACE) },
                        serverOffersPersonalSpace = {
                            config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.PERSONAL_SPACE_V1) == true
                        },
                        storageMode = get<ServerAccountStorageMode>()::currentMode,
                    ),
                homes = XrpcSpaceDataHomes(httpClient),
                swapStore = RoomSpaceSwapStore(get<LogDateDatabase>().spaceRecordStateDao()),
                accountDid = { accounts.getCurrentAccount()?.did },
                identityKey = identity::getAccountIdentityKey,
            )
        }
        single(WatchCloud) { watchCloudEngine() }
    }

private fun Scope.watchCloudEngine(): DefaultSyncManager {
    val storage = get<KeyValueStorage>(WatchCloud)
    val durable = get<DurableCloudApiClient>(WatchCloud)
    val cipher = get<SyncPayloadCipher>(WatchCloud)
    val config = get<LogDateConfigRepository>()
    val sessions = get<SessionStorage>()
    val space = get<SpaceSyncDependencies>(WatchCloud)
    return DefaultSyncManager(
        cloudContentDataSource = DefaultCloudContentDataSource(durable, cipher),
        cloudJournalDataSource = DefaultCloudJournalDataSource(durable, cipher),
        cloudAssociationDataSource = DefaultCloudAssociationDataSource(durable),
        cloudMediaDataSource =
            SpaceRoutingMediaDataSource(
                legacy = DefaultCloudMediaDataSource(get(WatchCloud), get()),
                space = SpaceMediaDataSource(space.homes, get(), spaceLocation(sessions, space.accountDid)),
                uploadsToSpace = { space.registry.forEntityType(EntityType.NOTE) != null && space.gate.route() == SpaceRoute.Space },
            ),
        cloudDraftDataSource = DefaultCloudDraftDataSource(durable, cipher),
        cloudAccountRepository = get(WatchCloud),
        sessionStorage = sessions,
        mediaManager = get(),
        mediaSyncRefStore =
            KeyValueMediaSyncRefStore(
                storage,
                currentOrigin = { config.getCurrentBackendUrl() },
                currentOwnerId = { sessions.getSession()?.accountId.orEmpty() },
            ),
        journalRepository = get(),
        journalNotesRepository = get(),
        journalContentRepository = get(WatchCloud),
        journalConflictResolver = get(named(SyncQualifiers.JOURNAL_CONFLICT_RESOLVER)),
        noteConflictResolver = get(named(SyncQualifiers.NOTE_CONFLICT_RESOLVER)),
        conflictStore = KeyValueSyncConflictStore(storage),
        deadLetterStore = KeyValueSyncDeadLetterStore(storage),
        retryScheduleStore = KeyValueSyncRetryScheduleStore(storage),
        syncMetadataService = RoutedSyncMetadataService(get(), get(WatchCloud)),
        transactionManager = get(WatchCloud),
        dataUsagePolicy = get(),
        deviceIdProvider = get(),
        identityKeyManager = get(),
        mediaPayloadKeyProvider = get(),
        lastErrorStore = KeyValueLastSyncErrorStore(storage),
        firstSyncEnqueueStore = KeyValueFirstSyncEnqueueStore(storage),
        cloudApiClient = get(WatchCloud),
        serverAccounts = get(),
        identityRecoveryNeededStore = KeyValueIdentityRecoveryNeededStore(storage),
        unreadableCloudRecordStore = KeyValueUnreadableCloudRecordStore(storage),
        downloadInbox = get(WatchCloud),
        momentSyncConfig = get(WatchCloud),
        deviceSessionRevocationListener = DeviceSessionRevocationListener { get<app.logdate.wear.sync.v2.removal.WatchDataWiper>().wipe() },
        profile = SyncProfile.LinkedWatch(RecentCaptureFilter(RECENT_CAPTURES)),
        spaceSync = space,
    )
}
