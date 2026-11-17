package app.logdate.client.networking

import kotlinx.coroutines.flow.Flow

/**
 * Provides the current data usage policy based on system-level network state.
 *
 * This policy reacts to the platform's Data Saver setting and connection type
 * to determine how aggressively the app should use network resources.
 * No app-level preferences are involved — the system settings are the single
 * source of truth.
 */
interface DataUsagePolicy {
    /**
     * A flow that emits the current [DataUsageMode] whenever the underlying
     * network state changes (e.g., Data Saver toggled, WiFi ↔ cellular transition).
     */
    val policy: Flow<DataUsageMode>

    /**
     * Returns the current [DataUsageMode] as a snapshot.
     */
    suspend fun currentMode(): DataUsageMode

    /**
     * Why the platform is currently withholding network access, if it is.
     *
     * [currentMode] collapses "no connection" and "background data is blocked" into
     * [DataUsageMode.Restricted], which is the right call for deciding *whether* to make a
     * request but useless for telling the user *why* nothing is happening. The two have very
     * different answers: one resolves itself, the other needs a setting changed.
     */
    suspend fun currentRestriction(): DataRestriction
}

/**
 * The platform-level reason network work cannot proceed.
 */
enum class DataRestriction {
    /** Network work may proceed. */
    NONE,

    /** No usable connection. Resolves on its own once the device is back online. */
    OFFLINE,

    /**
     * The platform refuses this app background network - either system-wide Data Saver, or
     * the app sitting on the per-app restrict-background list. Retrying never clears it; the
     * user has to allow background data for LogDate.
     */
    BACKGROUND_DATA_BLOCKED,
}

/**
 * Represents the app's data usage posture, derived from system network state.
 */
sealed interface DataUsageMode {
    /**
     * WiFi/Ethernet with Data Saver off. All operations allowed.
     */
    data object Unrestricted : DataUsageMode

    /**
     * Cellular with Data Saver off. Metadata syncs; media deferred to WiFi.
     */
    data object Conservative : DataUsageMode

    /**
     * Data Saver on, or no network connection. Only essential operations.
     */
    data object Restricted : DataUsageMode
}

/** True only on unrestricted (WiFi) — media is deferred to WiFi even on cellular. */
fun DataUsageMode.shouldSyncMedia(): Boolean = this is DataUsageMode.Unrestricted

/** True for Unrestricted and Conservative — text/metadata sync proceeds on cellular. */
fun DataUsageMode.shouldSyncMetadata(): Boolean = this is DataUsageMode.Unrestricted || this is DataUsageMode.Conservative

/** True only on Unrestricted — full resolution images. */
fun DataUsageMode.shouldLoadFullResImages(): Boolean = this is DataUsageMode.Unrestricted

/** True on Conservative — fetch images at reduced resolution. */
fun DataUsageMode.shouldLoadReducedImages(): Boolean = this is DataUsageMode.Conservative

/** True only on Unrestricted — prefetch nearby images. */
fun DataUsageMode.shouldPrefetchImages(): Boolean = this is DataUsageMode.Unrestricted

/** True for Unrestricted and Conservative — AI calls allowed on cellular without Data Saver. */
fun DataUsageMode.shouldAllowAICalls(): Boolean = this is DataUsageMode.Unrestricted || this is DataUsageMode.Conservative
