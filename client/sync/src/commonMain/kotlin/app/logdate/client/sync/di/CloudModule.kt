package app.logdate.client.sync.di

import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.dao.UserPlaceDao
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.networking.LocationHistoryApiClient
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableJournalContentRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalRepository
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.HistoryOwnerAdoptionRepair
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.moments.SyncableMarkedMomentRepository
import app.logdate.client.sync.AutomaticMediaRecovery
import app.logdate.client.sync.CloudRecoveryPhraseAccountProof
import app.logdate.client.sync.RecoveryPhraseAccountProof
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.CloudAssociationDataSource
import app.logdate.client.sync.cloud.CloudBackupDataSource
import app.logdate.client.sync.cloud.CloudContentDataSource
import app.logdate.client.sync.cloud.CloudDraftDataSource
import app.logdate.client.sync.cloud.CloudJournalDataSource
import app.logdate.client.sync.cloud.CloudMediaDataSource
import app.logdate.client.sync.cloud.CloudMomentDataSource
import app.logdate.client.sync.cloud.DefaultCloudAssociationDataSource
import app.logdate.client.sync.cloud.DefaultCloudBackupDataSource
import app.logdate.client.sync.cloud.DefaultCloudContentDataSource
import app.logdate.client.sync.cloud.DefaultCloudDraftDataSource
import app.logdate.client.sync.cloud.DefaultCloudJournalDataSource
import app.logdate.client.sync.cloud.DefaultCloudMediaDataSource
import app.logdate.client.sync.cloud.DefaultCloudMomentDataSource
import app.logdate.client.sync.cloud.LogDateCloudApiClient
import app.logdate.client.sync.cloud.account.ServerAccountStorageMode
import app.logdate.client.sync.cloud.account.ServerAccountStore
import app.logdate.client.sync.crypto.MarkedMomentPayloadCodec
import app.logdate.client.sync.crypto.MediaPayloadCrypto
import app.logdate.client.sync.crypto.MediaPayloadKeyProvider
import app.logdate.client.sync.crypto.StoredMediaPayloadCrypto
import app.logdate.client.sync.crypto.SyncPayloadCipher
import app.logdate.client.sync.location.CanonicalHistoryScopeProvider
import app.logdate.client.sync.location.LocationHistoryStager
import app.logdate.client.sync.location.LocationHistorySyncEngine
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.moments.MomentSyncConfig
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.recovery.DurableCloudApiClient
import app.logdate.client.sync.space.RoomSpaceSwapStore
import app.logdate.client.sync.space.SpaceDataHomes
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceMediaDataSource
import app.logdate.client.sync.space.SpaceRecordRegistry
import app.logdate.client.sync.space.SpaceRoute
import app.logdate.client.sync.space.SpaceRoutingMediaDataSource
import app.logdate.client.sync.space.SpaceSwapStore
import app.logdate.client.sync.space.SpaceSyncDependencies
import app.logdate.client.sync.space.SpaceTransportGate
import app.logdate.client.sync.space.XrpcSpaceDataHomes
import app.logdate.client.sync.space.mappers.HistoryScopeProvider
import app.logdate.client.sync.space.mappers.draftSpaceKind
import app.logdate.client.sync.space.mappers.journalSpaceKind
import app.logdate.client.sync.space.mappers.locationSamplesSpaceKind
import app.logdate.client.sync.space.mappers.markedMomentSpaceKind
import app.logdate.client.sync.space.mappers.membershipSpaceKind
import app.logdate.client.sync.space.mappers.noteSpaceKind
import app.logdate.client.sync.space.mappers.placeCorrectionSpaceKind
import app.logdate.client.sync.space.mappers.placeEntryLinkSpaceKind
import app.logdate.client.sync.space.mappers.placeJourneySpaceKind
import app.logdate.client.sync.space.mappers.placeSpaceKind
import app.logdate.client.sync.space.mappers.placeVisitSpaceKind
import app.logdate.client.sync.space.migration.HttpSpaceMigrationApi
import app.logdate.client.sync.space.migration.KeyValueSpaceMigrationCheckpoints
import app.logdate.client.sync.space.migration.SpaceMigrationApi
import app.logdate.client.sync.space.migration.SpaceMigrationCheckpoints
import app.logdate.client.sync.space.migration.SpaceMigrationDependencies
import app.logdate.client.sync.space.spaceLocation
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.CloudAccountRepository
import app.logdate.shared.model.ServerProtocolFeature
import kotlinx.coroutines.flow.first
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.time.Clock

