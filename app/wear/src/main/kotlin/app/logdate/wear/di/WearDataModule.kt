package app.logdate.wear.di

import app.logdate.client.data.journals.LocalFirstDraftRepository
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.data.journals.RemoteJournalDataSource
import app.logdate.client.data.location.OfflineFirstLocationHistoryRepository
import app.logdate.client.data.notes.EmptyNotePlaceResolver
import app.logdate.client.data.notes.NotePlaceResolver
import app.logdate.client.data.notes.OfflineFirstJournalNotesRepository
import app.logdate.client.data.notes.StoredMediaReferenceMigration
import app.logdate.client.data.notes.StoredMediaReferenceMigrationLauncher
import app.logdate.client.data.rewind.OfflineFirstRewindRepository
import app.logdate.client.database.databaseModule
import app.logdate.client.device.di.deviceInstanceModule
import app.logdate.client.di.datastoreModule
import app.logdate.client.location.di.locationModule
import app.logdate.client.media.storage.MediaRescuer
import app.logdate.client.permissions.di.permissionsModule
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.HealthSnapshotDataMapper
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.di.conflictResolverModule
import app.logdate.client.sync.metadata.KeyValueSyncDeadLetterStore
import app.logdate.client.sync.metadata.KeyValueSyncRetryScheduleStore
import app.logdate.client.sync.metadata.SyncDeadLetterStore
import app.logdate.client.sync.metadata.SyncRetryScheduleStore
import app.logdate.shared.config.configModule
import app.logdate.shared.model.Journal
import app.logdate.wear.health.HealthServicesWearHealthSensorManager
import app.logdate.wear.health.NoteHealthAnnotator
import app.logdate.wear.health.UnavailableWearHealthSensorManager
import app.logdate.wear.health.WearHealthSensorManager
import app.logdate.wear.location.WearLocationCaptureCoordinator
import app.logdate.wear.sync.GoogleWearDataLayerClient
import app.logdate.wear.sync.NoteDataMapper
import app.logdate.wear.sync.WearAudioDeliveryStore
import app.logdate.wear.sync.WearDataLayerClient
import app.logdate.wear.sync.WearDataLayerSyncManager
import app.logdate.wear.sync.WearNoteAckHandler
import app.logdate.wear.sync.WearNoteRemovalNotifier
import app.logdate.wear.sync.WearPhoneMessageHandler
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
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
        single<SyncDeadLetterStore> { KeyValueSyncDeadLetterStore(get()) }
        single<SyncRetryScheduleStore> { KeyValueSyncRetryScheduleStore(get()) }
        single { WearAudioDeliveryStore(get()) }
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
                deliveryStore = get(),
            )
        } binds arrayOf(SyncManager::class, WearNoteAckHandler::class, WearNoteRemovalNotifier::class)
        single { WearPhoneMessageHandler(ackHandler = get(), syncManager = get()) }

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
        single { WearLocationCaptureCoordinator(get(), get()) }

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
        single<NotePlaceResolver> { EmptyNotePlaceResolver }
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
                mediaReferences = get(),
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
