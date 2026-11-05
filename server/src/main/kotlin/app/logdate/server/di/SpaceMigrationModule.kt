package app.logdate.server.di

import app.logdate.server.ServerDescriptorConfig
import app.logdate.server.database.PostgreSQLLegacyJournalEraser
import app.logdate.server.database.PostgreSQLSpaceMigrationStore
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryJournalMergeStore
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateCollectionsMetadataStore
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import app.logdate.server.logdate.JournalMergeStore
import app.logdate.server.logdate.LogDateAtprotoBlobRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateCollectionsMetadataStore
import app.logdate.server.logdate.LogDateMediaRepository
import app.logdate.server.migration.InMemoryLegacyJournalEraser
import app.logdate.server.migration.InMemorySpaceMigrationStore
import app.logdate.server.migration.LegacyCopyCleanup
import app.logdate.server.migration.LegacyInventoryReader
import app.logdate.server.migration.LegacyJournalEraser
import app.logdate.server.migration.LegacyMediaCopier
import app.logdate.server.migration.SpaceMigrationConfig
import app.logdate.server.migration.SpaceMigrationHolds
import app.logdate.server.migration.SpaceMigrationService
import app.logdate.server.migration.SpaceMigrationStore
import app.logdate.server.sync.InMemoryLocationHistoryRepository
import app.logdate.server.sync.LocationHistoryRepository
import org.koin.core.module.Module
import studio.hypertext.atproto.repo.RepoBlockStore

/**
 * Moving existing journals into personal spaces. The store and holds are always there, so an account
 * that already moved keeps answering as moved even if the migration is turned off later; the service
 * that moves accounts exists only while [ServerDescriptorConfig.movesJournalsToPersonalSpaces].
 */
internal fun Module.spaceMigrationDefinitions(isDatabaseAvailable: Boolean) {
    single { SpaceMigrationConfig.fromEnvironment() }
    single<SpaceMigrationStore> { if (isDatabaseAvailable) PostgreSQLSpaceMigrationStore() else InMemorySpaceMigrationStore(get()) }
    single { SpaceMigrationHolds(get(), get()) }
    single {
        LegacyInventoryReader(
            get<LogDateCollectionsMetadataStore>(),
            get<LocationHistoryRepository>(),
            get<CompositeLogDateMediaBlobRepository>(),
        )
    }
    single { SpaceMigrationService(get(), get(), get(), get(), get()) }
    single { LegacyMediaCopier(get<CompositeLogDateMediaBlobRepository>(), get(), get(), get()) }
    single<LegacyJournalEraser> {
        if (isDatabaseAvailable) {
            PostgreSQLLegacyJournalEraser(get<LogDateBlobStorage>())
        } else {
            InMemoryLegacyJournalEraser(
                accounts = get(),
                migrations = get(),
                repositories = get<RepoBlockStore>(),
                metadata = get<LogDateCollectionsMetadataStore>() as InMemoryLogDateCollectionsMetadataStore,
                locationHistory = get<LocationHistoryRepository>() as InMemoryLocationHistoryRepository,
                media = get<LogDateMediaRepository>() as InMemoryLogDateMediaRepository,
                atprotoBlobs = get<LogDateAtprotoBlobRepository>() as InMemoryLogDateAtprotoBlobRepository,
                merges = get<JournalMergeStore>() as InMemoryJournalMergeStore,
                storage = get<LogDateBlobStorage>(),
            )
        }
    }
    single { LegacyCopyCleanup(get(), get(), get(), get()) }
}
