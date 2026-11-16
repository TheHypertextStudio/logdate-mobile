package app.logdate.client.domain.di

import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.account.GetCurrentEntitlementUseCase
import app.logdate.client.domain.activity.ActivityPresenter
import app.logdate.client.domain.activity.ActivityUiState
import app.logdate.client.domain.activity.CaptureActivitySource
import app.logdate.client.domain.dayboundary.DatastoreDayBoundaryPreferences
import app.logdate.client.domain.dayboundary.DayBoundarySettingsRepository
import app.logdate.client.domain.dayboundary.DefaultDayBoundarySettingsRepository
import app.logdate.client.domain.di.healthDomainModule
import app.logdate.client.domain.di.locationDomainModule
import app.logdate.client.domain.editor.ObserveEditorDataUseCase
import app.logdate.client.domain.entities.ExtractPeopleUseCase
import app.logdate.client.domain.entities.GetPeopleUseCase
import app.logdate.client.domain.events.DeleteEventUseCase
import app.logdate.client.domain.events.GetAttachableNotesForEventUseCase
import app.logdate.client.domain.events.GetEventByIdUseCase
import app.logdate.client.domain.events.ImportDeviceCalendarEventsUseCase
import app.logdate.client.domain.events.InferEventsUseCase
import app.logdate.client.domain.events.LinkNoteToEventUseCase
import app.logdate.client.domain.events.ObserveEventsForMonthUseCase
import app.logdate.client.domain.events.ObserveImportedEventsUseCase
import app.logdate.client.domain.events.ObserveLinkedNotesForEventUseCase
import app.logdate.client.domain.events.ObserveUpcomingEventsUseCase
import app.logdate.client.domain.events.ObserveUserPlacesUseCase
import app.logdate.client.domain.events.UnlinkNoteFromEventUseCase
import app.logdate.client.domain.events.UpdateEventUseCase
import app.logdate.client.domain.export.ExportUserDataUseCase
import app.logdate.client.domain.export.GetExportCountsUseCase
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.domain.identity.ObserveUserIdentityUseCase
import app.logdate.client.domain.journals.DeleteJournalUseCase
import app.logdate.client.domain.journals.GetCurrentUserJournalsUseCase
import app.logdate.client.domain.journals.GetDefaultSelectedJournalsUseCase
import app.logdate.client.domain.journals.GetJournalByIdUseCase
import app.logdate.client.domain.journals.MergeJournalsUseCase
import app.logdate.client.domain.journals.SuggestJournalsUseCase
import app.logdate.client.domain.journals.UpdateJournalUseCase
import app.logdate.client.domain.location.ObserveLocationStopsUseCase
import app.logdate.client.domain.media.IndexMediaForPeriodUseCase
import app.logdate.client.domain.moments.EnrichMarkLocationUseCase
import app.logdate.client.domain.notes.AddNoteUseCase
import app.logdate.client.domain.notes.FetchEntryUseCase
import app.logdate.client.domain.notes.FetchNotesForDayUseCase
import app.logdate.client.domain.notes.FetchTodayNotesUseCase
import app.logdate.client.domain.notes.HasNotesForTodayUseCase
import app.logdate.client.domain.notes.RemoveNoteUseCase
import app.logdate.client.domain.notes.drafts.CleanupExpiredDraftsUseCase
import app.logdate.client.domain.notes.drafts.CreateEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.DeleteAllDraftsUseCase
import app.logdate.client.domain.notes.drafts.DeleteEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.FetchEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.FetchMostRecentDraftUseCase
import app.logdate.client.domain.notes.drafts.GetAllDraftsUseCase
import app.logdate.client.domain.notes.drafts.UpdateEntryDraftUseCase
import app.logdate.client.domain.onboarding.ProcessPersonalIntroductionUseCase
import app.logdate.client.domain.places.PlaceResolutionCache
import app.logdate.client.domain.places.ResolveLocationToPlaceUseCase
import app.logdate.client.domain.profile.UpdateProfileUseCase
import app.logdate.client.domain.recommendation.AmbientPromptHistoryRepository
import app.logdate.client.domain.recommendation.DefaultAmbientPromptHistoryRepository
import app.logdate.client.domain.recommendation.DefaultMemoriesSettingsRepository
import app.logdate.client.domain.recommendation.DefaultPlaceFamiliarityRepository
import app.logdate.client.domain.recommendation.GenerateAmbientPromptCandidatesUseCase
import app.logdate.client.domain.recommendation.GetHomeRecommendationUseCase
import app.logdate.client.domain.recommendation.GetMemoryRecallUseCase
import app.logdate.client.domain.recommendation.MemoriesSettingsRepository
import app.logdate.client.domain.recommendation.PlaceFamiliarityRepository
import app.logdate.client.domain.reminders.LegacyReminderMigration
import app.logdate.client.domain.reminders.ReminderPreferences
import app.logdate.client.domain.restore.PreviewArchiveUseCase
import app.logdate.client.domain.restore.RestoreUserDataUseCase
import app.logdate.client.domain.rewind.DeleteRewindUseCase
import app.logdate.client.domain.rewind.EntitlementRewindAIAvailability
import app.logdate.client.domain.rewind.GenerateAnnualRewindUseCase
import app.logdate.client.domain.rewind.GenerateBasicRewindUseCase
import app.logdate.client.domain.rewind.GenerateRewindTitleUseCase
import app.logdate.client.domain.rewind.GetCurrentWeekRewindUseCase
import app.logdate.client.domain.rewind.GetPastRewindsUseCase
import app.logdate.client.domain.rewind.GetRewindUseCase
import app.logdate.client.domain.rewind.GetWeekRewindUseCase
import app.logdate.client.domain.rewind.MarkRewindViewedUseCase
import app.logdate.client.domain.rewind.ObserveReflectionPromptResponsesUseCase
import app.logdate.client.domain.rewind.PreferencesCurationConfigProvider
import app.logdate.client.domain.rewind.RefreshCurrentWeekRewindUseCase
import app.logdate.client.domain.rewind.RegenerateRewindUseCase
import app.logdate.client.domain.rewind.SaveReflectionPromptResponseUseCase
import app.logdate.client.domain.search.ObserveRecentSearchesUseCase
import app.logdate.client.domain.search.SearchEntriesUseCase
import app.logdate.client.domain.search.SearchInJournalUseCase
import app.logdate.client.domain.search.SearchJournalsUseCase
import app.logdate.client.domain.search.UniversalSearchUseCase
import app.logdate.client.domain.streak.CalculateStreakUseCase
import app.logdate.client.domain.streak.ObserveCampfireUseCase
import app.logdate.client.domain.streak.ObserveStreakUseCase
import app.logdate.client.domain.streak.RefreshStreakUseCase
import app.logdate.client.domain.streak.SetStreakEnabledUseCase
import app.logdate.client.domain.timeline.GetJournalMembershipUseCase
import app.logdate.client.domain.timeline.GetMediaUrisUseCase
import app.logdate.client.domain.timeline.GetStreamingTimelineUseCase
import app.logdate.client.domain.timeline.GetTimelineDayUseCase
import app.logdate.client.domain.timeline.GetTimelinePageUseCase
import app.logdate.client.domain.timeline.GroupNotesByDayBoundsUseCase
import app.logdate.client.domain.timeline.InferMomentsUseCase
import app.logdate.client.domain.timeline.SummarizeJournalEntriesUseCase
import app.logdate.client.domain.watch.DefaultWatchPreferencesRepository
import app.logdate.client.domain.watch.StampedPreferenceStore
import app.logdate.client.domain.watch.WatchPreferencesRepository
import app.logdate.client.intelligence.availability.RewindAIAvailability
import app.logdate.client.intelligence.curation.CurationConfigProvider
import app.logdate.client.intelligence.events.EventNamingExtractor
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.user.UserStateRepository
import app.logdate.client.sync.peer.state.PeerRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Main domain module with use cases that don't create circular dependencies.
 * Location, Account, and Quota use cases are in separate modules to avoid circular dependencies.
 */
