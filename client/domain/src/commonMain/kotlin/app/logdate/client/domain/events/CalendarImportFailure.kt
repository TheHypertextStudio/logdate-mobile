package app.logdate.client.domain.events

/**
 * Coarse failure category for the device calendar import worker.
 *
 * Stored as the enum name by
 * [app.logdate.client.datastore.LogdatePreferencesDataSource.recordDeviceCalendarSyncRun] and
 * mapped to a localized string by the calendar sync settings screen, so the user sees
 * "Calendar permission needed" instead of a raw `SecurityException` message.
 */
enum class CalendarImportFailure {
    /** Background pipeline threw something we didn't categorize. Default fallback. */
    Unknown,

    /** The runtime calendar permission isn't granted. The user has to opt in. */
    PermissionDenied,

    /** The system content provider returned no usable calendars. */
    CalendarsUnavailable,

    /** Reading or writing event rows blew up. */
    PersistenceFailed,

    ;

    companion object {
        fun fromPreference(stored: String?): CalendarImportFailure? = stored?.let { value -> runCatching { valueOf(value) }.getOrNull() }
    }
}
