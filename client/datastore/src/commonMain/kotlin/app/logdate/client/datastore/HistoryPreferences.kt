package app.logdate.client.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.DEVICE_CALENDAR_ENABLED_IDS
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.DEVICE_CALENDAR_SYNC_ENABLED
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.DEVICE_CALENDAR_SYNC_LAST_CREATED_COUNT
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.DEVICE_CALENDAR_SYNC_LAST_ERROR_KIND
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.DEVICE_CALENDAR_SYNC_LAST_RUN_AT
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.DEVICE_CALENDAR_SYNC_LAST_UPDATED_COUNT
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.EVENT_INFERENCE_AI_NAMING_ENABLED
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.EVENT_INFERENCE_LAST_CREATED_COUNT
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.EVENT_INFERENCE_LAST_ERROR_KIND
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.EVENT_INFERENCE_LAST_RUN_AT
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.EVENT_INFERENCE_RECENT_CREATED_COUNT
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.EVENT_INFERENCE_SENSITIVITY
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.HAS_SEEN_REWIND_ONBOARDING
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.REWIND_AUTO_GENERATION_ENABLED
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.REWIND_CURATION_STRICTNESS
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.REWIND_INCLUDE_SCREENSHOTS
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.REWIND_NOTIFICATIONS_ENABLED
import app.logdate.client.datastore.LogdatePreferencesDataSource.Companion.REWIND_REFLECTION_REPLIES_ENABLED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

