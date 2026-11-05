package app.logdate.feature.core.settings.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.permissions.rememberContactsPermissionState
import app.logdate.feature.core.people.ui.PeopleDirectoryScreen
import app.logdate.feature.core.people.ui.PeopleInboxScreen
import app.logdate.feature.core.people.ui.PeopleSettingsScreen
import app.logdate.feature.core.people.ui.PersonDetailScreen
import app.logdate.feature.core.settings.account.AccountDestinations
import app.logdate.feature.core.settings.account.AccountScreen
import app.logdate.feature.core.settings.account.delete.DeleteAccountScreen
import app.logdate.feature.core.settings.account.hosting.HostingScreen
import app.logdate.feature.core.settings.account.move.MoveServerScreen
import app.logdate.feature.core.settings.account.recovery.RecoveryPhraseScreen
import app.logdate.feature.core.settings.account.signin.SignInMethodsScreen
import app.logdate.feature.core.settings.ui.AdvancedSettingsScreen
import app.logdate.feature.core.settings.ui.AudioModelSettingsScreen
import app.logdate.feature.core.settings.ui.AudioSettingsCapability
import app.logdate.feature.core.settings.ui.BirthdaySettingsScreen
import app.logdate.feature.core.settings.ui.ClearDataSettingsScreen
import app.logdate.feature.core.settings.ui.DataSettingsScreen
import app.logdate.feature.core.settings.ui.DayBoundarySettingsScreen
import app.logdate.feature.core.settings.ui.DeveloperToolsScreen
import app.logdate.feature.core.settings.ui.ExportSettingsScreen
import app.logdate.feature.core.settings.ui.LibrarySettingsScreen
import app.logdate.feature.core.settings.ui.LocationIntervalScreen
import app.logdate.feature.core.settings.ui.LocationSettingsScreen
import app.logdate.feature.core.settings.ui.LocationTrackingOptionsScreen
import app.logdate.feature.core.settings.ui.MemoriesSettingsScreen
import app.logdate.feature.core.settings.ui.PrivacySettingsScreen
import app.logdate.feature.core.settings.ui.RecommendationSettingsScreen
import app.logdate.feature.core.settings.ui.RecoveryPhraseEntrySettingsScreen
import app.logdate.feature.core.settings.ui.ResetAppSettingsScreen
import app.logdate.feature.core.settings.ui.ResetSettingsScreen
import app.logdate.feature.core.settings.ui.SettingsOverviewScreen
import app.logdate.feature.core.settings.ui.StreakSettingsScreen
import app.logdate.feature.core.settings.ui.SyncSettingsScreen
import app.logdate.feature.core.settings.ui.TimelineSettingsScreen
import app.logdate.feature.core.settings.ui.VoiceNotesSettingsScreen
import app.logdate.feature.core.settings.ui.devices.DevicesScreen
import app.logdate.feature.events.ui.calendar.EventsCalendarScreen
import app.logdate.feature.events.ui.calendarsync.CalendarSyncActivityScreen
import app.logdate.feature.events.ui.calendarsync.CalendarSyncCalendarsScreen
import app.logdate.feature.events.ui.calendarsync.CalendarSyncSettingsScreen
import app.logdate.feature.events.ui.settings.EventsSettingsScreen
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.rewind.ui.settings.RewindSettingsScreen
import app.logdate.ui.navigation.ViewModelsPerVisit
import app.logdate.ui.navigation.taggedEntry
import kotlin.uuid.Uuid

/** Navigates to the settings overview. */
fun NavBackStack<NavKey>.navigateToSettings() {
    add(SettingsRoute())
}

