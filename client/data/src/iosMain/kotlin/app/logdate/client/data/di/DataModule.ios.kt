package app.logdate.client.data.di

import app.logdate.client.data.account.DefaultAccountIdentityRepository
import app.logdate.client.data.account.DefaultPasskeyAccountRepository
import app.logdate.client.data.account.DefaultServerScopedAccounts
import app.logdate.client.data.account.HistoryOwnerAdoption
import app.logdate.client.data.account.PasskeyBackedAccountRepository
import app.logdate.client.data.account.ServerScopedAccounts
import app.logdate.client.data.account.historyOwnerAdoptionModule
import app.logdate.client.data.audio.OfflineFirstAudioTagRepository
import app.logdate.client.data.events.OfflineFirstEventRepository
import app.logdate.client.data.journals.JournalUserDataRepository
import app.logdate.client.data.journals.LocalFirstDraftRepository
import app.logdate.client.data.journals.NoOpJournalDataSource
import app.logdate.client.data.journals.OfflineFirstJournalContentRepository
import app.logdate.client.data.journals.OfflineFirstJournalRepository
import app.logdate.client.data.journals.OfflineFirstJournalUserDataRepository
import app.logdate.client.data.journals.RemoteJournalDataSource
import app.logdate.client.data.location.OfflineFirstActivityHistoryRepository
import app.logdate.client.data.location.OfflineFirstLocationHistoryRepository
import app.logdate.client.data.location.RoomHistoryRecordStore
import app.logdate.client.data.maintenance.DataIntegrityService
import app.logdate.client.data.media.OfflineIndexedMediaRepository
import app.logdate.client.data.notes.DatabaseNotePlaceResolver
import app.logdate.client.data.notes.NotePlaceResolver
import app.logdate.client.data.notes.OfflineFirstJournalNotesRepository
import app.logdate.client.data.notes.StoredMediaReferenceMigration
import app.logdate.client.data.notes.StoredMediaReferenceMigrationLauncher
import app.logdate.client.data.notes.drafts.IosLocalEntryDraftStore
import app.logdate.client.data.notes.drafts.LocalEntryDraftStore
import app.logdate.client.data.notes.drafts.OfflineFirstEntryDraftRepository
import app.logdate.client.data.people.IosDeviceContactsReader
import app.logdate.client.data.people.OfflineFirstPeopleGraphRepository
import app.logdate.client.data.people.OfflineFirstPeopleRepository
import app.logdate.client.data.places.OfflineFirstUserPlacesRepository
import app.logdate.client.data.profile.OfflineFirstProfileRepository
import app.logdate.client.data.quota.IosRemoteQuotaDataSource
import app.logdate.client.data.rewind.DefaultRewindGenerationManager
import app.logdate.client.data.rewind.OfflineFirstReflectionPromptResponseRepository
import app.logdate.client.data.rewind.OfflineFirstRewindRepository
import app.logdate.client.data.search.DataStoreRecentSearchesRepository
import app.logdate.client.data.search.OfflineFirstSearchRepository
import app.logdate.client.data.streak.DefaultStreakSettingsRepository
import app.logdate.client.data.timeline.OfflineFirstActivityTimelineRepository
import app.logdate.client.data.transcription.OfflineFirstTranscriptionRepository
import app.logdate.client.data.user.IosUserDeviceRepository
import app.logdate.client.data.user.OfflineFirstUserStateRepository
import app.logdate.client.database.databaseModule
import app.logdate.client.device.di.deviceInstanceModule
import app.logdate.client.device.identity.userVisibleDeviceName
import app.logdate.client.di.datastoreModule
import app.logdate.client.media.storage.MediaRescuer
import app.logdate.client.networking.IdentityApiClient
import app.logdate.client.networking.IdentityApiClientContract
import app.logdate.client.networking.PasskeyApiClient
import app.logdate.client.networking.PasskeyApiClientContract
import app.logdate.client.networking.QuotaApiClient
import app.logdate.client.networking.QuotaApiClientContract
import app.logdate.client.permissions.di.permissionsModule
import app.logdate.client.repository.account.AccountIdentityRepository
import app.logdate.client.repository.account.AccountRepository
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.client.repository.audio.AudioTagRepository
import app.logdate.client.repository.events.EventRepository
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.knowledge.DeviceContactsReader
import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.client.repository.knowledge.PeopleContactsRepository
import app.logdate.client.repository.knowledge.PeopleProfileRepository
import app.logdate.client.repository.knowledge.PeopleRepository
import app.logdate.client.repository.knowledge.PersonLinkRepository
import app.logdate.client.repository.location.ActivityHistoryRepository
import app.logdate.client.repository.location.HistoryRecordStore
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.client.repository.places.UserPlacesRepository
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.client.repository.quota.RemoteQuotaDataSource
import app.logdate.client.repository.rewind.ReflectionPromptResponseRepository
import app.logdate.client.repository.rewind.RewindGenerationManager
import app.logdate.client.repository.rewind.RewindRepository
import app.logdate.client.repository.search.RecentSearchesRepository
import app.logdate.client.repository.search.SearchRepository
import app.logdate.client.repository.streak.StreakSettingsRepository
import app.logdate.client.repository.timeline.ActivityTimelineRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.repository.user.devices.UserDeviceRepository
import app.logdate.client.sync.conflict.SyncConflictStore
import app.logdate.client.sync.conflict.retainedMediaReferences
import app.logdate.shared.config.configModule
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module

