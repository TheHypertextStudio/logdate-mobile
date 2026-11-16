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
import app.logdate.feature.core.settings.ui.ReminderSettingsFix
import app.logdate.feature.core.settings.ui.ResetAppSettingsScreen
import app.logdate.feature.core.settings.ui.ResetSettingsScreen
import app.logdate.feature.core.settings.ui.SettingsCategoryNavigationScreen
import app.logdate.feature.core.settings.ui.SettingsOverviewScreen
import app.logdate.feature.core.settings.ui.StreakSettingsScreen
import app.logdate.feature.core.settings.ui.SyncSettingsScreen
import app.logdate.feature.core.settings.ui.TimelineSettingsScreen
import app.logdate.feature.core.settings.ui.VoiceNotesSettingsScreen
import app.logdate.feature.core.settings.ui.devices.DevicesScreen
import app.logdate.feature.core.settings.ui.labs.LabsSettingsScreen
import app.logdate.feature.events.ui.calendar.EventsCalendarScreen
import app.logdate.feature.events.ui.calendarsync.CalendarSyncActivityScreen
import app.logdate.feature.events.ui.calendarsync.CalendarSyncCalendarsScreen
import app.logdate.feature.events.ui.calendarsync.CalendarSyncSettingsScreen
import app.logdate.feature.events.ui.settings.EventsSettingsScreen
import app.logdate.feature.journals.navigation.JournalMergeRoute
import app.logdate.feature.rewind.ui.settings.RewindSettingsScreen
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.ViewModelsPerVisit
import app.logdate.ui.navigation.asMetadata
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
 * @param onOpenLocationTimeline exposes history browsing only when available; capture settings remain independent
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
    onNavigateToCategory: (NavKey) -> Unit = onNavigateTo,
    onOpenLocationTimeline: (() -> Unit)? = null,
) {
    val categories =
        SettingsActivityCategories { section ->
            SettingsCategoryNavigationScreen(
                selectedSection = section,
                onNavigateTo = onNavigateToCategory,
                onBack = onBack,
                onNavigateToProfile = onNavigateToProfile,
                onNavigateToWatch = onNavigateToWatch,
            )
        }
    taggedEntry<SettingsRoute>(metadata = ActivityPresentation.SettingsRoot.asMetadata()) {
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
    taggedEntry<AccountSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories) + ViewModelsPerVisit) {
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
    taggedEntry<SignInMethodsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories) + ViewModelsPerVisit) {
        SignInMethodsScreen(onBack = onBack)
    }
    taggedEntry<RecoveryPhraseRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories) + ViewModelsPerVisit) {
        RecoveryPhraseScreen(
            onBack = onBack,
            onEnterPhrase = { onNavigateTo(RecoveryPhraseEntrySettingsRoute) },
        )
    }
    taggedEntry<HostingRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories) + ViewModelsPerVisit) {
        HostingScreen(onBack = onBack, onMoveAccount = { onNavigateTo(MoveServerRoute) })
    }
    taggedEntry<MoveServerRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories) + ViewModelsPerVisit) {
        MoveServerScreen(onClose = onBack)
    }
    taggedEntry<DeleteAccountRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories) + ViewModelsPerVisit) {
        DeleteAccountScreen(onBack = onBack, onDeviceErased = onResetApp)
    }
    taggedEntry<PrivacySettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Privacy, categories)) {
        PrivacySettingsScreen(
            onBack = onBack,
            onNavigateToLocationSettings = { onNavigateTo(LocationSettingsRoute) },
        )
    }
    taggedEntry<DataSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Backup, categories)) {
        DataSettingsScreen(
            onBack = onBack,
            onNavigateToRecoveryPhrase = { onNavigateTo(RecoveryPhraseEntrySettingsRoute) },
        )
    }
    taggedEntry<AdvancedSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.About, categories)) {
        AdvancedSettingsScreen(
            onBack = onBack,
            onNavigateToDeveloperTools = { onNavigateTo(DeveloperToolsRoute) },
            onNavigateToLabs = { onNavigateTo(LabsSettingsRoute) },
        )
    }
    taggedEntry<DeveloperToolsRoute>(metadata = settingsDetailMetadata(SettingsCategory.About, categories)) {
        DeveloperToolsScreen(onBack = onBack)
    }
    taggedEntry<LabsSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.About, categories)) {
        LabsSettingsScreen(onBack = onBack)
    }
    taggedEntry<LocationSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Location, categories)) {
        LocationSettingsScreen(
            onBack = onBack,
            onOpenLocationTimeline = onOpenLocationTimeline,
            onNavigateToTrackingOptions = { onNavigateTo(LocationTrackingOptionsRoute) },
            onNavigateToInterval = { onNavigateTo(LocationIntervalRoute) },
        )
    }
    taggedEntry<LocationTrackingOptionsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Location, categories)) {
        LocationTrackingOptionsScreen(onBack = onBack)
    }
    taggedEntry<LocationIntervalRoute>(metadata = settingsDetailMetadata(SettingsCategory.Location, categories)) {
        LocationIntervalScreen(onBack = onBack)
    }
    taggedEntry<LocationAdvancedRoute>(metadata = settingsDetailMetadata(SettingsCategory.Location, categories)) {
        DeveloperToolsScreen(onBack = onBack)
    }
    taggedEntry<MemoriesSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Memories, categories)) {
        MemoriesSettingsScreen(
            onBack = onBack,
            onNavigateToRecommendations = { onNavigateTo(RecommendationSettingsRoute) },
        )
    }
    taggedEntry<VoiceNotesSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.VoiceNotes, categories)) {
        VoiceNotesSettingsScreen(onBack = onBack)
    }
    taggedEntry<StreakSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Streaks, categories)) {
        StreakSettingsScreen(onBack = onBack)
    }
    taggedEntry<TimelineSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Timeline, categories)) {
        TimelineSettingsScreen(
            onBack = onBack,
            onNavigateToDayBoundary = { onNavigateTo(DayBoundarySettingsRoute) },
        )
    }
    taggedEntry<DayBoundarySettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Timeline, categories)) {
        DayBoundarySettingsScreen(onBack = onBack)
    }
    taggedEntry<SyncSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Backup, categories)) {
        SyncSettingsScreen(
            onBack = onBack,
            onNavigateToCloudAccountCreation = onNavigateToCloudAccountCreation,
            onNavigateToSignIn = onNavigateToSignIn,
            onNavigateToRecoveryPhrase = { onNavigateTo(RecoveryPhraseEntrySettingsRoute) },
            onNavigateToExport = { onNavigateTo(ExportSettingsRoute) },
            onReviewSavedEdits = { onNavigateTo(app.logdate.feature.core.sync.navigation.SyncIssuesRoute) },
            onRecoverJournalMerge = { operation ->
                onNavigateTo(JournalMergeRoute(operation.sourceId.toString(), operation.operationId.toString()))
            },
        )
    }
    taggedEntry<RecoveryPhraseEntrySettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories)) {
        RecoveryPhraseEntrySettingsScreen(
            onBack = onBack,
            onRecovered = onBack,
        )
    }
    taggedEntry<ExportSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Export, categories)) {
        ExportSettingsScreen(onBack = onBack)
    }
    taggedEntry<LibrarySettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Library, categories)) {
        LibrarySettingsScreen(onBack = onBack)
    }
    taggedEntry<ResetSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Reset, categories)) {
        ResetSettingsScreen(
            onBack = onBack,
            onNavigateToClearData = { onNavigateTo(ClearDataSettingsRoute) },
            onNavigateToResetApp = { onNavigateTo(ResetAppSettingsRoute) },
        )
    }
    taggedEntry<ClearDataSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Reset, categories)) {
        ClearDataSettingsScreen(onBack = onBack)
    }
    taggedEntry<ResetAppSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Reset, categories)) {
        ResetAppSettingsScreen(
            onBack = onBack,
            onAppReset = onResetApp,
        )
    }
    taggedEntry<BirthdaySettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Account, categories)) {
        BirthdaySettingsScreen(onBack = onBack)
    }
    taggedEntry<RecommendationSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Memories, categories)) {
        RecommendationSettingsScreen(
            onBack = onBack,
            onReminderFix = { fix ->
                when (fix) {
                    ReminderSettingsFix.PLACES -> onNavigateTo(LocationSettingsRoute)
                    ReminderSettingsFix.STREAKS -> onNavigateTo(StreakSettingsRoute)
                    ReminderSettingsFix.SLEEP -> onNavigateTo(DayBoundarySettingsRoute)
                    ReminderSettingsFix.NOTIFICATIONS -> Unit
                }
            },
        )
    }
    taggedEntry<DevicesRoute>(metadata = settingsDetailMetadata(SettingsCategory.Devices, categories)) {
        DevicesScreen(onBackClick = onBack, onNavigateToWatch = onNavigateToWatch)
    }
    taggedEntry<RewindSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Rewind, categories)) {
        RewindSettingsScreen(onBack = onBack)
    }
    taggedEntry<EventsSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Events, categories)) {
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
    taggedEntry<EventsCalendarRoute>(metadata = settingsDetailMetadata(SettingsCategory.Events, categories)) {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            EventsCalendarScreen(
                onBack = onBack,
                onNavigateToEvent = onNavigateToEvent,
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<CalendarSyncSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Events, categories)) {
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
    taggedEntry<CalendarSyncCalendarsRoute>(metadata = settingsDetailMetadata(SettingsCategory.Events, categories)) {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            CalendarSyncCalendarsScreen(onBack = onBack)
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<CalendarSyncActivityRoute>(metadata = settingsDetailMetadata(SettingsCategory.Events, categories)) {
        if (FeatureFlag.EVENTS.availableForLaunch) {
            CalendarSyncActivityScreen(
                onBack = onBack,
                onNavigateToEvent = onNavigateToEvent,
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<PeopleSettingsRoute>(metadata = settingsDetailMetadata(SettingsCategory.People, categories)) {
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
    taggedEntry<PeopleDirectoryRoute>(metadata = settingsDetailMetadata(SettingsCategory.People, categories)) {
        if (FeatureFlag.PEOPLE.availableForLaunch) {
            PeopleDirectoryScreen(
                onBack = onBack,
                onOpenPerson = { onNavigateTo(PersonDetailRoute(it)) },
            )
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<PeopleInboxRoute>(metadata = settingsDetailMetadata(SettingsCategory.People, categories)) {
        if (FeatureFlag.PEOPLE.availableForLaunch) {
            PeopleInboxScreen(onBack = onBack)
        } else {
            ExitDeferredRoute(onBack)
        }
    }
    taggedEntry<PersonDetailRoute>(metadata = settingsDetailMetadata(SettingsCategory.People, categories)) { route ->
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