/**
 * Registers every entry under the Settings umbrella — overview, sub-pages, devices, location
 * sub-screens, people directory + inbox + person detail, events / calendar-sync / rewind
 * settings. Internal sub-navigation between settings screens is handled by this orchestrator;
 * callers only supply lambdas for transitions that leave the settings flow.
 *
 * @param onBack pop the current settings entry off the back stack
 * @param onResetApp invoked from `ResetAppSettings` when the user confirms a full reset
 * @param onNavigateToProfile launch the profile screen from the settings overview tile
 * @param onNavigateToCloudAccountCreation launch the cloud-account onboarding flow at step 1
 * @param onNavigateToSignIn launch the cloud-account onboarding flow on the sign-in step
 * @param onNavigateToEvent open an event's detail screen (used from the events calendar /
 *   calendar-sync activity log)
 * @param onNavigateToWatch supply a callback to expose the Watch tile in settings; pass `null`
 *   to hide it (iOS / desktop)
 * @param onNavigateToNotifications same shape as [onNavigateToWatch] but for the
 *   Notifications tile (currently unwired on every platform)
 */
fun EntryProviderScope<NavKey>.settingsEntries(
    onBack: () -> Unit,
    onResetApp: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToCloudAccountCreation: () -> Unit,
    onNavigateToSignIn: () -> Unit,
    onNavigateToEvent: (Uuid) -> Unit,
    onNavigateToWatch: (() -> Unit)? = null,
    onNavigateToNotifications: (() -> Unit)? = null,
    onNavigateTo: (NavKey) -> Unit,
) {
    taggedEntry<SettingsRoute> {
        SettingsOverviewScreen(
            onBack = onBack,
            onNavigateToProfile = onNavigateToProfile,
            onNavigateToAccount = { onNavigateTo(AccountSettingsRoute) },
            onNavigateToDevices = { onNavigateTo(DevicesRoute()) },
            onNavigateToWatch = onNavigateToWatch,
            onNavigateToReset = { onNavigateTo(ResetSettingsRoute) },
            onNavigateToLocation = { onNavigateTo(LocationSettingsRoute) },
            onNavigateToPrivacy = { onNavigateTo(PrivacySettingsRoute) },
            onNavigateToLibrarySettings = { onNavigateTo(LibrarySettingsRoute) },
            onNavigateToMemories = { onNavigateTo(MemoriesSettingsRoute) },
            onNavigateToVoiceNotes = { onNavigateTo(VoiceNotesSettingsRoute) },
            onNavigateToNotifications = onNavigateToNotifications,
            onNavigateToStreaks = { onNavigateTo(StreakSettingsRoute) },
            onNavigateToRewindSettings = { onNavigateTo(RewindSettingsRoute) },
            onNavigateToEventsSettings = { onNavigateTo(EventsSettingsRoute) },
            onNavigateToPeopleSettings = { onNavigateTo(PeopleSettingsRoute) },
            onNavigateToTimeline = { onNavigateTo(TimelineSettingsRoute) },
            onNavigateToSync = { onNavigateTo(SyncSettingsRoute) },
            onNavigateToExport = { onNavigateTo(ExportSettingsRoute) },
            onNavigateToAbout = { onNavigateTo(AdvancedSettingsRoute) },
            onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
            onNavigateToSignIn = onNavigateToSignIn,
        )
    }
    taggedEntry<AccountSettingsRoute>(metadata = ViewModelsPerVisit) {
        AccountScreen(
            destinations =
                AccountDestinations(
                    onBack = onBack,
                    onProfile = onNavigateToProfile,
                    onSignInMethods = { onNavigateTo(SignInMethodsRoute) },
                    onRecoveryPhrase = { onNavigateTo(RecoveryPhraseRoute) },
                    onHosting = { onNavigateTo(HostingRoute) },
                    onDeleteAccount = { onNavigateTo(DeleteAccountRoute) },
                    onSignIn = onNavigateToSignIn,
                    onCreateAccount = onNavigateToCloudAccountCreation,
                ),
        )
    }
    taggedEntry<SignInMethodsRoute>(metadata = ViewModelsPerVisit) {
        SignInMethodsScreen(onBack = onBack)
    }
    taggedEntry<RecoveryPhraseRoute>(metadata = ViewModelsPerVisit) {
        RecoveryPhraseScreen(
            onBack = onBack,
            onEnterPhrase = { onNavigateTo(RecoveryPhraseEntrySettingsRoute) },
        )
    }
    taggedEntry<HostingRoute>(metadata = ViewModelsPerVisit) {
        HostingScreen(onBack = onBack, onMoveAccount = { onNavigateTo(MoveServerRoute) })
    }
    taggedEntry<MoveServerRoute>(metadata = ViewModelsPerVisit) {
        MoveServerScreen(onClose = onBack)
    }
    taggedEntry<DeleteAccountRoute>(metadata = ViewModelsPerVisit) {
        DeleteAccountScreen(onBack = onBack, onDeviceErased = onResetApp)
    }
    taggedEntry<PrivacySettingsRoute> {
        PrivacySettingsScreen(
            onBack = onBack,
            onNavigateToLocationSettings = { onNavigateTo(LocationSettingsRoute) },
        )
    }
    taggedEntry<DataSettingsRoute> {
        DataSettingsScreen(
            onBack = onBack,
            onNavigateToRecoveryPhrase = { onNavigateTo(RecoveryPhraseRoute) },
        )
    }
    taggedEntry<AdvancedSettingsRoute> {
        AdvancedSettingsScreen(onBack = onBack, onNavigateToDeveloperTools = { onNavigateTo(DeveloperToolsRoute) })
    }
    taggedEntry<DeveloperToolsRoute> {
        DeveloperToolsScreen(onBack = onBack)
    }
    taggedEntry<LocationSettingsRoute> {
        LocationSettingsScreen(
            onBack = onBack,
            onOpenLocationTimeline = {},
            onNavigateToTrackingOptions = { onNavigateTo(LocationTrackingOptionsRoute) },
            onNavigateToInterval = { onNavigateTo(LocationIntervalRoute) },
        )
    }
    taggedEntry<LocationTrackingOptionsRoute> {
        LocationTrackingOptionsScreen(onBack = onBack)
    }
    taggedEntry<LocationIntervalRoute> {
        LocationIntervalScreen(onBack = onBack)
    }
    taggedEntry<LocationAdvancedRoute> {
        DeveloperToolsScreen(onBack = onBack)
    }
    taggedEntry<MemoriesSettingsRoute> {
        MemoriesSettingsScreen(
            onBack = onBack,
            onNavigateToRecommendations = { onNavigateTo(RecommendationSettingsRoute) },
        )
    }
    taggedEntry<VoiceNotesSettingsRoute> {
        VoiceNotesSettingsScreen(
            onBack = onBack,
            onOpenTranscription = { onNavigateTo(TranscriptionSettingsRoute) },
            onOpenSoundRecognition = { onNavigateTo(SoundRecognitionSettingsRoute) },
        )
    }
    taggedEntry<TranscriptionSettingsRoute> {
        AudioModelSettingsScreen(AudioSettingsCapability.Transcription, onBack)
    }
    taggedEntry<SoundRecognitionSettingsRoute> {
        AudioModelSettingsScreen(AudioSettingsCapability.SoundRecognition, onBack)
    }
    taggedEntry<StreakSettingsRoute> {
        StreakSettingsScreen(onBack = onBack)
    }
    taggedEntry<TimelineSettingsRoute> {
        TimelineSettingsScreen(
            onBack = onBack,
            onNavigateToDayBoundary = { onNavigateTo(DayBoundarySettingsRoute) },
        )
    }
    taggedEntry<DayBoundarySettingsRoute> {
        DayBoundarySettingsScreen(onBack = onBack)
    }
    taggedEntry<SyncSettingsRoute> {
        SyncSettingsScreen(
            onBack = onBack,
            onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
            onNavigateToSignIn = onNavigateToSignIn,
            onNavigateToRecoveryPhrase = { onNavigateTo(RecoveryPhraseRoute) },
            onReviewSavedEdits = { onNavigateTo(app.logdate.feature.core.sync.navigation.SyncIssuesRoute) },
            onRecoverJournalMerge = { operation ->
                onNavigateTo(JournalMergeRoute(operation.sourceId.toString(), operation.operationId.toString()))
            },
        )
    }
    taggedEntry<RecoveryPhraseEntrySettingsRoute> {
        RecoveryPhraseEntrySettingsScreen(
            onBack = onBack,
            onRecovered = onBack,
        )
    }
    taggedEntry<ExportSettingsRoute> {
        ExportSettingsScreen(onBack = onBack)
    }
    taggedEntry<LibrarySettingsRoute> {
        LibrarySettingsScreen(onBack = onBack)
    }
    taggedEntry<ResetSettingsRoute> {
        ResetSettingsScreen(
            onBack = onBack,
            onNavigateToClearData = { onNavigateTo(ClearDataSettingsRoute) },
            onNavigateToResetApp = { onNavigateTo(ResetAppSettingsRoute) },
        )
    }
    taggedEntry<ClearDataSettingsRoute> {
        ClearDataSettingsScreen(onBack = onBack)
    }
    taggedEntry<ResetAppSettingsRoute> {
        ResetAppSettingsScreen(
            onBack = onBack,
            onAppReset = onResetApp,
        )
    }
    taggedEntry<BirthdaySettingsRoute> {
        BirthdaySettingsScreen(onBack = onBack)
    }
    taggedEntry<RecommendationSettingsRoute> {
        RecommendationSettingsScreen(onBack = onBack)
    }
    taggedEntry<DevicesRoute> {
        DevicesScreen(onBackClick = onBack)
    }
    taggedEntry<RewindSettingsRoute> {
        RewindSettingsScreen(onBack = onBack)
    }
    taggedEntry<EventsSettingsRoute> {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            EventsSettingsScreen(
                onBack = onBack,
                onNavigateToCalendar = { onNavigateTo(EventsCalendarRoute) },
                onNavigateToCalendarSync = { onNavigateTo(CalendarSyncSettingsRoute) },
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<EventsCalendarRoute> {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            EventsCalendarScreen(
                onBack = onBack,
                onNavigateToEvent = onNavigateToEvent,
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<CalendarSyncSettingsRoute> {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            CalendarSyncSettingsScreen(
                onBack = onBack,
                onNavigateToCalendars = { onNavigateTo(CalendarSyncCalendarsRoute) },
                onNavigateToActivity = { onNavigateTo(CalendarSyncActivityRoute) },
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<CalendarSyncCalendarsRoute> {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            CalendarSyncCalendarsScreen(onBack = onBack)
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<CalendarSyncActivityRoute> {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            CalendarSyncActivityScreen(
                onBack = onBack,
                onNavigateToEvent = onNavigateToEvent,
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<PeopleSettingsRoute> {
        if (FeatureFlag.PEOPLE.availableForLaunch) {
            val contactsPermissionState = rememberContactsPermissionState()
            PeopleSettingsScreen(
                onBack = onBack,
                onBrowsePeople = { onNavigateTo(PeopleDirectoryRoute) },
                onOpenReviewInbox = { onNavigateTo(PeopleInboxRoute) },
                contactsPermissionState = contactsPermissionState,
                onImportSelectedContacts = {},
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<PeopleDirectoryRoute> {
        if (FeatureFlag.PEOPLE.availableForLaunch) {
            PeopleDirectoryScreen(
                onBack = onBack,
                onOpenPerson = { onNavigateTo(PersonDetailRoute(it)) },
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<PeopleInboxRoute> {
        if (FeatureFlag.PEOPLE.availableForLaunch) {
            PeopleInboxScreen(onBack = onBack)
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<PersonDetailRoute> { route ->
        if (FeatureFlag.PEOPLE.availableForLaunch) {
            PersonDetailScreen(
                personId = Uuid.parse(route.personId),
                onBack = onBack,
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
}

@Composable
@Suppress("ktlint:standard:function-naming")
private fun ExitDeferredRoute(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
