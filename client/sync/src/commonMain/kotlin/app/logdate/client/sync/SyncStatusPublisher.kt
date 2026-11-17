package app.logdate.client.sync

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.DataRestriction
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.shouldSyncMedia
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.conflict.SyncConflictStore
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.IdentityRecoveryNeededStore
import app.logdate.client.sync.metadata.LastSyncErrorStore
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UnreadableCloudRecordStore
import app.logdate.shared.model.CloudQuotaManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Owns [DefaultSyncManager]'s [SyncStatus] snapshot: republishing it on every state/error/session
 * transition, tracking the current upload run's progress, and deciding [SyncPausedReason].
 *
 * @param syncStateFlow Read-only view of state [DefaultSyncManager] itself still writes directly
 *   (it gates control flow there -- `syncMutex`-scoped state transitions), so this class only
 *   ever reads it.
 * @param lastErrorFlow Read *and* written here: every other write happens directly on
 *   [DefaultSyncManager] (clearing/setting it around an upload or download run), but mapping an
 *   exception into a [SyncResult] -- [handleSyncException]/[handleCloudApiError] -- is itself
 *   part of deciding what the published status's `lastError` should say, so it lives with the
 *   rest of that decision instead of splitting the two.
 * @param linkedDevice,identityKeyMissing A linked watch never signs in or creates a key of its own, so
 *   a missing session or key is [SyncPausedReason.NEEDS_DEVICE_LINK] on it, and a recovery phrase is
 *   never asked for.
 * @param latestSyncTime,isEnabled Reused from [DefaultSyncManager] rather than duplicated: the
 *   former is independently useful for a [SyncResult]'s own `lastSyncTime`, and the latter is a
 *   plain field with no reason to move.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SyncStatusPublisher(
    private val sessionStorage: SessionStorage,
    private val syncMetadataService: SyncMetadataService,
    private val dataUsagePolicy: DataUsagePolicy,
    private val cloudQuotaManager: CloudQuotaManager?,
    private val syncStateFlow: StateFlow<SyncState>,
    private val lastErrorFlow: MutableStateFlow<SyncError?>,
    private val syncScope: CoroutineScope,
    private val lastErrorStore: LastSyncErrorStore,
    private val latestSyncTime: suspend () -> Instant?,
    private val isEnabled: () -> Boolean,
    private val conflictStore: SyncConflictStore,
    private val identityRecoveryNeededStore: IdentityRecoveryNeededStore,
    private val unreadableCloudRecordStore: UnreadableCloudRecordStore,
    private val pendingRecoveryCount: suspend () -> Int = { 0 },
    private val linkedDevice: Boolean = false,
    private val identityKeyMissing: suspend () -> Boolean = { false },
    private val attentionRecoveryCount: suspend () -> Int = { 0 },
    private val readinessGate: SyncReadinessGate = SyncReadinessGate(),
) {
    /**
     * Whether the last upload pass held a photo or video back for want of Wi-Fi.
     *
     * Reset at the start of each upload pass so the status reflects the current run rather than
     * a deferral the device has long since worked through.
     */
    private var mediaDeferredForNetwork = false
    private val queueObservationFailed = MutableStateFlow(false)
    private val publicationMutex = Mutex()

    /** What the current upload run set out to do, and how far through it is. See [SyncStatus]. */
    var runTotal: Int? = null
        private set
    var runCompleted = 0
        private set

    private val _syncStatusFlow =
        MutableStateFlow(
            SyncStatus(
                isEnabled = true,
                lastSyncTime = null,
                pendingUploads = 0,
                isSyncing = false,
                hasErrors = false,
            ),
        )
    val syncStatusFlow: StateFlow<SyncStatus> = _syncStatusFlow.asStateFlow()

    init {
        // The last error survives a restart. It used to live only in memory, and Android restarts
        // a backgrounded app freely -- so after each restart a sync that kept failing looked like
        // a calm backlog. Restore it unless this run already has one, then save every change.
        syncScope.launch {
            val saved =
                runCatchingUnlessCancelled { lastErrorStore.load() }
                    .onFailure { Napier.e("Could not read the last sync error: ${it::class.simpleName}") }
                    .getOrNull()
            if (saved != null) lastErrorFlow.compareAndSet(null, saved)
            lastErrorFlow.collect { error ->
                runCatchingUnlessCancelled { lastErrorStore.save(error) }
                    .onFailure { Napier.e("Could not save the last sync error: ${it::class.simpleName}") }
            }
        }
        // Republish status on each internal state/error transition so observers don't have to poll.
        syncScope.launch {
            combine(syncStateFlow, lastErrorFlow) { state, error -> state to error }
                .collect { publish() }
        }
        syncScope.launch {
            dataUsagePolicy.policy.collect { publish() }
        }
        syncScope.launch {
            readinessGate.reason.collect { publish() }
        }
        // Republish whenever the session changes — sign-in flips isEnabled true, sign-out flips
        // it false, and the UI needs to react without waiting for the next sync transition.
        syncScope.launch {
            sessionStorage.getSessionFlow().collect { publish() }
        }
        // Follow the queue itself between those transitions. Entries written while no run is in
        // flight, or uploads that settle outside a tracked run, otherwise left the published count
        // stale until the next sync started or stopped. Restarted per session because the queue is
        // scoped to the signed-in owner.
        syncScope.launch {
            sessionStorage
                .getSessionFlow()
                .flatMapLatest { syncMetadataService.observePendingCount() }
                .catch { error ->
                    Napier.e("Could not follow the pending upload count")
                    queueObservationFailed.value = true
                    _syncStatusFlow.update { it.copy(queueReadable = false) }
                }.collect { count ->
                    queueObservationFailed.value = false
                    _syncStatusFlow.update { it.copy(pendingUploads = count) }
                }
        }
    }

    /**
     * Snapshot the combined state into [syncStatusFlow].
     *
     * `isEnabled` here is the *effective* state the UI cares about: a queue can only matter
     * when there's a session to drain it to. Without a session, we report disabled regardless
     * of the local preference.
     */
    suspend fun publish() =
        publicationMutex.withLock {
            val authenticated = sessionStorage.getSession() != null
            val pendingCountResult =
                runCatchingUnlessCancelled { syncMetadataService.getPendingCount() }
                    .onFailure { Napier.e("Could not read the pending upload count: ${it::class.simpleName}") }
            val recovery = runCatchingUnlessCancelled { pendingRecoveryCount() }
            val attention = runCatchingUnlessCancelled { attentionRecoveryCount() }.getOrDefault(0)
            val review = runCatchingUnlessCancelled { currentConflictReview() }
            _syncStatusFlow.value =
                SyncStatus(
                    isEnabled = authenticated && isEnabled(),
                    lastSyncTime = latestSyncTime(),
                    pendingUploads = pendingCountResult.getOrDefault(0),
                    queueReadable = pendingCountResult.isSuccess && recovery.isSuccess && review.isSuccess && !queueObservationFailed.value,
                    pendingDownloads = recovery.getOrDefault(0),
                    backgroundWorkLimited = backgroundWorkLimited(),
                    isSyncing = syncStateFlow.value is SyncState.Syncing,
                    hasErrors = lastErrorFlow.value != null || attention > 0 || recovery.isFailure || review.isFailure,
                    lastError = lastErrorFlow.value,
                    pausedReason = currentPausedReason(authenticated),
                    totalForRun = runTotal,
                    completedInRun = runCompleted,
                    conflictCount = review.getOrNull()?.edits?.size ?: 0,
                    earlierConflictCount = review.getOrNull()?.earlierIssues ?: 0,
                    unreadableCloudCount = runCatchingUnlessCancelled { unreadableCloudRecordStore.count() }.getOrDefault(0),
                )
        }

    private suspend fun currentConflictReview(): app.logdate.client.sync.conflict.SyncReviewInventory {
        val scope =
            sessionStorage.getOriginBoundSession()?.let {
                app.logdate.client.sync.metadata
                    .UploadScope(it.session.accountId, it.origin)
            }
        return app.logdate.client.sync.conflict
            .syncReviewInventory(conflictStore.list(), scope)
    }

    /**
     * Starts a new top-level upload run: forgets whatever the previous run's counters said and
     * records what *this* run is setting out to do, then republishes so observers see the reset
     * immediately rather than a stale carryover.
     *
     * Must be called at the top of every public entry point that can reach [recordProgress] --
     * not just `uploadPendingChanges`, but also the opportunistic, single-entity-type entry
     * points (`syncContent`, `syncJournals`, `syncAssociations`, `syncDrafts`) that repositories
     * call right after a local write. Without this, an opportunistic sync of one item run right
     * after an earlier five-item run left [runTotal] at the stale value of 5 while [runCompleted]
     * climbed past it (e.g. reporting "6 of 5" instead of "1 of 1").
     */
    suspend fun beginRun(total: Int?) {
        runTotal = total
        runCompleted = 0
        publish()
    }

    /** [beginRun] scoped to how many of [entityType] are currently pending. */
    suspend fun beginRunForPending(entityType: EntityType) {
        beginRun(runCatchingUnlessCancelled { syncMetadataService.getPendingUploads(entityType).size }.getOrNull()?.takeIf { it > 0 })
    }

    /**
     * Advances the upload run's progress by [count] items and republishes status immediately.
     *
     * Called from inside each upload loop as items actually finish, rather than only once at the
     * start and end of a run -- without this, [runCompleted] sat at zero for the whole run and
     * jumped straight to the total, so a determinate progress indicator never actually looked
     * like it was moving.
     *
     * Deliberately does *not* go through [publish]: that re-runs
     * [SyncMetadataService.getPendingCount], which takes a mutex and loops every [EntityType]
     * doing a promotion check before its final COUNT query. Calling that once per uploaded item
     * turned a 500-note backup into roughly 500x that overhead for no benefit -- the pending
     * badge doesn't need to be exactly current between every single item, only the run counters
     * ([runTotal]/[runCompleted]) do. This reuses the rest of the last-published snapshot and
     * only touches the two fields that actually changed; a fresh, fully-recomputed status is
     * still published on every state transition (run start/end) via [publish].
     */
    fun recordProgress(count: Int = 1) {
        runCompleted += count
        _syncStatusFlow.value =
            _syncStatusFlow.value.copy(
                totalForRun = runTotal,
                completedInRun = runCompleted,
            )
    }

    /** Overrides [runCompleted] directly once a run's true final count is known. */
    fun setRunCompleted(count: Int) {
        runCompleted = count
    }

    fun setMediaDeferredForNetwork(deferred: Boolean) {
        mediaDeferredForNetwork = deferred
    }

    /**
     * Why the backup cannot progress right now, or null if nothing is holding it back.
     *
     * Reported whether or not anything is queued. Background data limits are reported
     * separately because an open app can still request a manual backup.
     */
    suspend fun currentPausedReason(authenticated: Boolean): SyncPausedReason? {
        if (!authenticated) return if (linkedDevice) SyncPausedReason.NEEDS_DEVICE_LINK else SyncPausedReason.NOT_SIGNED_IN
        if (linkedDevice && (identityKeyMissing() || identityRecoveryNeededStore.isNeeded())) return SyncPausedReason.NEEDS_DEVICE_LINK
        if (identityRecoveryNeededStore.isNeeded()) return SyncPausedReason.NEEDS_RECOVERY_PHRASE
        readinessGate.reason.value?.let { return it }
        val restriction = runCatchingUnlessCancelled { dataUsagePolicy.currentRestriction() }.getOrNull()
        return when (restriction) {
            DataRestriction.OFFLINE -> {
                SyncPausedReason.OFFLINE
            }

            DataRestriction.BACKGROUND_DATA_BLOCKED, DataRestriction.NONE, null -> {
                // Only after something was actually held back - being on cellular with nothing
                // waiting is not a pause, and saying so would train the user to ignore the line.
                SyncPausedReason.MEDIA_WAITING_FOR_WIFI.takeIf {
                    mediaDeferredForNetwork && !dataUsagePolicy.currentMode().shouldSyncMedia()
                }
            }
        }
    }

    suspend fun backgroundWorkLimited(): Boolean =
        runCatchingUnlessCancelled { dataUsagePolicy.currentRestriction() == DataRestriction.BACKGROUND_DATA_BLOCKED }
            .getOrDefault(false)

    fun isQueueObservationHealthy(): Boolean = !queueObservationFailed.value

    suspend fun refreshObservedQuotaFromServer(reason: String) {
        val manager = cloudQuotaManager ?: return
        runCatchingUnlessCancelled { manager.syncWithServer() }
            .onFailure { Napier.w("Failed to refresh quota: ${it::class.simpleName}") }
    }

    /**
     * Helper to handle sync exceptions consistently. The error keeps the exception's own message,
     * so the person and the logs see why [operation] failed rather than only that it did.
     */
    fun handleSyncException(
        e: Exception,
        operation: String,
    ): SyncResult {
        if (e is kotlinx.coroutines.CancellationException) throw e
        readinessGate.pause(e)
        val error =
            SyncError(
                type = SyncErrorType.UNKNOWN_ERROR,
                message = "Synchronization failed: ${e.message ?: e::class.simpleName}",
                cause = e,
            )
        lastErrorFlow.value = error
        Napier.e("Synchronization failed ($operation)", e)
        return SyncResult(success = false, errors = listOf(error))
    }

    /**
     * Helper to handle CloudApiException consistently.
     * Distinguishes 401 Unauthorized errors from other server errors.
     */
    fun handleCloudApiError(e: CloudApiException): SyncResult {
        Napier.e("Sync request failed")
        val errorType =
            if (e.statusCode == 401) {
                SyncErrorType.AUTHENTICATION_ERROR
            } else {
                SyncErrorType.SERVER_ERROR
            }
        return SyncResult(
            success = false,
            errors =
                listOf(
                    SyncError(errorType, errorType.name),
                ),
        )
    }
}
