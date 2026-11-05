package app.logdate.feature.core.settings.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data class SettingsRoute(
    val settingId: String? = null,
    val selectedDetail: String? = null,
) : NavKey

@Serializable
data class DevicesRoute(
    val id: String = "devices",
) : NavKey

@Serializable
data object AccountSettingsRoute : NavKey

@Serializable
data object SignInMethodsRoute : NavKey

@Serializable
data object RecoveryPhraseRoute : NavKey

@Serializable
data object HostingRoute : NavKey

@Serializable
data object MoveServerRoute : NavKey

@Serializable
data object DeleteAccountRoute : NavKey

@Serializable
data object PrivacySettingsRoute : NavKey

@Serializable
data object DataSettingsRoute : NavKey

@Serializable
data object LocationSettingsRoute : NavKey

@Serializable
data object AdvancedSettingsRoute : NavKey

@Serializable
data object DeveloperToolsRoute : NavKey

@Serializable
data object MemoriesSettingsRoute : NavKey

@Serializable
data object VoiceNotesSettingsRoute : NavKey

@Serializable
data object TranscriptionSettingsRoute : NavKey

@Serializable
data object SoundRecognitionSettingsRoute : NavKey

@Serializable
data object StreakSettingsRoute : NavKey

@Serializable
data object TimelineSettingsRoute : NavKey

@Serializable
data object SyncSettingsRoute : NavKey

@Serializable
data object RecoveryPhraseEntrySettingsRoute : NavKey

@Serializable
data object ExportSettingsRoute : NavKey

@Serializable
data object LibrarySettingsRoute : NavKey

@Serializable
data object ResetSettingsRoute : NavKey

@Serializable
data object BirthdaySettingsRoute : NavKey

@Serializable
data object DayBoundarySettingsRoute : NavKey

@Serializable
data object RecommendationSettingsRoute : NavKey

@Serializable
data object ClearDataSettingsRoute : NavKey

@Serializable
data object ResetAppSettingsRoute : NavKey

@Serializable
data object RewindSettingsRoute : NavKey

@Serializable
data object EventsSettingsRoute : NavKey

@Serializable
data object EventsCalendarRoute : NavKey

@Serializable
data object CalendarSyncSettingsRoute : NavKey

@Serializable
data object CalendarSyncCalendarsRoute : NavKey

@Serializable
data object CalendarSyncActivityRoute : NavKey

@Serializable
data object PeopleSettingsRoute : NavKey

@Serializable
data object PeopleDirectoryRoute : NavKey

@Serializable
data object PeopleInboxRoute : NavKey

@Serializable
data class PersonDetailRoute(
    val personId: String,
) : NavKey {
    constructor(personId: kotlin.uuid.Uuid) : this(personId.toString())
}

@Serializable
data object LocationTrackingOptionsRoute : NavKey

@Serializable
data object LocationIntervalRoute : NavKey

@Serializable
data object LocationAdvancedRoute : NavKey

@Serializable
data object WatchSettingsRoute : NavKey

@Serializable
data object WatchNotificationSettingsRoute : NavKey

@Serializable
data object WatchTroubleshootingRoute : NavKey
