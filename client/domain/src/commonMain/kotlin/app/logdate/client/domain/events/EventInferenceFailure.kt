package app.logdate.client.domain.events

/**
 * Coarse failure category for the on-device event inference worker.
 *
 * Stored as the enum name by
 * [app.logdate.client.datastore.LogdatePreferencesDataSource.recordEventInferenceRun] and
 * mapped to a localized string by the auto-events settings screen, so we never persist
 * raw exception messages (which would leak developer-language strings or PII into a
 * surface the user actually reads). Add new variants here when a failure mode is
 * stable enough to deserve its own user-facing line.
 */
enum class EventInferenceFailure {
    /** Background pipeline threw something we didn't categorize. Default fallback. */
    Unknown,

    /** A required reader (location stops, media, notes) was unavailable or empty. */
    SignalUnavailable,

    /** The naming extractor returned an error (model unreachable, parse failed, etc.). */
    NamingFailed,

    /** Persisting the new event row to Room failed. */
    PersistenceFailed,

    ;

    companion object {
        fun fromPreference(stored: String?): EventInferenceFailure? = stored?.let { value -> runCatching { valueOf(value) }.getOrNull() }
    }
}
