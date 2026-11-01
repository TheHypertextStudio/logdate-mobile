package app.logdate.client.sync

import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/**
 * A generic interface for syncing the client with the user's remote data.
 *
 * Implementations of this interface should be platform-specific.
 */
interface SyncManager {
    /**
     * Live snapshot of the sync pipeline state.
     *
     * Emits updates when sync starts, completes, or errors. The UI observes this to render a
     * persistent status banner: the value reflects whatever was true at the last sync settle
     * (plus live `isSyncing` while a run is in flight). Implementations that do not track sync
     * reactively return a flow that is updated on [getSyncStatus] or on each explicit sync call.
     */
    val syncStatusFlow: StateFlow<SyncStatus>

    /**
     * Begin syncing the client with the user's remote data.
     *
     * By default, this method does not start syncing immediately and instead syncs data
     * asynchronously when resources are available.
     *
     * @param startNow Whether to start syncing immediately. Note that this parameter may be ignored
     *                 by some implementations. False by default.
     */
    fun sync(startNow: Boolean = false)

    /** Requests a manual backup; Android waits for its scheduler to accept the request. */
    suspend fun requestBackup(allowMeteredMedia: Boolean = false) {
        require(!allowMeteredMedia) { "This sync manager does not support mobile data consent" }
        sync(startNow = true)
    }

    /**
     * Uploads all pending local changes to the cloud.
     * This includes journals, notes, associations, and media.
     */
    suspend fun uploadPendingChanges(): SyncResult

    /**
     * Downloads and applies all remote changes since the last sync.
     * This includes journals, notes, associations, and media.
     */
    suspend fun downloadRemoteChanges(): SyncResult

    /**
     * Syncs content (notes) specifically.
     * Uploads local changes and downloads remote changes.
     */
    suspend fun syncContent(): SyncResult

    /**
     * Syncs journal metadata specifically.
     * Uploads local changes and downloads remote changes.
     */
    suspend fun syncJournals(): SyncResult

    /**
     * Syncs journal-content associations specifically.
     * Uploads local changes and downloads remote changes.
     */
    suspend fun syncAssociations(): SyncResult

    /**
     * Syncs editor drafts for cross-device handoff.
     * Uploads local drafts and downloads drafts started on other devices,
     * enabling users to continue editing where they left off.
     */
    suspend fun syncDrafts(): SyncResult

    /**
     * Performs a full bidirectional sync of all data.
     * This is the most comprehensive sync operation.
     */
    suspend fun fullSync(): SyncResult

    /**
     * Gets the current sync status including last sync time and pending changes count.
     */
    suspend fun getSyncStatus(): SyncStatus

    /**
     * Live snapshot of entities that exhausted their retry budget. These are the writes the
     * UI should surface so the user can choose to retry or discard them. Always returns the
     * current full list — implementations may emit an empty list when nothing is broken.
     */
    fun observeDeadLetters(): Flow<List<SyncDeadLetterRecord>>

    /**
     * Re-enqueue a dead-lettered record for another sync attempt. The record is removed from
     * the dead-letter store regardless; the next sync pass picks up the freshly-queued
     * pending op.
     */
    suspend fun retryDeadLetter(id: String)

    /**
     * Drop a dead-lettered record without re-attempting it. Use when the user decides the
     * pending change is no longer wanted.
     */
    suspend fun discardDeadLetter(id: String)

    /**
     * Runs [block] with no sync in progress and none starting until it returns. Syncs asked for in
     * the meantime run afterwards, against whatever [block] changed -- such as the server the app
     * is connected to.
     */
    suspend fun <T> whilePaused(block: suspend () -> T): T = block()
}

/**
 * Result of a sync operation indicating success/failure and what was synced.
 */
data class SyncResult(
    val success: Boolean,
    val uploadedItems: Int = 0,
    val downloadedItems: Int = 0,
    val conflictsResolved: Int = 0,
    val errors: List<SyncError> = emptyList(),
    val lastSyncTime: Instant? = null,
    /** Another bounded pass is required; this is progress, not an error. */
    val hasMorePending: Boolean = false,
    /** Every remote page was fetched and retained; applying individual records may still fail. */
    val remoteInventoryComplete: Boolean = false,
    /** Set only by a successful full run whose entry and draft phases retained this scope. */
    val recoveredMediaScope: app.logdate.client.sync.metadata.UploadScope? = null,
)