/** This device's id as location history names it. */
private fun Scope.historyDeviceId(): () -> String? {
    val device = get<DeviceIdProvider>()
    return { device.getDeviceId().value.toString() }
}

/**
 * The personal-space transport: which kinds use it, whether it is open, and how to reach the space.
 * Each kind's [SpaceKind] is contributed by the module that owns its mapper, under its own name so
 * kinds don't replace one another; with none registered, every kind keeps syncing through its own
 * endpoints.
 */
val spaceSyncModule =
    module {
        single<SpaceKind<*>>(named("journalSpaceKind")) {
            journalSpaceKind(
                journals = get<JournalRepository>() as SyncableJournalRepository,
                resolver = get(named(SyncQualifiers.JOURNAL_CONFLICT_RESOLVER)),
            )
        }
        single<SpaceKind<*>>(named("membershipSpaceKind")) {
            membershipSpaceKind(get<JournalContentRepository>() as SyncableJournalContentRepository)
        }
        single<SpaceKind<*>>(named("draftSpaceKind")) {
            draftSpaceKind(get<JournalRepository>())
        }
        single<SpaceKind<*>>(named("markedMomentSpaceKind")) {
            markedMomentSpaceKind(get<SyncableMarkedMomentRepository>())
        }
        single {
            val sessions = get<SessionStorage>()
            val config = get<LogDateConfigRepository>()
            CanonicalHistoryScopeProvider(sessions, config, get<CanonicalOwnerProvider>()) as HistoryScopeProvider
        }
        single<SpaceKind<*>>(named("placeSpaceKind")) {
            placeSpaceKind(get(), get(), get<UserPlaceDao>(), deviceId = historyDeviceId())
        }
        single<SpaceKind<*>>(named("placeVisitSpaceKind")) {
            placeVisitSpaceKind(get(), get(), deviceId = historyDeviceId())
        }
        single<SpaceKind<*>>(named("placeCorrectionSpaceKind")) {
            placeCorrectionSpaceKind(get(), get(), deviceId = historyDeviceId())
        }
        single<SpaceKind<*>>(named("placeEntryLinkSpaceKind")) {
            placeEntryLinkSpaceKind(get(), get(), deviceId = historyDeviceId())
        }
        single<SpaceKind<*>>(named("placeJourneySpaceKind")) {
            placeJourneySpaceKind(get(), get(), deviceId = historyDeviceId())
        }
        single<SpaceKind<*>>(named("locationSamplesSpaceKind")) {
            val sessions = get<SessionStorage>()
            locationSamplesSpaceKind(get(), get(), ownerId = { sessions.getSession()?.accountId }, deviceId = historyDeviceId())
        }
        single<SpaceKind<*>>(named("noteSpaceKind")) {
            noteSpaceKind(
                notes = get<JournalNotesRepository>() as SyncableJournalNotesRepository,
                resolver = get(named(SyncQualifiers.NOTE_CONFLICT_RESOLVER)),
            )
        }
        single { SpaceRecordRegistry(getAll<SpaceKind<*>>()) }
        single<SpaceDataHomes> { XrpcSpaceDataHomes(get<io.ktor.client.HttpClient>()) }
        single<SpaceSwapStore> { RoomSpaceSwapStore(get<LogDateDatabase>().spaceRecordStateDao()) }
        single {
            val flags = get<FeatureFlagStore>()
            val config = get<LogDateConfigRepository>()
            SpaceTransportGate(
                enabledInThisBuild = { flags.isEnabled(FeatureFlag.PERSONAL_SPACE) },
                serverOffersPersonalSpace = {
                    config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.PERSONAL_SPACE_V1) == true
                },
                storageMode = get<ServerAccountStorageMode>()::currentMode,
            )
        }
        single<SpaceMigrationApi> {
            val config = get<LogDateConfigRepository>()
            HttpSpaceMigrationApi(get<io.ktor.client.HttpClient>()) { config.apiBaseUrl.first() }
        }
        single<SpaceMigrationCheckpoints> { KeyValueSpaceMigrationCheckpoints(get()) }
        single {
            val flags = get<FeatureFlagStore>()
            val config = get<LogDateConfigRepository>()
            val moments = get<LogDateDatabase>().markedMomentDao()
            SpaceMigrationDependencies(
                api = get(),
                checkpoints = get(),
                enabledInThisBuild = {
                    flags.isEnabled(FeatureFlag.PERSONAL_SPACE_MIGRATION) && flags.isEnabled(FeatureFlag.PERSONAL_SPACE)
                },
                serverOffersMove = {
                    config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.PERSONAL_SPACE_MIGRATION_V1) == true
                },
                spaceMedia = get<SpaceMediaDataSource>(),
                liveMomentIds = { moments.liveIds().map { it.toString() } },
            )
        }
        single {
            val accounts = get<CloudAccountRepository>()
            val identity = get<IdentityKeyManager>()
            SpaceSyncDependencies(
                registry = get(),
                gate = get(),
                homes = get(),
                swapStore = get(),
                accountDid = { accounts.getCurrentAccount()?.did },
                identityKey = identity::getAccountIdentityKey,
            )
        }
    }

