package app.logdate.client.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.shared.model.profile.asBirthdayDateInstant
import app.logdate.shared.model.user.AppSecurityLevel
import app.logdate.shared.model.user.UserData
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlin.time.Clock
import kotlin.time.Instant

private const val DEFAULT_PEOPLE_CONTACTS_ACCESS_MODE = "NONE"

/**
 * A local data source for user preferences.
 */
class LogdatePreferencesDataSource(
    private val userPreferences: DataStore<Preferences>,
    private val featureAvailable: (FeatureFlag) -> Boolean = { it.availableForLaunch },
) {
    fun observeEntryStartMode(): Flow<EntryStartMode> =
        userPreferences.data.map { preferences ->
            EntryStartMode.entries.firstOrNull { it.name == preferences[ENTRY_START_MODE] } ?: EntryStartMode.TEXT
        }

    suspend fun setEntryStartMode(mode: EntryStartMode) {
        userPreferences.updateData { it.toMutablePreferences().apply { this[ENTRY_START_MODE] = mode.name } }
    }

    fun observeDeveloperModeEnabled(): Flow<Boolean> = userPreferences.data.map { it[DEVELOPER_MODE_ENABLED] ?: false }

    suspend fun setDeveloperModeEnabled(enabled: Boolean) {
        userPreferences.updateData { it.toMutablePreferences().apply { this[DEVELOPER_MODE_ENABLED] = enabled } }
    }

    fun observeQuietSaveEnabled(): Flow<Boolean> = userPreferences.data.map { it[QUIET_SAVE_ENABLED] ?: true }

    suspend fun setQuietSaveEnabled(enabled: Boolean) {
        userPreferences.updateData { it.toMutablePreferences().apply { this[QUIET_SAVE_ENABLED] = enabled } }
    }

    private val historyPreferences by lazy { HistoryPreferences(userPreferences) }
    private val profilePreferences by lazy { ProfilePreferences(userPreferences, userData, ::setBirthdate) }

    fun archiveProfileRecoveryStore() = ArchiveProfileRecoveryStore(userPreferences)

    // TODO: Migrate to datastore-proto
    companion object {
        val ENTRY_START_MODE = stringPreferencesKey("entry_default_capture_mode")
        val DEVELOPER_MODE_ENABLED = booleanPreferencesKey("developer_mode_enabled")
        val QUIET_SAVE_ENABLED = booleanPreferencesKey("quiet_save_preference_enabled")
        val BIRTHDAY = longPreferencesKey("birthday")
        val IS_ONBOARDED = booleanPreferencesKey("is_onboarded")
        val ONBOARDED_TIMESTAMP = longPreferencesKey("onboarded_timestamp")
        val SECURITY_LEVEL = stringPreferencesKey("security_level")
        val DAY_START_HOUR = intPreferencesKey("day_start_hour")
        val DAY_END_HOUR = intPreferencesKey("day_end_hour")
        val SYSTEM_SEARCH_VISIBILITY_ENABLED = booleanPreferencesKey("system_search_visibility_enabled")
        val DEVICE_ASSISTANT_SCOPES = stringSetPreferencesKey("device_assistant_scopes")
        val DEVICE_ASSISTANT_JOURNAL_IDS = stringSetPreferencesKey("device_assistant_journal_ids")
        val DEVICE_ASSISTANT_INCLUDES_UNFILED = booleanPreferencesKey("device_assistant_includes_unfiled")
        val FAVORITE_NOTE_IDS = stringSetPreferencesKey("favorite_note_ids")

        // Account IDs that have already had their local data backfilled into the sync queue
        // after first sign-in. Idempotency guard so a re-sign-in into the same account doesn't
        // re-enqueue everything; signing into a different account does trigger a fresh pass.
        val BACKFILLED_ACCOUNT_IDS = stringSetPreferencesKey("backfilled_account_ids")

        // Feature flags
        val LIBRARY_ENABLED = booleanPreferencesKey("library_enabled")
        val EVENTS_ENABLED = booleanPreferencesKey("events_enabled")
        val PEOPLE_ENABLED = booleanPreferencesKey("people_enabled")
        val PEOPLE_CONTACTS_ACCESS_MODE = stringPreferencesKey("people_contacts_access_mode")

        // Journal UI keys
        val JOURNAL_LAYOUT_MODE = stringPreferencesKey("journal_layout_mode")

        // Profile keys
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val PROFILE_PHOTO_URI = stringPreferencesKey("profile_photo_uri")
        val BIO = stringPreferencesKey("bio")
        val ORIGINAL_BIO = stringPreferencesKey("original_bio")
        val PROFILE_CREATED_AT = longPreferencesKey("profile_created_at")
        val PROFILE_LAST_UPDATED_AT = longPreferencesKey("profile_last_updated_at")

        // Calendar preferences
        val FIRST_DAY_OF_WEEK = stringPreferencesKey("first_day_of_week")

        // Rewind preferences
        val REWIND_AUTO_GENERATION_ENABLED = booleanPreferencesKey("rewind_auto_generation_enabled")
        val REWIND_NOTIFICATIONS_ENABLED = booleanPreferencesKey("rewind_notifications_enabled")
        val REWIND_REFLECTION_REPLIES_ENABLED = booleanPreferencesKey("rewind_reflection_replies_enabled")
        val HAS_SEEN_REWIND_ONBOARDING = booleanPreferencesKey("has_seen_rewind_onboarding")
        val REWIND_CURATION_STRICTNESS = stringPreferencesKey("rewind_curation_strictness")
        val REWIND_INCLUDE_SCREENSHOTS = booleanPreferencesKey("rewind_include_screenshots")

        // Event inference preferences
        val EVENT_INFERENCE_SENSITIVITY = stringPreferencesKey("event_inference_sensitivity")
        val EVENT_INFERENCE_AI_NAMING_ENABLED = booleanPreferencesKey("event_inference_ai_naming_enabled")
        val EVENT_INFERENCE_LAST_RUN_AT = longPreferencesKey("event_inference_last_run_at")
        val EVENT_INFERENCE_LAST_CREATED_COUNT = intPreferencesKey("event_inference_last_created_count")
        val EVENT_INFERENCE_RECENT_CREATED_COUNT = intPreferencesKey("event_inference_recent_created_count")
        val EVENT_INFERENCE_LAST_ERROR_KIND = stringPreferencesKey("event_inference_last_error_kind")

        // Device calendar sync preferences
        val DEVICE_CALENDAR_SYNC_ENABLED = booleanPreferencesKey("device_calendar_sync_enabled")
        val DEVICE_CALENDAR_ENABLED_IDS = stringSetPreferencesKey("device_calendar_enabled_ids")
        val DEVICE_CALENDAR_SYNC_LAST_RUN_AT = longPreferencesKey("device_calendar_sync_last_run_at")
        val DEVICE_CALENDAR_SYNC_LAST_CREATED_COUNT = intPreferencesKey("device_calendar_sync_last_created_count")
        val DEVICE_CALENDAR_SYNC_LAST_UPDATED_COUNT = intPreferencesKey("device_calendar_sync_last_updated_count")
        val DEVICE_CALENDAR_SYNC_LAST_ERROR_KIND = stringPreferencesKey("device_calendar_sync_last_error_kind")

        // Android AppSearch metadata
        val ANDROID_PLATFORM_SEARCH_INDEX_GENERATION = longPreferencesKey("android_platform_search_index_generation")
        val ANDROID_PLATFORM_SEARCH_SCHEMA_VERSION = intPreferencesKey("android_platform_search_schema_version")
    }

    val userData: Flow<UserData> =
        userPreferences.data.map { prefs ->
            val birthdayMillis = prefs[BIRTHDAY] ?: 0
            Napier.d("Read birthday from preferences: $birthdayMillis")

            UserData(
                birthday =
                    millisToInstantOrDistantPast(birthdayMillis).takeUnless { it == Instant.DISTANT_PAST }?.asBirthdayDateInstant()
                        ?: Instant.DISTANT_PAST,
                isOnboarded = prefs[IS_ONBOARDED] == true,
                onboardedDate = millisToInstantOrDistantPast(prefs[ONBOARDED_TIMESTAMP]),
                securityLevel =
                    AppSecurityLevel.valueOf(
                        prefs[SECURITY_LEVEL] ?: AppSecurityLevel.NONE.name,
                    ),
                favoriteNotes = prefs[FAVORITE_NOTE_IDS]?.toList().orEmpty(),
                // Profile data
                displayName = prefs[DISPLAY_NAME] ?: "",
                profilePhotoUri = prefs[PROFILE_PHOTO_URI],
                bio = prefs[BIO],
                originalBio = prefs[ORIGINAL_BIO],
                profileCreatedAt = millisToInstantOrDistantPast(prefs[PROFILE_CREATED_AT]),
                profileLastUpdatedAt = millisToInstantOrDistantPast(prefs[PROFILE_LAST_UPDATED_AT]),
            )
        }

    fun observeLibraryEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[LIBRARY_ENABLED] ?: FeatureFlag.LIBRARY.defaultEnabled
        }

    fun observeSystemSearchVisibilityEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[SYSTEM_SEARCH_VISIBILITY_ENABLED] ?: true
        }

    fun observeAndroidPlatformSearchIndexedGeneration(): Flow<Long?> =
        userPreferences.data.map { prefs ->
            prefs[ANDROID_PLATFORM_SEARCH_INDEX_GENERATION]
        }

    suspend fun setLibraryEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[LIBRARY_ENABLED] = enabled
            }
        }
    }

    fun observeEventsEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            featureAvailable(FeatureFlag.EVENTS) &&
                (prefs[EVENTS_ENABLED] ?: FeatureFlag.EVENTS.defaultEnabled)
        }

    suspend fun isEventsEnabled(): Boolean = observeEventsEnabled().first()

    suspend fun setEventsEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[EVENTS_ENABLED] = enabled
            }
        }
    }

    fun observePeopleEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            featureAvailable(FeatureFlag.PEOPLE) &&
                (prefs[PEOPLE_ENABLED] ?: FeatureFlag.PEOPLE.defaultEnabled)
        }

    suspend fun setPeopleEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[PEOPLE_ENABLED] = enabled
            }
        }
    }

    fun observePeopleContactsAccessMode(): Flow<String> =
        userPreferences.data.map { prefs ->
            prefs[PEOPLE_CONTACTS_ACCESS_MODE] ?: DEFAULT_PEOPLE_CONTACTS_ACCESS_MODE
        }

    suspend fun setPeopleContactsAccessMode(modeName: String) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[PEOPLE_CONTACTS_ACCESS_MODE] = modeName
            }
        }
    }

    suspend fun setSystemSearchVisibilityEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[SYSTEM_SEARCH_VISIBILITY_ENABLED] = enabled
            }
        }
    }

    suspend fun getSystemSearchVisibilityEnabled(): Boolean = observeSystemSearchVisibilityEnabled().first()

    /** What assistants on this device may see and do. Nothing until the person turns it on. */
    fun observeDeviceAssistantAccess(): Flow<StoredDeviceAssistantAccess> =
        userPreferences.data.map { prefs ->
            StoredDeviceAssistantAccess(
                scopes = prefs[DEVICE_ASSISTANT_SCOPES].orEmpty(),
                journalIds = prefs[DEVICE_ASSISTANT_JOURNAL_IDS].orEmpty(),
                includesUnfiled = prefs[DEVICE_ASSISTANT_INCLUDES_UNFILED] ?: false,
            )
        }

    suspend fun setDeviceAssistantAccess(access: StoredDeviceAssistantAccess) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[DEVICE_ASSISTANT_SCOPES] = access.scopes
                this[DEVICE_ASSISTANT_JOURNAL_IDS] = access.journalIds
                this[DEVICE_ASSISTANT_INCLUDES_UNFILED] = access.includesUnfiled
            }
        }
    }

    suspend fun getAndroidPlatformSearchIndexedGeneration(): Long? =
        userPreferences.data
            .map { prefs -> prefs[ANDROID_PLATFORM_SEARCH_INDEX_GENERATION] }
            .first()

    suspend fun getAndroidPlatformSearchSchemaVersion(): Int? =
        userPreferences.data
            .map { prefs -> prefs[ANDROID_PLATFORM_SEARCH_SCHEMA_VERSION] }
            .first()

    suspend fun setAndroidPlatformSearchIndexState(
        generation: Long,
        schemaVersion: Int,
    ) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[ANDROID_PLATFORM_SEARCH_INDEX_GENERATION] = generation
                this[ANDROID_PLATFORM_SEARCH_SCHEMA_VERSION] = schemaVersion
            }
        }
    }

    fun observeDayStartHour(): Flow<Int?> =
        userPreferences.data.map { prefs ->
            prefs[DAY_START_HOUR]
        }

    fun observeJournalLayoutMode(): Flow<String> =
        userPreferences.data.map { prefs ->
            prefs[JOURNAL_LAYOUT_MODE] ?: "CAROUSEL"
        }

    suspend fun setJournalLayoutMode(modeName: String) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[JOURNAL_LAYOUT_MODE] = modeName
            }
        }
    }

    suspend fun setShouldHideOnboarding(value: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                putAll(
                    IS_ONBOARDED to value,
                    ONBOARDED_TIMESTAMP to Clock.System.now().toEpochMilliseconds(),
                )
            }
        }
    }

    suspend fun setShouldShowBiometric(value: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                putAll(
                    SECURITY_LEVEL to if (value) AppSecurityLevel.BIOMETRIC.name else AppSecurityLevel.NONE.name,
                )
            }
        }
    }

    suspend fun addFavoriteNotes(noteIds: Set<String>) {
        if (noteIds.isEmpty()) return

        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                val current = this[FAVORITE_NOTE_IDS].orEmpty()
                this[FAVORITE_NOTE_IDS] = current + noteIds
            }
        }
    }

    suspend fun setBirthdate(birthday: Instant) {
        Napier.d("Setting birthday to: $birthday")
        userPreferences.updateData { preferences ->
            val millisValue =
                if (birthday == Instant.DISTANT_PAST) {
                    0L
                } else {
                    birthday.asBirthdayDateInstant().toEpochMilliseconds()
                }
            Napier.d("Birthday in milliseconds: $millisValue")

            preferences.toMutablePreferences().apply {
                putAll(
                    BIRTHDAY to millisValue,
                )
                Napier.d("Birthday set in preferences: ${this[BIRTHDAY]}")
            }
        }
        Napier.d("Birthday update complete")
    }

    suspend fun getBackfilledAccountIds(): Set<String> = userPreferences.data.first()[BACKFILLED_ACCOUNT_IDS] ?: emptySet()

    suspend fun markAccountBackfilled(accountId: String) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                val current = this[BACKFILLED_ACCOUNT_IDS] ?: emptySet()
                this[BACKFILLED_ACCOUNT_IDS] = current + accountId
            }
        }
    }

    suspend fun getPreferences(): UserPreferences {
        // Using first() to get the first emission from the flow
        // This avoids collecting indefinitely
        return userPreferences.data
            .map { prefs ->
                UserPreferences(
                    dayStartHour = prefs[DAY_START_HOUR],
                    dayEndHour = prefs[DAY_END_HOUR],
                )
            }.first()
    }

    suspend fun setDayBounds(
        startHour: Int,
        endHour: Int,
    ) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                putAll(
                    DAY_START_HOUR to startHour,
                    DAY_END_HOUR to endHour,
                )
            }
        }
    }

    suspend fun updateDisplayName(displayName: String): Result<UserData> = profilePreferences.updateDisplayName(displayName)

    suspend fun updateBirthday(birthday: Instant): Result<UserData> = profilePreferences.updateBirthday(birthday)

    suspend fun updateProfilePhotoUri(profilePhotoUri: String?): Result<UserData> =
        profilePreferences.updateProfilePhotoUri(profilePhotoUri)

    suspend fun updateBio(
        bio: String?,
        originalBio: String?,
    ): Result<UserData> = profilePreferences.updateBio(bio, originalBio)

    suspend fun updateProfileLastUpdated(timestamp: Instant) = profilePreferences.updateProfileLastUpdated(timestamp)

    suspend fun getCurrentUserData(): UserData = userData.first()

    suspend fun clearUserData(): Result<Unit> =
        try {
            userPreferences.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    clear()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Napier.e("Failed to clear user data", e)
            Result.failure(e)
        }

    fun observeFirstDayOfWeek(): Flow<DayOfWeek?> =
        userPreferences.data.map { prefs ->
            val stored = prefs[FIRST_DAY_OF_WEEK] ?: return@map null
            runCatching { DayOfWeek.valueOf(stored) }.getOrNull()
        }

    suspend fun getFirstDayOfWeek(): DayOfWeek? = observeFirstDayOfWeek().first()

    suspend fun setFirstDayOfWeek(dayOfWeek: DayOfWeek) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[FIRST_DAY_OF_WEEK] = dayOfWeek.name
            }
        }
    }

    fun observeRewindAutoGenerationEnabled(): Flow<Boolean> = historyPreferences.observeRewindAutoGenerationEnabled()

    suspend fun isRewindAutoGenerationEnabled(): Boolean = historyPreferences.isRewindAutoGenerationEnabled()

    suspend fun setRewindAutoGenerationEnabled(enabled: Boolean) = historyPreferences.setRewindAutoGenerationEnabled(enabled)

    fun observeRewindNotificationsEnabled(): Flow<Boolean> = historyPreferences.observeRewindNotificationsEnabled()

    suspend fun isRewindNotificationsEnabled(): Boolean = historyPreferences.isRewindNotificationsEnabled()

    suspend fun setRewindNotificationsEnabled(enabled: Boolean) = historyPreferences.setRewindNotificationsEnabled(enabled)

    fun observeRewindReflectionRepliesEnabled(): Flow<Boolean> = historyPreferences.observeRewindReflectionRepliesEnabled()

    suspend fun setRewindReflectionRepliesEnabled(enabled: Boolean) = historyPreferences.setRewindReflectionRepliesEnabled(enabled)

    fun observeHasSeenRewindOnboarding(): Flow<Boolean> = historyPreferences.observeHasSeenRewindOnboarding()

    suspend fun hasSeenRewindOnboarding(): Boolean = historyPreferences.hasSeenRewindOnboarding()

    suspend fun setHasSeenRewindOnboarding(value: Boolean) = historyPreferences.setHasSeenRewindOnboarding(value)

    fun observeRewindCurationStrictness(): Flow<String> = historyPreferences.observeRewindCurationStrictness()

    suspend fun getRewindCurationStrictness(): String = historyPreferences.getRewindCurationStrictness()

    suspend fun setRewindCurationStrictness(strictness: String) = historyPreferences.setRewindCurationStrictness(strictness)

    fun observeRewindIncludeScreenshots(): Flow<Boolean> = historyPreferences.observeRewindIncludeScreenshots()

    suspend fun isRewindIncludeScreenshots(): Boolean = historyPreferences.isRewindIncludeScreenshots()

    suspend fun setRewindIncludeScreenshots(value: Boolean) = historyPreferences.setRewindIncludeScreenshots(value)

    fun observeEventInferenceSensitivity(): Flow<String> = historyPreferences.observeEventInferenceSensitivity()

    suspend fun getEventInferenceSensitivity(): String = historyPreferences.getEventInferenceSensitivity()

    suspend fun setEventInferenceSensitivity(sensitivity: String) = historyPreferences.setEventInferenceSensitivity(sensitivity)

    fun observeEventInferenceAiNamingEnabled(): Flow<Boolean> = historyPreferences.observeEventInferenceAiNamingEnabled()

    suspend fun isEventInferenceAiNamingEnabled(): Boolean = historyPreferences.isEventInferenceAiNamingEnabled()

    suspend fun setEventInferenceAiNamingEnabled(enabled: Boolean) = historyPreferences.setEventInferenceAiNamingEnabled(enabled)

    fun observeEventInferenceStats(): Flow<EventInferenceStats> = historyPreferences.observeEventInferenceStats()

    suspend fun recordEventInferenceRun(
        runAt: Instant,
        createdThisRun: Int,
        errorKind: String?,
    ) = historyPreferences.recordEventInferenceRun(runAt, createdThisRun, errorKind)

    fun observeDeviceCalendarSyncEnabled(): Flow<Boolean> = historyPreferences.observeDeviceCalendarSyncEnabled()

    suspend fun isDeviceCalendarSyncEnabled(): Boolean = historyPreferences.isDeviceCalendarSyncEnabled()

    suspend fun setDeviceCalendarSyncEnabled(enabled: Boolean) = historyPreferences.setDeviceCalendarSyncEnabled(enabled)

    fun observeDeviceCalendarEnabledIds(): Flow<Set<String>> = historyPreferences.observeDeviceCalendarEnabledIds()

    suspend fun getDeviceCalendarEnabledIds(): Set<String> = historyPreferences.getDeviceCalendarEnabledIds()

    suspend fun setDeviceCalendarEnabledIds(ids: Set<String>) = historyPreferences.setDeviceCalendarEnabledIds(ids)

    fun observeDeviceCalendarSyncStats(): Flow<DeviceCalendarSyncStats> = historyPreferences.observeDeviceCalendarSyncStats()

    suspend fun recordDeviceCalendarSyncRun(
        runAt: Instant,
        created: Int,
        updated: Int,
        errorKind: String?,
    ) = historyPreferences.recordDeviceCalendarSyncRun(runAt, created, updated, errorKind)

    private fun millisToInstantOrDistantPast(millis: Long?): Instant {
        val value = millis ?: 0L
        return if (value == 0L) {
            Instant.DISTANT_PAST
        } else {
            Instant.fromEpochMilliseconds(value)
        }
    }
}