/**
 * Current sync status of the client.
 */
data class SyncStatus(
    val isEnabled: Boolean,
    val lastSyncTime: Instant?,
    val pendingUploads: Int,
    val isSyncing: Boolean,
    val hasErrors: Boolean,
    val lastError: SyncError? = null,
    val pausedReason: SyncPausedReason? = null,
    /**
     * How many items this run set out to upload, or null when no run has started one.
     *
     * [pendingUploads] is a live count of what is left, which only shrinks and never says how far
     * along the run is. Progress needs a denominator: on a first sync of several hundred entries,
     * "45 of 630" is the difference between waiting a moment and waiting an hour.
     */
    val totalForRun: Int? = null,
    /** How many of [totalForRun] this run has finished. */
    val completedInRun: Int = 0,
    /** How many entries have a conflict waiting for the user to resolve in Sync issues. */
    val conflictCount: Int = 0,
    /**
     * Records on the server this device cannot read and has no local copy to repair from --
     * nothing here can fix them; they need either the writing device to come back, or this
     * device to recover the identity that wrote them.
     */
    val unreadableCloudCount: Int = 0,
    /** Whether the local pending queue could be read for this status snapshot. */
    val queueReadable: Boolean = true,
    /** State of a requested Android backup before the sync engine itself starts. */
    val requestState: BackupRequestState = BackupRequestState.NONE,
    /** Automatic background work may wait on metered data; manual backup remains available. */
    val backgroundWorkLimited: Boolean = false,
    /** Records and attachments not yet recovered, independent of upload progress. */
    val pendingDownloads: Int = 0,
    /** Retained metadata issues without saved candidates; never described as divergent writing. */
    val earlierConflictCount: Int = 0,
)

enum class BackupRequestState {
    NONE,
    QUEUED,
    RETRYING,
    RUNNING,
    FAILED,
    COMPLETED,
}

/**
 * Why the backup is not making progress right now.
 *
 * This is distinct from [SyncError]: an error means an attempt was made and failed, and a
 * retry is worth trying. A pause means no attempt can even be made until something outside
 * the app changes, so retrying in a loop only burns battery and tells the user nothing.
 */
enum class SyncPausedReason {
    /** There is no signed-in account to back up to. */
    NOT_SIGNED_IN,

    /** The device has no usable connection. Clears itself once it is back online. */
    OFFLINE,

    /**
     * The platform is withholding background network from LogDate, so queued work is never
     * allowed to run. Only the user can clear this, by allowing background data for LogDate.
     */
    BACKGROUND_DATA_OFF,

    /**
     * Photos and videos are held back until the device is on Wi-Fi. Text still syncs, so this is
     * a partial pause: it is reported only once something has actually been held back, never as a
     * standing warning about being on cellular.
     */
    MEDIA_WAITING_FOR_WIFI,

    /**
     * This device has no identity key, but the account already has data in the cloud -- almost
     * always because the key was lost (a reinstall, a restore onto a different device) rather
     * than this being a genuinely new account. Minting a new key here would encrypt everything
     * from now on under a key that can never read what came before, so sync stops instead and
     * waits for the recovery phrase to be entered again.
     */
    NEEDS_RECOVERY_PHRASE,

    /**
     * A linked watch has no session or identity key from its phone yet, or one that no longer
     * matches its account. It never makes its own; sync waits until the phone links it again.
     */
    NEEDS_DEVICE_LINK,
    LOCAL_STORAGE_UNAVAILABLE,
}

/**
 * Represents a sync error that occurred during a sync operation.
 */
data class SyncError(
    val type: SyncErrorType,
    val message: String,
    val cause: Throwable? = null,
    val retryable: Boolean = true,
)

/**
 * Types of sync errors that can occur.
 */
enum class SyncErrorType {
    NETWORK_ERROR,
    AUTHENTICATION_ERROR,
    SERVER_ERROR,
    CONFLICT_ERROR,
    KEY_RECOVERY_ERROR,
    MEDIA_TOO_LARGE_ERROR,
    STORAGE_ERROR,

    /** The server stores the account in a way this device can't sync, so nothing syncs. */
    ACCOUNT_STORAGE_ERROR,
    UNKNOWN_ERROR,
}
