package app.logdate.feature.core.settings.navigation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata

const val SETTINGS_CATEGORIES_METADATA_KEY = "logdate.settingsCategories"

class SettingsActivityCategories(
    val content: @Composable (String) -> Unit,
)

enum class SettingsCategory(
    val id: String,
    val route: NavKey,
) {
    Account("account", AccountSettingsRoute),
    Memories("memories", MemoriesSettingsRoute),
    VoiceNotes("voice-notes", VoiceNotesSettingsRoute),
    Timeline("timeline", TimelineSettingsRoute),
    Streaks("streaks", StreakSettingsRoute),
    Rewind("rewind", RewindSettingsRoute),
    Events("events", EventsSettingsRoute),
    People("people", PeopleSettingsRoute),
    Library("library", LibrarySettingsRoute),
    Privacy("privacy", PrivacySettingsRoute),
    Devices("devices", DevicesRoute()),
    Location("location", LocationSettingsRoute),
    Watch("watch", WatchSettingsRoute),
    Backup("backup", SyncSettingsRoute),
    Export("export", ExportSettingsRoute),
    Reset("reset", ResetSettingsRoute),
    About("about", AdvancedSettingsRoute),
}

fun settingsDetailMetadata(
    category: SettingsCategory,
    categories: SettingsActivityCategories,
): Map<String, Any> =
    ActivityPresentation.SettingsDetail(category.id).asMetadata() +
        (SETTINGS_CATEGORIES_METADATA_KEY to categories)

fun NavKey.settingsCategory(): SettingsCategory? =
    when (this) {
        AccountSettingsRoute, SignInMethodsRoute, RecoveryPhraseRoute, HostingRoute,
        MoveServerRoute, DeleteAccountRoute, RecoveryPhraseEntrySettingsRoute,
        -> SettingsCategory.Account

        MemoriesSettingsRoute, RecommendationSettingsRoute -> SettingsCategory.Memories

        VoiceNotesSettingsRoute -> SettingsCategory.VoiceNotes

        TimelineSettingsRoute, DayBoundarySettingsRoute -> SettingsCategory.Timeline

        StreakSettingsRoute -> SettingsCategory.Streaks

        RewindSettingsRoute -> SettingsCategory.Rewind

        EventsSettingsRoute, EventsCalendarRoute, CalendarSyncSettingsRoute,
        CalendarSyncCalendarsRoute, CalendarSyncActivityRoute,
        -> SettingsCategory.Events

        PeopleSettingsRoute, PeopleDirectoryRoute, PeopleInboxRoute, is PersonDetailRoute -> SettingsCategory.People

        LibrarySettingsRoute -> SettingsCategory.Library

        PrivacySettingsRoute -> SettingsCategory.Privacy

        is DevicesRoute -> SettingsCategory.Devices

        LocationSettingsRoute, LocationTrackingOptionsRoute, LocationIntervalRoute,
        LocationAdvancedRoute,
        -> SettingsCategory.Location

        WatchSettingsRoute,
        WatchNotificationSettingsRoute,
        WatchTroubleshootingRoute,
        WatchTilesRoute,
        WatchCheckConnectionRoute,
        WatchMicrophoneRoute,
        -> SettingsCategory.Watch

        SyncSettingsRoute, DataSettingsRoute -> SettingsCategory.Backup

        ExportSettingsRoute -> SettingsCategory.Export

        ResetSettingsRoute, ClearDataSettingsRoute, ResetAppSettingsRoute -> SettingsCategory.Reset

        AdvancedSettingsRoute, DeveloperToolsRoute, LabsSettingsRoute -> SettingsCategory.About

        BirthdaySettingsRoute -> SettingsCategory.Account

        else -> null
    }

fun NavBackStack<NavKey>.navigateToSettingsCategory(route: NavKey) {
    val category = route.settingsCategory() ?: return
    if (lastOrNull()?.settingsCategory() == category) return
    while (lastOrNull()?.settingsCategory() != null) removeAt(lastIndex)
    add(route)
}
