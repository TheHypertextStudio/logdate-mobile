package app.logdate.client.datastore.featureflags

/**
 * A feature that can be turned on or off at runtime.
 *
 * Every commit lands on `main`, so a feature that is not ready for users has to be invisible to
 * them rather than parked on a branch. A flag is how that is done: the code ships, the flag stays
 * off, and turning it on is a decision rather than a merge.
 *
 * A flag is a temporary thing. Once a feature ships to everyone, its flag and every branch on it
 * should be deleted -- a flag that nobody will ever flip is just a second way to read a constant.
 *
 * @property key The preference key the flag is stored under. Changing it silently resets everyone
 *   who had already set the flag, so treat it as permanent once released.
 * @property defaultEnabled Whether the feature is on for someone who has never set it. Incomplete
 *   work defaults to `false`; a flag that exists only as a kill switch for shipped behavior
 *   defaults to `true`.
 */
enum class FeatureFlag(
    val key: String,
    val defaultEnabled: Boolean,
    val availableForLaunch: Boolean = true,
) {
    /**
     * The media Library browsing experience.
     *
     * On by default for the first public Android release.
     */
    LIBRARY(key = "library_enabled", defaultEnabled = true),

    /** Opt-in Places browsing presentation; independent of location collection and Rewind maps. */
    PLACES_BETA(key = "places_beta_enabled", defaultEnabled = false),

    /** Immersive entry overview and block focus, with a legacy presentation rollback. */
    IMMERSIVE_ENTRY_EDITOR(key = "immersive_entry_editor_enabled", defaultEnabled = true),

    /** External identity sign-in; enable only after native journal acceptance. */
    BLUESKY_SIGN_IN(key = "bluesky_sign_in_enabled", defaultEnabled = false),

    /** Optional account authorization; enable only after connection lifecycle acceptance. */
    ATPROTO_CONNECTION(key = "atproto_connection_enabled", defaultEnabled = false),

    /** Stop quiet phone recordings and preserve them as drafts. */
    QUIET_SAVE(key = "quiet_save_available", defaultEnabled = false),

    /** Original ink blocks, retained through drafts, saved entries, archives and sync. */
    HANDWRITING_ENTRIES(key = "handwriting_entries_enabled", defaultEnabled = true),

    /**
     * Automatic event detection and the Events surface.
     *
     * Deferred from the first public Android release, including for previously enabled devices.
     */
    EVENTS(key = "events_enabled", defaultEnabled = false, availableForLaunch = false),

    /**
     * The People slice.
     *
     * Deferred from the first public Android release, including for previously enabled devices.
     */
    PEOPLE(key = "people_enabled", defaultEnabled = false, availableForLaunch = false),

    /**
     * The forgiving campfire streak that replaces the consecutive-day counter.
     *
     * On by default: every surface that shows a streak renders the campfire. The flag stays as a
     * switch back to the old counter until that counter's code is deleted, and both go together.
     */
    CAMPFIRE_STREAKS(key = "campfire_streaks_enabled", defaultEnabled = true),

    /** Combine journal membership while retaining notes and media. */
    JOURNAL_MERGE(key = "journal_merge_enabled", defaultEnabled = true),

    /** Shared adaptive Home presentation; retain the preference as a presentation rollback switch. */
    HOME_WORKSPACE_V2(key = "home_workspace_v2_enabled", defaultEnabled = true),

    /** Human history browsing and encrypted sync; complete-day recording still requires explicit opt-in. */
    HUMAN_LOCATION_HISTORY(key = "human_location_history_enabled", defaultEnabled = true),

    /**
     * New entry and Record voice tiles in Quick Settings.
     *
     * Off until the tiles are verified on managed devices. While off, the tile services stay disabled
     * in the manifest, so the tiles do not appear in the tile picker at all.
     */
    QUICK_SETTINGS_TILES(key = "quick_settings_tiles_enabled", defaultEnabled = false),

    /**
     * Recording, export, restore, and sync shown as promoted Live Updates in the status bar and on the
     * lock screen. Off keeps the existing ongoing notifications.
     */
    LIVE_UPDATES(key = "live_updates_enabled", defaultEnabled = false),

    /** A floating recorder bubble that keeps a voice entry recording while another app is open. */
    RECORDING_BUBBLE(key = "recording_bubble_enabled", defaultEnabled = false),

    /**
     * LogDate's AppFunctions for on-device assistants: capture always, reading only with consent and
     * while unlocked.
     */
    APP_FUNCTIONS(key = "app_functions_enabled", defaultEnabled = false),

    /** Remote recall and reviewed capture; unavailable until the full connection is accepted. */
    CONNECTED_ASSISTANTS(key = "connected_assistants_enabled", defaultEnabled = false, availableForLaunch = false),

    /** A capture-only widget for the lock screen and hub that never shows journal content. */
    LOCK_SCREEN_CAPTURE_WIDGET(key = "lock_screen_capture_widget_enabled", defaultEnabled = false),

    /** Write and Record actions on capture nudges, so an entry can be written from the notification. */
    NOTIFICATION_QUICK_REPLY(key = "notification_quick_reply_enabled", defaultEnabled = false),

    /** Android Handoff for the editor, so an unfinished entry continues on another Android device. */
    EDITOR_HANDOFF(key = "editor_handoff_enabled", defaultEnabled = false),

    /**
     * The redesigned watch experience and its phone companion: the watch app, and the phone
     * screens that set up and manage the watch.
     *
     * Off by default while the redesign is built. Once it ships to everyone, delete this flag
     * together with every branch that reads it and the watch screens it replaces.
     */
    WATCH_EXPERIENCE_V2(key = "watch_experience_v2_enabled", defaultEnabled = false),

    /**
     * Activity awareness: the phone and the watch notice when someone is moving, in a vehicle or
     * asleep. Reminders wait while an activity is under way, and the end of one can be the day's
     * reminder.
     *
     * Off by default while it is built. The phone mirrors it to the watch.
     */
    ACTIVITY_AWARENESS(key = "activity_awareness_enabled", defaultEnabled = false),

    /**
     * Sync through the account's sealed personal space, as LogDate schema records, instead of the
     * per-kind sync endpoints.
     *
     * A device only uses the personal space when the server offers it and the account is stored
     * there.
     */
    PERSONAL_SPACE(key = "personal_space_enabled", defaultEnabled = true),

    /**
     * Move an existing journal from the per-kind sync endpoints into the account's sealed personal
     * space, from this device, once it has synced everything; and show the move in Data settings.
     *
     * It takes effect only together with [PERSONAL_SPACE], since a moved journal syncs through its
     * personal space, and only on a server that offers the move.
     */
    PERSONAL_SPACE_MIGRATION(key = "personal_space_migration_enabled", defaultEnabled = true),

    /**
     * Confirming the recovery phrase also registers a key derived from it on the account's identity,
     * so the phrase can move the account to another server. It is offered only when the connected
     * server supports it and this device can confirm with a passkey.
     */
    IDENTITY_RECOVERY_KEY(key = "identity_recovery_key_enabled", defaultEnabled = true),
    ;

    companion object {
        /**
         * Finds a flag by its stored [key], or `null` if no flag owns it.
         *
         * Useful for debug tooling that reads flags back out of storage; production code should
         * name the flag it means.
         */
        fun forKey(key: String): FeatureFlag? = entries.firstOrNull { it.key == key }
    }
}