actual val dataModule: Module =
    module {
        includes(deviceInstanceModule)
        includes(datastoreModule)
        includes(databaseModule)
        includes(historyOwnerAdoptionModule)
        includes(configModule)
        includes(permissionsModule)

        // JSON serialization
        single {
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
                encodeDefaults = true
            }
        }

        // Journals — RemoteJournalDataSource is a vestigial pre-AT-Protocol abstraction.
        // OfflineFirstJournalRepository sources real sync through SyncManager + the
        // record-level /api/v1/sync/journals endpoints; the no-op source here is intentional and the
        // field is unused at runtime.
        factory<RemoteJournalDataSource> { NoOpJournalDataSource }
        single<JournalUserDataRepository> { OfflineFirstJournalUserDataRepository(get()) }
        single<DraftRepository> {
            LocalFirstDraftRepository(
                get(),
                get(),
                get<app.logdate.client.datastore.SessionStorage>(),
                get<app.logdate.shared.config.LogDateConfigRepository>(),
            )
        }
        single<JournalRepository> {
            OfflineFirstJournalRepository(
                get(),
                get(),
                get(),
                syncManagerProvider = { get() },
                syncMetadataService = get(),
                database = get(),
                mergeTransactionManager = get(),
                mergeScopeChanges =
                    combine(
                        get<app.logdate.shared.config.LogDateConfigRepository>().backendUrl,
                        get<app.logdate.client.datastore.SessionStorage>().getSessionFlow(),
                    ) { _, _ -> Unit },
                currentScope = {
                    app.logdate.client.repository.journals.JournalMergeScope(
                        get<app.logdate.client.device.identity.CanonicalOwnerProvider>().getCanonicalOwnerId(),
                        get<app.logdate.shared.config.LogDateConfigRepository>().getCurrentBackendUrl().trimEnd('/'),
                    )
                },
            )
        }

        // Notes
        single<JournalNotesRepository> {
            OfflineFirstJournalNotesRepository(
                get(), // textNoteDao
                get(), // imageNoteDao
                get(), // voiceNoteDao
                get(), // videoNoteDao
                get(), // journalContentDao
                get(), // journalRepository
                get(), // mediaCaptionDao
                get(), // notePlaceResolver
                get(), // indexedMediaRepository
                get(), // mediaManager
                get(), // transactionManager
                syncManagerProvider = { get() },
                syncMetadataService = get(),
                transcriptionRepository = get(),
                mediaReferences = get(),
                retainedMedia = { getOrNull<SyncConflictStore>()?.retainedMediaReferences().orEmpty() },
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
        single<NotePlaceResolver> { DatabaseNotePlaceResolver(get()) }
        single<JournalContentRepository> {
            OfflineFirstJournalContentRepository(
                get(),
                get(),
                get(),
                syncMetadataService = get(),
                transactionManager = get(),
            )
        }

        single<EntryDraftRepository> { OfflineFirstEntryDraftRepository(get(), get(), get()) }
        factory<LocalEntryDraftStore> { IosLocalEntryDraftStore() }

        // Rewind
        single<RewindRepository> { OfflineFirstRewindRepository(get()) }
        single<RewindGenerationManager> { DefaultRewindGenerationManager(get()) }
        single<ReflectionPromptResponseRepository> { OfflineFirstReflectionPromptResponseRepository(get()) }

        // Media
        single<IndexedMediaRepository> { OfflineIndexedMediaRepository(get(), get()) }

        // Timeline
        single<ActivityTimelineRepository> { OfflineFirstActivityTimelineRepository() }

        // Location
        single<LocationHistoryRepository> { OfflineFirstLocationHistoryRepository(get(), get()) }
        single<HistoryRecordStore> { RoomHistoryRecordStore(get()) }
        single<ActivityHistoryRepository> { OfflineFirstActivityHistoryRepository(get()) }

        // Places
        single<UserPlacesRepository> { OfflineFirstUserPlacesRepository(get()) }

        // Events
        single<EventRepository> { OfflineFirstEventRepository(get(), get()) }

        // People
        single { OfflineFirstPeopleRepository(get()) }
        single {
            OfflineFirstPeopleGraphRepository(
                personDao = get(),
                inferredPersonClusterDao = get(),
                inferredPersonEvidenceDao = get(),
                personLinkDao = get(),
                personResolutionDecisionDao = get(),
                textNoteDao = get(),
                transcriptionDao = get(),
                eventDao = get(),
                journalNotesRepository = get(),
                eventRepository = get(),
            )
        }
        single<PeopleRepository> { get<OfflineFirstPeopleRepository>() }
        single<PeopleContactsRepository> { get<OfflineFirstPeopleRepository>() }
        single<InferredPeopleRepository> { get<OfflineFirstPeopleGraphRepository>() }
        single<PersonLinkRepository> { get<OfflineFirstPeopleGraphRepository>() }
        single<PeopleProfileRepository> { get<OfflineFirstPeopleGraphRepository>() }
        single<DeviceContactsReader> { IosDeviceContactsReader() }

        // Profile
        single<ProfileRepository> { OfflineFirstProfileRepository(get()) }

        // Networking clients
        single<PasskeyApiClientContract> { PasskeyApiClient(get(), get()) }
        single<IdentityApiClientContract> { IdentityApiClient(get(), get()) }
        single<QuotaApiClientContract> { QuotaApiClient(get(), get()) }

        // Account
        single<AccountRepository> { PasskeyBackedAccountRepository(passkeyRepository = get()) }
        single<ServerScopedAccounts> {
            DefaultServerScopedAccounts(
                httpClient = get(),
                vault = get(),
                passkeyManager = get(),
                platformAccountManager = get(),
                canonicalOwnerProvider = get(),
                // Only a moving account is opened this way, and it always has this device's entries.
                hasLocalData = { true },
                adoptCanonicalOwner = { owner, origin -> get<HistoryOwnerAdoption>().adoptOnOrigin(owner, origin) },
                deviceName = { userVisibleDeviceName() },
            )
        }
        single<AccountIdentityRepository> {
            DefaultAccountIdentityRepository(
                apiClient = get(),
                sessionStorage = get(),
                plcRecoveryKeyManager = get(),
            )
        }
        single<PasskeyAccountRepository> {
            DefaultPasskeyAccountRepository(
                apiClient = get(),
                passkeyManager = get(),
                restoreCredentialManager = get(),
                sessionStorage = get(),
                platformAccountManager = get(),
                configRepository = get(),
                canonicalOwnerProvider = get(),
                adoptCanonicalOwner = { get<HistoryOwnerAdoption>().adopt(it) },
                hasLocalData = {
                    val journals = get<JournalRepository>()
                    val notes = get<JournalNotesRepository>()
                    journals.allJournalsObserved.first().isNotEmpty() ||
                        notes.allNotesObserved.first().isNotEmpty() ||
                        journals.getAllDrafts().isNotEmpty() ||
                        notes.getAllJournalNoteLinks().isNotEmpty() ||
                        get<HistoryOwnerAdoption>().hasLocalHistory()
                },
                deviceName = { userVisibleDeviceName() },
            )
        }

        // User
        single<UserDeviceRepository> { IosUserDeviceRepository() }
        single<UserStateRepository> { OfflineFirstUserStateRepository(get()) }

        // Ambient sound tags
        single<AudioTagRepository> { OfflineFirstAudioTagRepository(audioTagDao = get()) }

        // Quota
        single<RemoteQuotaDataSource> {
            IosRemoteQuotaDataSource(
                apiClient = get(),
                sessionStorage = get(),
            )
        }

        // Transcription
        single<TranscriptionRepository> {
            OfflineFirstTranscriptionRepository(
                get(), // transcriptionDao
                get(), // voiceNoteDao
                get(), // transcriptionManager
                syncMetadataService = get(),
                transactionManager = get(),
                syncManagerProvider = { get() },
            )
        }

        // Search
        single<SearchRepository> { OfflineFirstSearchRepository(get(), get()) }
        single<RecentSearchesRepository> { DataStoreRecentSearchesRepository(get()) }

        // Streaks
        single<StreakSettingsRepository> { DefaultStreakSettingsRepository(get()) }

        // Integrity
        single { DataIntegrityService(get(), get(), get(), get(), get(), get()) }
    }
