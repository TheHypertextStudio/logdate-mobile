package app.logdate.client.sync.di

import app.logdate.client.database.LogDateDatabase
import app.logdate.client.device.di.deviceInstanceModule
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.sync.AndroidSyncManager
import app.logdate.client.sync.DefaultSyncManager
import app.logdate.client.sync.RecoverIdentityUseCase
import app.logdate.client.sync.RoomSyncTransactionManager
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncReadinessGate
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.SyncUpgradeResumption
import app.logdate.client.sync.cloud.di.cloudAccountModule
import app.logdate.client.sync.conflict.KeyValueSyncConflictStore
import app.logdate.client.sync.conflict.SyncConflictStore
import app.logdate.client.sync.metadata.KeyValueFirstSyncEnqueueStore
import app.logdate.client.sync.metadata.KeyValueIdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.KeyValueLastSyncErrorStore
import app.logdate.client.sync.metadata.KeyValueMediaSyncRefStore
import app.logdate.client.sync.metadata.KeyValueSyncDeadLetterStore
import app.logdate.client.sync.metadata.KeyValueSyncRetryScheduleStore
import app.logdate.client.sync.metadata.KeyValueUnreadableCloudRecordStore
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.client.sync.migration.di.migrationCoreModule
import app.logdate.client.sync.migration.di.migrationModule
import app.logdate.shared.config.LogDateConfigRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * A module for all sync-related dependencies.
 */
actual val syncModule: Module =
    module {
        includes(diagnosticsModule)
        single { RecoverIdentityUseCase(get(), get(), get(), get(), get(), get()) }
        single { SyncReadinessGate() }
        single<SyncConflictStore> { KeyValueSyncConflictStore(get()) }
        single<MediaSyncRefStore> {
            val configRepository = get<LogDateConfigRepository>()
            KeyValueMediaSyncRefStore(get(), currentOrigin = {
                configRepository.getCurrentBackendUrl()
            }, currentOwnerId = { get<app.logdate.client.datastore.SessionStorage>().getSession()?.accountId.orEmpty() })
        }
        single<SyncDeadLetterStore> { KeyValueSyncDeadLetterStore(get()) }
        single<SyncRetryScheduleStore> { KeyValueSyncRetryScheduleStore(get()) }
        single { SyncUpgradeResumption(get()) }
        single<SyncTransactionManager> {
            val database = get<LogDateDatabase>()
            RoomSyncTransactionManager(database)
        }
        single<DefaultSyncManager> {
            DefaultSyncManager(
                readinessGate = get(),
                cloudContentDataSource = get(),
                cloudJournalDataSource = get(),
                cloudAssociationDataSource = get(),
                cloudMediaDataSource = get(),
                cloudDraftDataSource = get(),
                cloudAccountRepository = get(),
                sessionStorage = get(),
                mediaManager = get(),
                mediaSyncRefStore = get(),
                mediaReferences = get(),
                journalRepository = get(),
                journalNotesRepository = get(),
                journalContentRepository = get(),
                journalConflictResolver = get(named(SyncQualifiers.JOURNAL_CONFLICT_RESOLVER)),
                noteConflictResolver = get(named(SyncQualifiers.NOTE_CONFLICT_RESOLVER)),
                conflictStore = get(),
                deadLetterStore = get(),
                retryScheduleStore = get(),
                lastErrorStore = KeyValueLastSyncErrorStore(get()),
                firstSyncEnqueueStore = KeyValueFirstSyncEnqueueStore(get()),
                syncMetadataService = get(),
                transactionManager = get(),
                dataUsagePolicy = get(),
                deviceIdProvider = get(),
                identityKeyManager = get(),
                mediaPayloadKeyProvider = get(),
                cloudQuotaManager = get(),
                cloudApiClient = get(),
                downloadInbox = get(),
                diagnostics = get(),
                diagnosticSource = get<app.logdate.client.sync.diagnostics.DiagnosticSourceProvider>()::current,
                supportsRichDrafts = {
                    get<LogDateConfigRepository>().getCurrentServerDescriptor()?.hasProtocolFeature(
                        app.logdate.shared.model.ServerProtocolFeature.RICH_DRAFTS_V1,
                    ) ==
                        true
                },
                identityRecoveryNeededStore = KeyValueIdentityRecoveryNeededStore(get()),
                unreadableCloudRecordStore = KeyValueUnreadableCloudRecordStore(get()),
                locationHistorySyncEngine = get(),
            )
        }
        single<SyncManager> {
            AndroidSyncManager(
                androidContext(),
                get<DefaultSyncManager>(),
                get(),
                get(),
                get<NetworkAvailabilityMonitor>(),
                get(),
            )
        }
        includes(
            quotaModule,
            cloudAccountModule,
            cloudModule,
            deviceInstanceModule,
            conflictResolverModule,
            migrationCoreModule,
            migrationModule,
            app.logdate.client.media.di.mediaModule,
        )
    }