val domainModule: Module =
    module {
        factory { MergeJournalsUseCase(get(), get(), get()) }
        // Domain module dependencies

        // Entities
        factory { ExtractPeopleUseCase(get()) }
        factory { GetPeopleUseCase(get()) }

        // App info

        // Export
        factory { ExportUserDataUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { ExportArchiveUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { GetExportCountsUseCase(get(), get()) }
        factory { RestoreUserDataUseCase(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { PreviewArchiveUseCase() }

        // Notes
        factory {
            AddNoteUseCase(
                repository = get(),
                journalContentRepository = get(),
                logLocationUseCase = get(),
                logCurrentLocationUseCase = get(),
                settingsRepository = get(),
                mediaManager = get(),
                captureActivity = getOrNull<CaptureActivitySource>() ?: CaptureActivitySource.None,
            )
        }
        factory { FetchEntryUseCase(get()) }
        factory { FetchTodayNotesUseCase(get()) }
        factory { FetchNotesForDayUseCase(get()) }
        factory { HasNotesForTodayUseCase(get()) }
        factory { RemoveNoteUseCase(get()) }

        // Drafts
        factory { CleanupExpiredDraftsUseCase(get()) }
        factory { CreateEntryDraftUseCase(get()) }
        factory { DeleteAllDraftsUseCase(get()) }
        factory {
            val journals = get<JournalRepository>()
            DeleteEntryDraftUseCase(
                get(),
                get(),
                get(),
                journalCoverUris = { journals.allJournalsObserved.first().map { it.coverImageUri } },
                mediaReferences = get<StoredMediaReferences>(),
            )
        }
        factory { FetchEntryDraftUseCase(get()) }
        factory { FetchMostRecentDraftUseCase(get()) }
        factory { GetAllDraftsUseCase(get()) }
        factory { UpdateEntryDraftUseCase(get()) }

        // Editor
        factory { ObserveEditorDataUseCase(get(), get(), get(), get()) }
        factory {
            app.logdate.client.domain.moments.markedEntrySaveUseCase(
                get(),
                get(),
                get<app.logdate.client.domain.location.history.LocationHistoryService>()::linkMemory,
                get(),
            )
        }

        // Rewind
        factory { GetPastRewindsUseCase(get()) }
        factory { GetRewindUseCase(get(), get(), get()) }
        factory {
            val query: GetRewindUseCase = get()
            val repository: app.logdate.client.repository.rewind.RewindRepository = get()
            val prefs: LogdatePreferencesDataSource = get()
            GetCurrentWeekRewindUseCase(query::invoke, repository::getRewindsInRange, prefs.observeFirstDayOfWeek())
        }
        factory {
            val generate: GenerateBasicRewindUseCase = get()
            val repository: app.logdate.client.repository.rewind.RewindRepository = get()
            val prefs: LogdatePreferencesDataSource = get()
            RefreshCurrentWeekRewindUseCase(
                { start, end, existing -> generate(start, end, replaceExisting = existing) },
                repository::getRewindsInRange,
                prefs.observeFirstDayOfWeek(),
            )
        }
        factory {
            val prefs: LogdatePreferencesDataSource = get()
            GetWeekRewindUseCase(get(), prefs.observeFirstDayOfWeek())
        }
        factory { GenerateRewindTitleUseCase() }
        factory { SaveReflectionPromptResponseUseCase(get()) }
        factory { ObserveReflectionPromptResponsesUseCase(get()) }
        factory { DeleteRewindUseCase(get()) }
        factory { MarkRewindViewedUseCase(get()) }
        factory { GenerateAnnualRewindUseCase(get(), get(), get(), get()) }
        factory<RewindAIAvailability> {
            val getEntitlement: GetCurrentEntitlementUseCase = get()
            EntitlementRewindAIAvailability(getEntitlement::invoke)
        }
        factory<CurationConfigProvider> { PreferencesCurationConfigProvider(get()) }

        // Media indexing
        factory { IndexMediaForPeriodUseCase(get(), get()) }

        // Create the GenerateBasicRewindUseCase with all its dependencies
        factory { GenerateBasicRewindUseCase(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        factory { RegenerateRewindUseCase(get()) }

        // Timeline
        factory { GetJournalMembershipUseCase(get()) }
        factory { GetMediaUrisUseCase(get()) }
        // Adapts the persisted datastore preferences to the day-bounds engine's preference
        // contract, so the user's configured day-start hour actually reaches timeline grouping.
        single<app.logdate.client.health.util.LogdatePreferencesDataSource> {
            DatastoreDayBoundaryPreferences(get())
        }
        factory { GroupNotesByDayBoundsUseCase(get(), get(), get()) }
        factory {
            GetStreamingTimelineUseCase(
                get(),
                get<GetTimelineDayUseCase>(),
                get(),
                get(),
                get<app.logdate.client.domain.moments.ObserveTimelineMarksUseCase>()(),
            )
        }
        factory { GetTimelinePageUseCase(get(), get(), get(), get<GetTimelineDayUseCase>()) }
        factory { InferMomentsUseCase(get(), get()) }
        factory { GetTimelineDayUseCase(get(), get(), get(), get()) }
        factory { SummarizeJournalEntriesUseCase(get()) }

        // Include health domain module
        includes(healthDomainModule)

        // Include location domain module
        includes(locationDomainModule)

        // Onboarding
        factory { ProcessPersonalIntroductionUseCase(get(), get(), get()) }

        // Journals
        factory { GetCurrentUserJournalsUseCase(get()) }
        factory { GetDefaultSelectedJournalsUseCase(get(), get()) }
        factory { SuggestJournalsUseCase(get(), get()) }
        factory { GetJournalByIdUseCase(get()) }
        factory { UpdateJournalUseCase(get()) }
        factory { DeleteJournalUseCase(get()) }

        // Events
        factory { ObserveEventsForMonthUseCase(get()) }
        factory { GetEventByIdUseCase(get()) }
        factory { UpdateEventUseCase(get()) }
        factory { DeleteEventUseCase(get()) }
        factory { LinkNoteToEventUseCase(get()) }
        factory { UnlinkNoteFromEventUseCase(get()) }
        factory { ObserveLinkedNotesForEventUseCase(get(), get()) }
        factory { GetAttachableNotesForEventUseCase(get(), get()) }
        factory { ObserveUserPlacesUseCase(get()) }
        factory { ObserveUpcomingEventsUseCase(get()) }
        factory {
            val observeStops: ObserveLocationStopsUseCase = get()
            val placeResolutionCache: PlaceResolutionCache = get()
            val namingExtractor: EventNamingExtractor = get()
            InferEventsUseCase(
                observeLocationStops = observeStops::since,
                indexedMediaRepository = get(),
                notesRepository = get(),
                placeFamiliarity = get(),
                resolvePlaceForLocation = placeResolutionCache::resolve,
                eventRepository = get(),
                suggestEventName = { cluster -> namingExtractor.suggestName(cluster) },
            )
        }
        factory { ImportDeviceCalendarEventsUseCase(get(), get()) }
        factory { ObserveImportedEventsUseCase(get()) }

        // Places
        factory { ResolveLocationToPlaceUseCase(get(), get(), get()) }
        single { PlaceResolutionCache(get()) }

        // Profile
        factory { UpdateProfileUseCase(get()) }

        // Identity
        factory { ObserveUserIdentityUseCase(get(), get(), get(), get()) }

        // Search
        factory { SearchEntriesUseCase(get()) }
        factory { SearchInJournalUseCase(get()) }
        factory { SearchJournalsUseCase(get()) }
        factory { UniversalSearchUseCase(get()) }
        factory { ObserveRecentSearchesUseCase(get()) }

        // Recommendations
        factory { GetMemoryRecallUseCase(get(), getOrNull()) }
        factory {
            val activity = getOrNull<ActivityPresenter>()
            GetHomeRecommendationUseCase(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                activity = activity?.state ?: flowOf(ActivityUiState()),
                dismissActivitySuggestion = { activity?.dismiss() },
                useActivitySuggestion = { activity?.actedOn() },
            )
        }
        factory { GenerateAmbientPromptCandidatesUseCase(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single<AmbientPromptHistoryRepository> { DefaultAmbientPromptHistoryRepository(get()) }
        single<PlaceFamiliarityRepository> { DefaultPlaceFamiliarityRepository(get()) }
        single<MemoriesSettingsRepository> { DefaultMemoriesSettingsRepository(get()) }

        // Streaks
        factory { ObserveCampfireUseCase(get(), get(), get()) }
        factory { CalculateStreakUseCase(get()) }
        factory { ObserveStreakUseCase(get()) }
        single { RefreshStreakUseCase(get(), get()) }
        factory { SetStreakEnabledUseCase(get()) }

        // Day boundaries
        single<DayBoundarySettingsRepository> { DefaultDayBoundarySettingsRepository(get()) }

        // Preferences shared with a paired watch. The watch app binds its own store with its role.
        single { StampedPreferenceStore(storage = get(), localRole = PeerRole.PHONE) }
        single(createdAtStart = true) {
            LegacyReminderMigration(get(), get(), get<UserStateRepository>().userData, get())
        }
        single<WatchPreferencesRepository> {
            val migration = get<LegacyReminderMigration>()
            DefaultWatchPreferencesRepository(get()) { migration.ensure() }
        }
        factory { ReminderPreferences(get(), get()) }
        factory {
            app.logdate.client.domain.moments
                .ObserveTimelineMarksUseCase(get(), get(), get())
        }
        factory {
            app.logdate.client.domain.moments
                .ObserveMarkPhotoSuggestionsUseCase(get())
        }
        factory {
            app.logdate.client.domain.moments
                .AttachPhotoToMarkUseCase(get(), get(), get(), get())
        }
        factory {
            app.logdate.client.domain.moments
                .SaveMarkCaptionUseCase(get(), get(), get())
        }
        factory { EnrichMarkLocationUseCase(marks = get(), locationHistory = get(), watchPreferences = get()) }
    }
