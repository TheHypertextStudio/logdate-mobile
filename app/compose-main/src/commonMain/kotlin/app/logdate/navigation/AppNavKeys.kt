@file:OptIn(ExperimentalSerializationApi::class)

package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.account.navigation.CloudAccountSetupRoute
import app.logdate.feature.core.main.HomeRoute
import app.logdate.feature.core.navigation.BaseRoute
import app.logdate.feature.core.profile.navigation.ProfileRoute
import app.logdate.feature.core.settings.navigation.AccountSettingsRoute
import app.logdate.feature.core.settings.navigation.AdvancedSettingsRoute
import app.logdate.feature.core.settings.navigation.BirthdaySettingsRoute
import app.logdate.feature.core.settings.navigation.CalendarSyncActivityRoute
import app.logdate.feature.core.settings.navigation.CalendarSyncCalendarsRoute
import app.logdate.feature.core.settings.navigation.CalendarSyncSettingsRoute
import app.logdate.feature.core.settings.navigation.ClearDataSettingsRoute
import app.logdate.feature.core.settings.navigation.DataSettingsRoute
import app.logdate.feature.core.settings.navigation.DayBoundarySettingsRoute
import app.logdate.feature.core.settings.navigation.DeleteAccountRoute
import app.logdate.feature.core.settings.navigation.DeveloperToolsRoute
import app.logdate.feature.core.settings.navigation.DevicesRoute
import app.logdate.feature.core.settings.navigation.EventsCalendarRoute
import app.logdate.feature.core.settings.navigation.EventsSettingsRoute
import app.logdate.feature.core.settings.navigation.ExportSettingsRoute
import app.logdate.feature.core.settings.navigation.HostingRoute
import app.logdate.feature.core.settings.navigation.LabsSettingsRoute
import app.logdate.feature.core.settings.navigation.LibrarySettingsRoute
import app.logdate.feature.core.settings.navigation.LocationAdvancedRoute
import app.logdate.feature.core.settings.navigation.LocationIntervalRoute
import app.logdate.feature.core.settings.navigation.LocationSettingsRoute
import app.logdate.feature.core.settings.navigation.LocationTrackingOptionsRoute
import app.logdate.feature.core.settings.navigation.MemoriesSettingsRoute
import app.logdate.feature.core.settings.navigation.MoveServerRoute
import app.logdate.feature.core.settings.navigation.PeopleDirectoryRoute
import app.logdate.feature.core.settings.navigation.PeopleInboxRoute
import app.logdate.feature.core.settings.navigation.PeopleSettingsRoute
import app.logdate.feature.core.settings.navigation.PersonDetailRoute
import app.logdate.feature.core.settings.navigation.PrivacySettingsRoute
import app.logdate.feature.core.settings.navigation.RecommendationSettingsRoute
import app.logdate.feature.core.settings.navigation.RecoveryPhraseEntrySettingsRoute
import app.logdate.feature.core.settings.navigation.RecoveryPhraseRoute
import app.logdate.feature.core.settings.navigation.ResetAppSettingsRoute
import app.logdate.feature.core.settings.navigation.ResetSettingsRoute
import app.logdate.feature.core.settings.navigation.RewindSettingsRoute
import app.logdate.feature.core.settings.navigation.SettingsRoute
import app.logdate.feature.core.settings.navigation.SignInMethodsRoute
import app.logdate.feature.core.settings.navigation.StreakSettingsRoute
import app.logdate.feature.core.settings.navigation.SyncSettingsRoute
import app.logdate.feature.core.settings.navigation.TimelineSettingsRoute
import app.logdate.feature.core.settings.navigation.VoiceNotesSettingsRoute
import app.logdate.feature.core.settings.navigation.WatchCheckConnectionRoute
import app.logdate.feature.core.settings.navigation.WatchMicrophoneRoute
import app.logdate.feature.core.settings.navigation.WatchNotificationSettingsRoute
import app.logdate.feature.core.settings.navigation.WatchSettingsRoute
import app.logdate.feature.core.settings.navigation.WatchTilesRoute
import app.logdate.feature.core.settings.navigation.WatchTroubleshootingRoute
import app.logdate.feature.core.sync.navigation.SyncIssuesRoute
import app.logdate.feature.core.watch.continuity.WatchHandoffRoute
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.events.navigation.EventDetailRoute
import app.logdate.feature.journals.navigation.JournalContentPickerRoute
import app.logdate.feature.journals.navigation.JournalCreationRoute
import app.logdate.feature.journals.navigation.JournalDetailsRoute
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.journals.navigation.JournalSettingsRoute
import app.logdate.feature.journals.navigation.JournalsOverviewRoute
import app.logdate.feature.journals.navigation.NoteDetailRoute
import app.logdate.feature.journals.navigation.ShareJournalRoute
import app.logdate.feature.library.navigation.LibraryOverviewRoute
import app.logdate.feature.library.navigation.MediaDetailRoute
import app.logdate.feature.onboarding.navigation.AccountCreation
import app.logdate.feature.onboarding.navigation.AppOverview
import app.logdate.feature.onboarding.navigation.BirthdayIntro
import app.logdate.feature.onboarding.navigation.FeatureDayBoundaries
import app.logdate.feature.onboarding.navigation.FeatureLocationTimeline
import app.logdate.feature.onboarding.navigation.FeatureNotifications
import app.logdate.feature.onboarding.navigation.FeatureRecommendations
import app.logdate.feature.onboarding.navigation.FeatureWatch
import app.logdate.feature.onboarding.navigation.FirstEntryImportOffer
import app.logdate.feature.onboarding.navigation.FirstEntryMemorySelection
import app.logdate.feature.onboarding.navigation.MemoryImport
import app.logdate.feature.onboarding.navigation.MemorySelection
import app.logdate.feature.onboarding.navigation.OnboardingComplete
import app.logdate.feature.onboarding.navigation.OnboardingRoute
import app.logdate.feature.onboarding.navigation.OnboardingStart
import app.logdate.feature.onboarding.navigation.PersonalIntro
import app.logdate.feature.onboarding.navigation.RecoveryPhrase
import app.logdate.feature.onboarding.navigation.SignIn
import app.logdate.feature.onboarding.navigation.WelcomeBack
import app.logdate.feature.postcards.navigation.PostcardEditorRoute
import app.logdate.feature.postcards.navigation.PostcardViewerRoute
import app.logdate.feature.postcards.navigation.PostcardsCollectionRoute
import app.logdate.feature.rewind.navigation.RewindDetailRoute
import app.logdate.feature.rewind.navigation.RewindOverviewRoute
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Central registry of every typed navigation destination in the app.
 *
 * Compose Multiplatform's Navigation 3 layer cannot use reflection-based `NavKey`
 * serialization on iOS / web — every concrete `NavKey` subtype must be registered with the
 * polymorphic serializer below. Used by `rememberNavBackStack(appNavSavedStateConfiguration,
 * startKey)` in the `NavDisplay`-based root composable.
 */