internal class HistoryPreferences(
    private val userPreferences: DataStore<Preferences>,
) {
    /**
     * Observes whether weekly rewinds should be generated automatically in the background.
     *
     * Defaults to true so existing users keep getting weekly rewinds without opt-in.
     */
    fun observeRewindAutoGenerationEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[REWIND_AUTO_GENERATION_ENABLED] ?: true
        }

    suspend fun isRewindAutoGenerationEnabled(): Boolean = observeRewindAutoGenerationEnabled().first()

    suspend fun setRewindAutoGenerationEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[REWIND_AUTO_GENERATION_ENABLED] = enabled
            }
        }
    }

    /**
     * Observes whether the user wants a notification when a new weekly rewind is ready.
     *
     * Defaults to true. Independent of the system-level notification channel: this gates
     * whether the app even tries to post the notification, so users who turn rewinds off
     * here also stop seeing them in the notification settings flow.
     */
    fun observeRewindNotificationsEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[REWIND_NOTIFICATIONS_ENABLED] ?: true
        }

    suspend fun isRewindNotificationsEnabled(): Boolean = observeRewindNotificationsEnabled().first()

    suspend fun setRewindNotificationsEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[REWIND_NOTIFICATIONS_ENABLED] = enabled
            }
        }
    }

    /**
     * Observes whether the user wants to be able to type replies to a rewind's noticing prompts.
     *
     * Defaults to true. When false, the rewind detail screen still shows the prompts but hides
     * the reply chrome — useful for people who experience the prompts as decoration rather than
     * an invitation.
     */
    fun observeRewindReflectionRepliesEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[REWIND_REFLECTION_REPLIES_ENABLED] ?: true
        }

    suspend fun setRewindReflectionRepliesEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[REWIND_REFLECTION_REPLIES_ENABLED] = enabled
            }
        }
    }

    /**
     * Whether the user has seen the first-Rewind onboarding bottom sheet that explains
     * how the cadence, share, and curation strictness work. Defaults to false so brand-new
     * users still see it; gets set true the first time the sheet is dismissed.
     */
    fun observeHasSeenRewindOnboarding(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[HAS_SEEN_REWIND_ONBOARDING] ?: false
        }

    suspend fun hasSeenRewindOnboarding(): Boolean = observeHasSeenRewindOnboarding().first()

    suspend fun setHasSeenRewindOnboarding(value: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[HAS_SEEN_REWIND_ONBOARDING] = value
            }
        }
    }

    /**
     * How aggressively photo curation drops borderline items. Persisted as the enum name
     * (`LENIENT`, `STANDARD`, `STRICT`) so the lexicon can extend without renumbering.
     * Defaults to STANDARD for new users.
     */
    fun observeRewindCurationStrictness(): Flow<String> =
        userPreferences.data.map { prefs ->
            prefs[REWIND_CURATION_STRICTNESS] ?: "STANDARD"
        }

    suspend fun getRewindCurationStrictness(): String = observeRewindCurationStrictness().first()

    suspend fun setRewindCurationStrictness(strictness: String) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[REWIND_CURATION_STRICTNESS] = strictness
            }
        }
    }

    /**
     * Whether screenshots are allowed to land in a Rewind. Defaults to false so brand-new
     * users get a clean story; the user can opt in from the Rewind settings screen if they
     * want their UI screenshots and saved images to participate.
     */
    fun observeRewindIncludeScreenshots(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[REWIND_INCLUDE_SCREENSHOTS] ?: false
        }

    suspend fun isRewindIncludeScreenshots(): Boolean = observeRewindIncludeScreenshots().first()

    suspend fun setRewindIncludeScreenshots(value: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[REWIND_INCLUDE_SCREENSHOTS] = value
            }
        }
    }

    /**
     * Observes the user's auto-event inference sensitivity, defaulting to medium when unset.
     *
     * Persisted as the enum name (`LOW`, `MEDIUM`, `HIGH`) so we can extend the set later
     * without renumbering existing values.
     */
    fun observeEventInferenceSensitivity(): Flow<String> =
        userPreferences.data.map { prefs ->
            prefs[EVENT_INFERENCE_SENSITIVITY] ?: "MEDIUM"
        }

    suspend fun getEventInferenceSensitivity(): String = observeEventInferenceSensitivity().first()

    suspend fun setEventInferenceSensitivity(sensitivity: String) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[EVENT_INFERENCE_SENSITIVITY] = sensitivity
            }
        }
    }

    /**
     * Whether the inference worker is allowed to call out to the on-device extractor for
     * naming. Off → every event uses the heuristic fallback name, no network call.
     */
    fun observeEventInferenceAiNamingEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[EVENT_INFERENCE_AI_NAMING_ENABLED] ?: true
        }

    suspend fun isEventInferenceAiNamingEnabled(): Boolean = observeEventInferenceAiNamingEnabled().first()

    suspend fun setEventInferenceAiNamingEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[EVENT_INFERENCE_AI_NAMING_ENABLED] = enabled
            }
        }
    }

    /**
     * Records the result of one event inference worker run. Atomic — the recent rolling
     * total is incremented inside the same `updateData` block as the per-run stats so two
     * concurrent worker runs (immediate + periodic) can't lose increments to a stale read.
     *
     * [errorKind] is the `EventInferenceFailure` enum name (or `null` on success); the
     * settings screen translates the kind to a localized string instead of showing raw
     * exception messages.
     */
    suspend fun recordEventInferenceRun(
        runAt: Instant,
        createdThisRun: Int,
        errorKind: String?,
    ) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[EVENT_INFERENCE_LAST_RUN_AT] = runAt.toEpochMilliseconds()
                this[EVENT_INFERENCE_LAST_CREATED_COUNT] = createdThisRun
                val previousRecent = this[EVENT_INFERENCE_RECENT_CREATED_COUNT] ?: 0
                this[EVENT_INFERENCE_RECENT_CREATED_COUNT] = previousRecent + createdThisRun
                if (errorKind != null) {
                    this[EVENT_INFERENCE_LAST_ERROR_KIND] = errorKind
                } else {
                    this.remove(EVENT_INFERENCE_LAST_ERROR_KIND)
                }
            }
        }
    }

    /**
     * Whether the user has turned on device calendar sync. When `false` the import worker
     * still gets scheduled, but its first action is to short-circuit out — that keeps the
     * worker registered so flipping the toggle on takes effect on the next periodic tick
     * without re-bootstrapping WorkManager.
     */
    fun observeDeviceCalendarSyncEnabled(): Flow<Boolean> =
        userPreferences.data.map { prefs ->
            prefs[DEVICE_CALENDAR_SYNC_ENABLED] ?: false
        }

    suspend fun isDeviceCalendarSyncEnabled(): Boolean = observeDeviceCalendarSyncEnabled().first()

    suspend fun setDeviceCalendarSyncEnabled(enabled: Boolean) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[DEVICE_CALENDAR_SYNC_ENABLED] = enabled
            }
        }
    }

    /**
     * The set of device calendar ids the user has opted into. Empty by default; the
     * settings overview surfaces an explicit "choose calendars" affordance when nothing's
     * been picked yet.
     */
    fun observeDeviceCalendarEnabledIds(): Flow<Set<String>> =
        userPreferences.data.map { prefs ->
            prefs[DEVICE_CALENDAR_ENABLED_IDS] ?: emptySet()
        }

    suspend fun getDeviceCalendarEnabledIds(): Set<String> = observeDeviceCalendarEnabledIds().first()

    suspend fun setDeviceCalendarEnabledIds(ids: Set<String>) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[DEVICE_CALENDAR_ENABLED_IDS] = ids
            }
        }
    }

    /**
     * Records the result of one calendar import worker run. [errorKind] is the
     * `CalendarImportFailure` enum name (or `null` on success); the settings screen
     * translates the kind to a localized string instead of showing raw exception
     * messages.
     */
    suspend fun recordDeviceCalendarSyncRun(
        runAt: Instant,
        created: Int,
        updated: Int,
        errorKind: String?,
    ) {
        userPreferences.updateData { preferences ->
            preferences.toMutablePreferences().apply {
                this[DEVICE_CALENDAR_SYNC_LAST_RUN_AT] = runAt.toEpochMilliseconds()
                this[DEVICE_CALENDAR_SYNC_LAST_CREATED_COUNT] = created
                this[DEVICE_CALENDAR_SYNC_LAST_UPDATED_COUNT] = updated
                if (errorKind != null) {
                    this[DEVICE_CALENDAR_SYNC_LAST_ERROR_KIND] = errorKind
                } else {
                    this.remove(DEVICE_CALENDAR_SYNC_LAST_ERROR_KIND)
                }
            }
        }
    }

    private fun millisToInstantOrDistantPast(millis: Long?): Instant =
        millis?.takeIf { it != 0L }?.let(Instant::fromEpochMilliseconds) ?: Instant.DISTANT_PAST
}