/**
 * Koin module for Cloud API client dependencies.
 *
 * This module provides the CloudApiClient implementation
 * for communicating with the LogDate Cloud API.
 */
val cloudModule =
    module {
        single<RecoveryPhraseAccountProof> {
            val accounts = get<CloudAccountRepository>()
            CloudRecoveryPhraseAccountProof(
                get(),
                get(),
                get(),
                spaceRoute = get<SpaceTransportGate>()::route,
                spaceDataHomes = get(),
                accountDid = { accountId ->
                    accounts.getCurrentAccount()?.takeIf { it.id.toString() == accountId }?.did
                },
            )
        }
        // API Client
        single<CloudApiClient> {
            val recorder = get<app.logdate.client.sync.diagnostics.SyncDiagnosticRecorder>()
            val sources = get<app.logdate.client.sync.diagnostics.DiagnosticSourceProvider>()
            LogDateCloudApiClient(
                configRepository = get(),
                httpClient = get(),
                sessionStorage = get<SessionStorage>(),
                diagnosticSource = { sources.current() },
                scopedDiagnostics = { event, source -> recorder.record(event, source?.scope, source?.epoch) },
            )
        }

        single {
            val sessions = get<SessionStorage>()
            val recorder = get<app.logdate.client.sync.diagnostics.SyncDiagnosticRecorder>()
            val sources = get<app.logdate.client.sync.diagnostics.DiagnosticSourceProvider>()
            DownloadInbox(
                get<LogDateDatabase>().downloadInboxDao(),
                get(),
                {
                    val bound = sessions.getOriginBoundSession()
                    DownloadScope(bound?.session?.accountId.orEmpty(), bound?.origin.orEmpty())
                },
                { Clock.System.now().toEpochMilliseconds() },
                diagnosticSource = sources::current,
                diagnostics = { event, source -> recorder.record(event, source?.scope, source?.epoch) },
            )
        }
        single { DurableCloudApiClient(get<CloudApiClient>(), get()) }
        single { ServerAccountStore(get()) }
        single { ServerAccountStorageMode(get(), get()) }

        // Raw API remains available for identity emptiness checks; incremental inbox reads cannot prove emptiness.
        // Cloud Data Sources
        single { SyncPayloadCipher(get(), get(), get()) }
        single {
            val locations = get<LocationHistoryRepository>()
            val activities = get<ActivityHistoryRepository>()
            val device = get<DeviceIdProvider>()
            LocationHistoryStager(
                locations::getLocationHistoryPage,
                activities::getActivityHistoryPage,
                get(),
                get(),
                { device.getDeviceId().value.toString() },
            )
        }
        single {
            val httpClient = get<io.ktor.client.HttpClient>()
            val flags = get<FeatureFlagStore>()
            val stager = get<LocationHistoryStager>()
            val adoption = get<HistoryOwnerAdoptionRepair>()
            val places = get<UserPlaceDao>()
            LocationHistorySyncEngine(
                apiFactory = { config -> LocationHistoryApiClient(httpClient, config) },
                store = get(),
                cipher = get(),
                sessions = get(),
                config = get(),
                enabled = { flags.isEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY) },
                prepare = { owner, origin ->
                    adoption.reconcile(owner, origin)
                    stager.stage(owner, origin)
                },
                savedPlaceIds = { places.getAllPlaces().map { it.id } },
                canonicalOwner = get(),
            )
        }
        single { MarkedMomentPayloadCodec(get()) }
        single<CloudMomentDataSource> { DefaultCloudMomentDataSource(get<DurableCloudApiClient>(), get()) }
        single {
            val flags = get<FeatureFlagStore>()
            val config = get<LogDateConfigRepository>()
            MomentSyncConfig(get(), get()) {
                flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) &&
                    config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.MOMENTS_V1) == true
            }
        }
        single<CloudContentDataSource> { DefaultCloudContentDataSource(get<DurableCloudApiClient>(), get()) }
        single<CloudJournalDataSource> { DefaultCloudJournalDataSource(get<DurableCloudApiClient>(), get()) }
        single<CloudAssociationDataSource> { DefaultCloudAssociationDataSource(get<DurableCloudApiClient>()) }
        single<CloudBackupDataSource> { DefaultCloudBackupDataSource(get()) }
        single<CloudDraftDataSource> {
            val config = get<LogDateConfigRepository>()
            DefaultCloudDraftDataSource(get<DurableCloudApiClient>(), get()) {
                config.getCurrentServerDescriptor()?.hasProtocolFeature(ServerProtocolFeature.RICH_DRAFTS_V1) == true
            }
        }
        single { MediaPayloadKeyProvider(get(), get(), get(), get()) }
        single<MediaPayloadCrypto> { StoredMediaPayloadCrypto(get()) }
        includes(spaceSyncModule)
        single { SpaceMediaDataSource(get(), get(), spaceLocation(get(), get<SpaceSyncDependencies>().accountDid), intents = get()) }
        single {
            val sessions = get<SessionStorage>()
            app.logdate.client.sync.metadata.MediaUploadIntentStore(get()) {
                val bound = sessions.getOriginBoundSession()
                app.logdate.client.sync.metadata
                    .UploadScope(bound?.session?.accountId.orEmpty(), bound?.origin.orEmpty())
            }
        }
        single<CloudMediaDataSource> {
            val registry = get<SpaceRecordRegistry>()
            val gate = get<SpaceTransportGate>()
            SpaceRoutingMediaDataSource(
                legacy = DefaultCloudMediaDataSource(get(), get(), get()),
                space = get<SpaceMediaDataSource>(),
                uploadsToSpace = { registry.forEntityType(EntityType.NOTE) != null && gate.route() == SpaceRoute.Space },
            )
        }
        single {
            AutomaticMediaRecovery(
                get(),
                get(),
                get(),
                get(),
                get(),
                dataUsagePolicy = get(),
                references = getOrNull<StoredMediaReferences>() ?: StoredMediaReferences.Unchanged,
                publicationCustody =
                    getOrNull<app.logdate.client.media.ManagedMediaPublicationCustody>()
                        ?: app.logdate.client.media.ManagedMediaPublicationCustody.None,
                owner = get(),
                config = get(),
            )
        }
    }