val appNavSavedStateConfiguration: SavedStateConfiguration =
    SavedStateConfiguration {
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    // Top-level
                    subclass(BaseRoute::class, BaseRoute.serializer())
                    subclass(HomeRoute::class, HomeRoute.serializer())
                    subclass(ProfileRoute::class, ProfileRoute.serializer())
                    subclass(SyncIssuesRoute::class, SyncIssuesRoute.serializer())

                    // Onboarding
                    subclass(OnboardingRoute::class, OnboardingRoute.serializer())
                    subclass(OnboardingStart::class, OnboardingStart.serializer())
                    subclass(PersonalIntro::class, PersonalIntro.serializer())
                    subclass(AppOverview::class, AppOverview.serializer())
                    subclass(MemoryImport::class, MemoryImport.serializer())
                    subclass(MemorySelection::class, MemorySelection.serializer())
                    subclass(AccountCreation::class, AccountCreation.serializer())
                    subclass(SignIn::class, SignIn.serializer())
                    subclass(RecoveryPhrase::class, RecoveryPhrase.serializer())
                    subclass(BirthdayIntro::class, BirthdayIntro.serializer())
                    subclass(FeatureRecommendations::class, FeatureRecommendations.serializer())
                    subclass(FeatureDayBoundaries::class, FeatureDayBoundaries.serializer())
                    subclass(FeatureLocationTimeline::class, FeatureLocationTimeline.serializer())
                    subclass(FeatureNotifications::class, FeatureNotifications.serializer())
                    subclass(FeatureWatch::class, FeatureWatch.serializer())
                    subclass(OnboardingComplete::class, OnboardingComplete.serializer())
                    subclass(FirstEntryImportOffer::class, FirstEntryImportOffer.serializer())
                    subclass(FirstEntryMemorySelection::class, FirstEntryMemorySelection.serializer())
                    subclass(WelcomeBack::class, WelcomeBack.serializer())

                    // Settings
                    subclass(SettingsRoute::class, SettingsRoute.serializer())
                    subclass(DevicesRoute::class, DevicesRoute.serializer())
                    subclass(AccountSettingsRoute::class, AccountSettingsRoute.serializer())
                    subclass(SignInMethodsRoute::class, SignInMethodsRoute.serializer())
                    subclass(RecoveryPhraseRoute::class, RecoveryPhraseRoute.serializer())
                    subclass(RecoveryPhraseEntrySettingsRoute::class, RecoveryPhraseEntrySettingsRoute.serializer())
                    subclass(HostingRoute::class, HostingRoute.serializer())
                    subclass(MoveServerRoute::class, MoveServerRoute.serializer())
                    subclass(DeleteAccountRoute::class, DeleteAccountRoute.serializer())
                    subclass(PrivacySettingsRoute::class, PrivacySettingsRoute.serializer())
                    subclass(DataSettingsRoute::class, DataSettingsRoute.serializer())
                    subclass(LocationSettingsRoute::class, LocationSettingsRoute.serializer())
                    subclass(LocationTrackingOptionsRoute::class, LocationTrackingOptionsRoute.serializer())
                    subclass(LocationIntervalRoute::class, LocationIntervalRoute.serializer())
                    subclass(LocationAdvancedRoute::class, LocationAdvancedRoute.serializer())
                    subclass(AdvancedSettingsRoute::class, AdvancedSettingsRoute.serializer())
                    subclass(DeveloperToolsRoute::class, DeveloperToolsRoute.serializer())
                    subclass(LabsSettingsRoute::class, LabsSettingsRoute.serializer())
                    subclass(MemoriesSettingsRoute::class, MemoriesSettingsRoute.serializer())
                    subclass(VoiceNotesSettingsRoute::class, VoiceNotesSettingsRoute.serializer())
                    subclass(StreakSettingsRoute::class, StreakSettingsRoute.serializer())
                    subclass(TimelineSettingsRoute::class, TimelineSettingsRoute.serializer())
                    subclass(SyncSettingsRoute::class, SyncSettingsRoute.serializer())
                    subclass(ExportSettingsRoute::class, ExportSettingsRoute.serializer())
                    subclass(LibrarySettingsRoute::class, LibrarySettingsRoute.serializer())
                    subclass(ResetSettingsRoute::class, ResetSettingsRoute.serializer())
                    subclass(BirthdaySettingsRoute::class, BirthdaySettingsRoute.serializer())
                    subclass(DayBoundarySettingsRoute::class, DayBoundarySettingsRoute.serializer())
                    subclass(RecommendationSettingsRoute::class, RecommendationSettingsRoute.serializer())
                    subclass(ClearDataSettingsRoute::class, ClearDataSettingsRoute.serializer())
                    subclass(ResetAppSettingsRoute::class, ResetAppSettingsRoute.serializer())
                    subclass(RewindSettingsRoute::class, RewindSettingsRoute.serializer())
                    subclass(EventsSettingsRoute::class, EventsSettingsRoute.serializer())
                    subclass(EventsCalendarRoute::class, EventsCalendarRoute.serializer())
                    subclass(CalendarSyncSettingsRoute::class, CalendarSyncSettingsRoute.serializer())
                    subclass(CalendarSyncCalendarsRoute::class, CalendarSyncCalendarsRoute.serializer())
                    subclass(CalendarSyncActivityRoute::class, CalendarSyncActivityRoute.serializer())
                    subclass(PeopleSettingsRoute::class, PeopleSettingsRoute.serializer())
                    subclass(PeopleDirectoryRoute::class, PeopleDirectoryRoute.serializer())
                    subclass(PeopleInboxRoute::class, PeopleInboxRoute.serializer())
                    subclass(PersonDetailRoute::class, PersonDetailRoute.serializer())
                    subclass(WatchSettingsRoute::class, WatchSettingsRoute.serializer())
                    subclass(WatchTilesRoute::class, WatchTilesRoute.serializer())
                    subclass(
                        app.logdate.feature.core.settings.navigation.WatchRemovalRoute::class,
                        app.logdate.feature.core.settings.navigation.WatchRemovalRoute
                            .serializer(),
                    )
                    subclass(WatchCheckConnectionRoute::class, WatchCheckConnectionRoute.serializer())
                    subclass(WatchMicrophoneRoute::class, WatchMicrophoneRoute.serializer())
                    subclass(WatchHandoffRoute::class, WatchHandoffRoute.serializer())
                    subclass(WatchNotificationSettingsRoute::class, WatchNotificationSettingsRoute.serializer())
                    subclass(WatchTroubleshootingRoute::class, WatchTroubleshootingRoute.serializer())

                    // Cloud account flow
                    subclass(CloudAccountSetupRoute::class, CloudAccountSetupRoute.serializer())
                    subclass(BlueskySignInRoute::class, BlueskySignInRoute.serializer())

                    // Editor
                    subclass(EntryEditorRoute::class, EntryEditorRoute.serializer())

                    // Journals
                    subclass(JournalsOverviewRoute::class, JournalsOverviewRoute.serializer())
                    subclass(JournalDetailsRoute::class, JournalDetailsRoute.serializer())
                    subclass(JournalMergeRoute::class, JournalMergeRoute.serializer())
                    subclass(JournalContentPickerRoute::class, JournalContentPickerRoute.serializer())
                    subclass(JournalSettingsRoute::class, JournalSettingsRoute.serializer())
                    subclass(JournalCreationRoute::class, JournalCreationRoute.serializer())
                    subclass(NoteDetailRoute::class, NoteDetailRoute.serializer())
                    subclass(ShareJournalRoute::class, ShareJournalRoute.serializer())

                    // Library + media
                    subclass(LibraryOverviewRoute::class, LibraryOverviewRoute.serializer())
                    subclass(MediaDetailRoute::class, MediaDetailRoute.serializer())

                    // Postcards
                    subclass(PostcardsCollectionRoute::class, PostcardsCollectionRoute.serializer())
                    subclass(PostcardEditorRoute::class, PostcardEditorRoute.serializer())
                    subclass(PostcardViewerRoute::class, PostcardViewerRoute.serializer())

                    // Rewind
                    subclass(RewindOverviewRoute::class, RewindOverviewRoute.serializer())
                    subclass(RewindDetailRoute::class, RewindDetailRoute.serializer())

                    // Events
                    subclass(EventDetailRoute::class, EventDetailRoute.serializer())

                    // Timeline
                    subclass(TimelineDetailRoute::class, TimelineDetailRoute.serializer())

                    // Compose-main local routes
                    subclass(
                        app.logdate.client.ui.navigation.SearchRoute::class,
                        app.logdate.client.ui.navigation.SearchRoute
                            .serializer(),
                    )
                    subclass(
                        app.logdate.client.ui.navigation.LocationTimelineRoute::class,
                        app.logdate.client.ui.navigation.LocationTimelineRoute
                            .serializer(),
                    )
                }
            }
    }
