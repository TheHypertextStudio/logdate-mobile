package app.logdate.client.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.config.LogDateConfigRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * A platform-specific implementation of [SyncManager] for Android.
 *
 * This relies on the Android WorkManager API to schedule background work
 * and delegates core sync functionality to DefaultSyncManager.
 *
 * Features:
 * - Automatic background sync enable/disable based on authentication state and user preference
 * - Periodic background sync every 15 minutes (minimum allowed by Android)
 * - Immediate sync for user-triggered actions
 * - Network-aware scheduling (WiFi preferred, but cellular allowed)
 * - Battery optimization aware
 * - Exponential backoff on failures
 * - Automatic sync retry on network restoration
 *
 * Background sync is automatically managed:
 * - When user signs in and background sync is enabled in settings, periodic sync is enabled
 * - When user signs out or disables background sync, periodic sync is disabled
 * - No UI code needs to manually schedule WorkManager sync
 * - Network transitions (offline→online) automatically trigger sync retry if needed
 */
@OptIn(FlowPreview::class)
class AndroidSyncManager(
    private val applicationContext: Context,
    private val defaultSyncManager: DefaultSyncManager,
    private val sessionStorage: SessionStorage,
    private val preferencesDataSource: LogdatePreferencesDataSource,
    private val networkMonitor: NetworkAvailabilityMonitor,
    private val dataUsagePolicy: DataUsagePolicy,
    private val configRepository: LogDateConfigRepository,
) : SyncManager {
    private val workManager = WorkManager.getInstance(applicationContext)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)
    private val immediateScheduleMutex = Mutex()
    private val requestedBackupState =
        workManager
            .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
            .map { work -> backupRequestState(work.map { BackupWorkSnapshot(it.state, it.runAttemptCount) }) }
            .stateIn(scope, SharingStarted.Eagerly, BackupRequestState.NONE)

    init {
        // Keep one owner for periodic scheduling. Separate auth and policy collectors used to
        // replace the same work twice at startup and could schedule again after sign-out.
        scope.launch {
            val targets =
                combine(
                    sessionStorage.getSessionFlow(),
                    configRepository.backendUrl,
                    dataUsagePolicy.policy,
                ) { _, origin, mode -> authenticatedScheduleTarget(origin, mode) }
                    .filterNotNull()
                    .retryWhen { failure, attempt ->
                        if (failure is CancellationException) throw failure
                        Napier.w("Authenticated recovery scheduling is waiting for session storage")
                        delay((1_000L shl attempt.coerceAtMost(5).toInt()).coerceAtMost(30_000L))
                        true
                    }
            PeriodicSyncScheduleController(
                isCurrent = ::isAuthenticatedScheduleCurrent,
                apply = ::applyAuthenticatedSchedule,
            ).collect(targets)
        }
        // Observe network state and trigger sync retry on network restoration
        scope.launch {
            var lastNetworkState: NetworkState? = null
            networkMonitor
                .observeNetwork()
                .debounce(2000) // Wait 2 seconds for network to stabilize
                .distinctUntilChanged()
                .collect { currentState ->
                    // Check if transitioned from offline to online
                    if (lastNetworkState is NetworkState.NotConnected &&
                        currentState is NetworkState.Connected
                    ) {
                        handleNetworkRestored()
                    }
                    lastNetworkState = currentState
                }
        }
    }

    private suspend fun applyAuthenticatedSchedule(change: PeriodicSyncScheduleChange) {
        when (change) {
            is PeriodicSyncScheduleChange.Enable -> {
                setupPeriodicSync(change.requirement)
                resumeAuthenticatedSync(ownerChanged = change.ownerChanged)
            }

            PeriodicSyncScheduleChange.ResumeOwner,
            PeriodicSyncScheduleChange.ResumeMedia,
            -> {
                resumeAuthenticatedSync(ownerChanged = change == PeriodicSyncScheduleChange.ResumeOwner)
            }

            PeriodicSyncScheduleChange.Disable -> {
                disableBackgroundSync()
            }

            PeriodicSyncScheduleChange.Unchanged -> {
                Unit
            }
        }
    }

    private suspend fun resumeAuthenticatedSync(ownerChanged: Boolean) {
        val target = requireNotNull(authenticatedScheduleTarget(configRepository.getCurrentBackendUrl(), dataUsagePolicy.currentMode()))
        check(target.owner != null && isAuthenticatedScheduleCurrent(target)) { "Recovery scope changed" }
        if (ownerChanged) {
            // Retire obsolete work before waiting for a phase lock held by its running worker.
            // A dependent request would inherit that worker's authentication failure or backoff.
            immediateScheduleMutex.withLock {
                check(isAuthenticatedScheduleCurrent(target)) { "Recovery scope changed" }
                workManager.cancelUniqueWork(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC).await()
            }
        }
        if (target.mode == DataUsageMode.Unrestricted) {
            resumeMediaReadiness(
                work =
                    workManager
                        .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
                        .map { mediaReadinessSnapshot(it, target.owner) },
                isCurrent = { isAuthenticatedScheduleCurrent(target) },
                replace = { expected ->
                    // Releasing queue backoff may wait for a writer's phase lock. Never hold the
                    // scheduling mutex while doing that, or a consented successor could deadlock.
                    defaultSyncManager.releaseUploadBackoff()
                    immediateScheduleMutex.withLock {
                        check(isAuthenticatedScheduleCurrent(target)) { "Recovery scope changed" }
                        val latest = workManager.getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC).first()
                        check(isAuthenticatedScheduleCurrent(target)) { "Recovery scope changed" }
                        if (mediaReadinessSnapshot(latest, target.owner) != expected) {
                            false
                        } else {
                            scheduleImmediateSync(policy = ExistingWorkPolicy.REPLACE).await()
                            true
                        }
                    }
                },
            )
            return
        }
        defaultSyncManager.releaseUploadBackoff()
        check(isAuthenticatedScheduleCurrent(target)) { "Recovery scope changed" }
        resumeScheduledSync(ownerChanged = ownerChanged).await()
    }

    private fun mediaReadinessSnapshot(
        work: List<WorkInfo>,
        owner: UploadScope,
    ): List<MediaReadinessWork> =
        work
            .map {
                MediaReadinessWork(
                    id = "${it.id}:${it.generation}",
                    state = it.state,
                    hasMobileDataConsent = MOBILE_DATA_SYNC_TAG in it.tags,
                    recoveredAllMedia = completedMediaRecoveryScope(it.outputData) == owner,
                )
            }.sortedBy { it.id }

    private suspend fun authenticatedScheduleTarget(
        origin: String,
        mode: DataUsageMode,
    ): PeriodicSyncScheduleTarget? {
        if (configRepository.getCurrentBackendUrl() != origin) return null
        // A server change can retain identical credentials and suppress the session-flow emission.
        if (sessionStorage.getOriginBoundSession() == null) sessionStorage.hasValidSession()
        if (configRepository.getCurrentBackendUrl() != origin) return null
        val session = sessionStorage.getOriginBoundSession()?.takeIf { it.origin == origin }
        val owner = session?.let { UploadScope(it.session.accountId, it.origin) }
        return PeriodicSyncScheduleTarget(mode, owner, origin)
    }

    private suspend fun isAuthenticatedScheduleCurrent(target: PeriodicSyncScheduleTarget): Boolean {
        if (configRepository.getCurrentBackendUrl() != target.origin) return false
        if (dataUsagePolicy.currentMode() != target.mode) return false
        val current = sessionStorage.getOriginBoundSession()
        val owner = current?.let { UploadScope(it.session.accountId, it.origin) }
        return owner == target.owner &&
            sessionStorage.getSession()?.accountId == target.owner?.ownerId &&
            configRepository.getCurrentBackendUrl() == target.origin
    }

    /**
     * Cancels the background sync observation coroutine.
     * Should be called when the sync manager is no longer needed (e.g., during testing cleanup).
     */
    fun cancel() {
        supervisorJob.cancel()
        Napier.d("AndroidSyncManager scope cancelled")
    }

    /**
     * Resumes retained work and fetches changes when connectivity returns, even if a previous
     * process lost its last-error state or the scheduler is still waiting in backoff.
     */
    private suspend fun handleNetworkRestored() {
        Napier.i("Network stable after offline period, checking if sync retry needed")
        if (sessionStorage.getSession() == null) return
        try {
            defaultSyncManager.releaseUploadBackoff()
            resumeScheduledSync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not resume sync after network restoration: ${error::class.simpleName}")
        }
    }

    override fun sync(startNow: Boolean) {
        if (startNow) {
            scope.launch {
                runCatchingUnlessCancelled { requestBackup() }
                    .onFailure { Napier.e("Could not request a manual backup: ${it::class.simpleName}") }
            }
        } else {
            // The auth/policy observer above owns the periodic schedule.
        }
    }

    override suspend fun requestBackup(allowMeteredMedia: Boolean) {
        val consent =
            if (allowMeteredMedia) {
                val session = requireNotNull(sessionStorage.getOriginBoundSession())
                UploadScope(session.session.accountId, session.origin)
            } else {
                null
            }
        val operation = backUpNow(consent)
        withContext(Dispatchers.IO) { operation.result.get() }
    }

    /**
     * What "Back up now" does. A request that failed before sits in WorkManager's retry backoff,
     * which reaches five hours, and a new request queued with [ExistingWorkPolicy.KEEP] was thrown
     * away behind it -- so tapping the button did nothing for hours. Replace a queued request
     * instead, unless one is actually running (two writers on one account is what KEEP is there to
     * prevent). Entries waiting out their own retry backoff are released too, so the run the user
     * asked for attempts them.
     */
    private suspend fun backUpNow(consent: UploadScope? = null): Operation {
        try {
            defaultSyncManager.releaseUploadBackoff()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Could not release upload backoff for a manual backup: ${error::class.simpleName}")
        }
        return resumeScheduledSync(consent, userInitiated = true)
    }

    private suspend fun resumeScheduledSync(
        consent: UploadScope? = null,
        ownerChanged: Boolean = false,
        userInitiated: Boolean = false,
    ): Operation =
        immediateScheduleMutex.withLock {
            val policy =
                try {
                    val work =
                        workManager
                            .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
                            .first()
                    immediateSyncPolicy(
                        work.map { it.state },
                        hasMobileDataConsent = consent != null,
                        ownerChanged = ownerChanged,
                        preserveConsentedRequest = work.any { !it.state.isFinished && MOBILE_DATA_SYNC_TAG in it.tags },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.e("Could not inspect scheduled sync; preserving any active request: ${error::class.simpleName}")
                    when {
                        ownerChanged -> ExistingWorkPolicy.REPLACE
                        consent != null -> ExistingWorkPolicy.APPEND_OR_REPLACE
                        else -> ExistingWorkPolicy.KEEP
                    }
                }
            scheduleImmediateSync(
                AndroidLogDateSyncWorker.SYNC_TYPE_FULL,
                policy = policy,
                consent = consent,
                expedited = userInitiated,
            ).also {
                it.await()
            }
        }

    /**
     * Schedules immediate sync work with high priority.
     */
    private fun scheduleImmediateSync(
        syncType: String = AndroidLogDateSyncWorker.SYNC_TYPE_FULL,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        consent: UploadScope? = null,
        expedited: Boolean = false,
    ): Operation {
        val request = immediateSyncRequest(syncType, consent, expedited)

        // Unique, and an already-running sync wins. Stacking runs would put several writers on
        // the same account's repo at once, which is exactly the contention that used to drop
        // records and starve the server of request threads.
        val operation =
            workManager.enqueueUniqueWork(
                AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC,
                policy,
                request,
            )
        Napier.d("Scheduled immediate sync")
        return operation
    }

    /**
     * Sets up periodic background sync using WorkManager.
     *
     * Constraints adapt to the current data usage mode:
     * - [DataUsageMode.Restricted]: Require unmetered network (WiFi only)
     * - [DataUsageMode.Conservative] / [DataUsageMode.Unrestricted]: Any connected network
     */
    private suspend fun setupPeriodicSync(requirement: PeriodicNetworkRequirement) {
        val networkType =
            when (requirement) {
                PeriodicNetworkRequirement.UNMETERED -> NetworkType.UNMETERED
                PeriodicNetworkRequirement.CONNECTED -> NetworkType.CONNECTED
            }

        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(networkType)
                // No battery-not-low constraint. Backing up a journal is a few small requests, and
                // at low battery -- exactly when a phone is likeliest to die -- holding entries back
                // is the worse trade.
                .build()

        // A download-only schedule meant nothing ever retried an upload on its own: an entry that
        // failed could only be attempted again when the user wrote something new or tapped sync,
        // so it never worked through its retry budget and simply sat unsynced indefinitely.
        val inputData =
            Data
                .Builder()
                .putString(AndroidLogDateSyncWorker.KEY_SYNC_TYPE, AndroidLogDateSyncWorker.SYNC_TYPE_FULL)
                .build()

        val periodicRequest =
            PeriodicWorkRequestBuilder<AndroidLogDateSyncWorker>(
                15,
                TimeUnit.MINUTES, // Minimum interval allowed by Android
            ).setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    1,
                    TimeUnit.MINUTES,
                ).build()

        // UPDATE applies changed constraints without resetting the periodic clock on startup.
        workManager
            .enqueueUniquePeriodicWork(
                AndroidLogDateSyncWorker.WORK_NAME_PERIODIC_SYNC,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest,
            ).await()

        Napier.d("Scheduled periodic sync")
    }

    /**
     * Disables all background sync work.
     * Called automatically when authentication state changes to unauthenticated.
     */
    private suspend fun disableBackgroundSync() {
        workManager.cancelUniqueWork(AndroidLogDateSyncWorker.WORK_NAME_PERIODIC_SYNC).await()
        Napier.i("Disabled background sync")
    }

    /**
     * Schedules upload-only sync for immediate changes.
     */
    fun scheduleUploadSync() {
        schedulePartialSync(AndroidLogDateSyncWorker.SYNC_TYPE_UPLOAD)
    }

    /**
     * Schedules download-only sync for pulling remote changes.
     */
    fun scheduleDownloadSync() {
        schedulePartialSync(AndroidLogDateSyncWorker.SYNC_TYPE_DOWNLOAD)
    }

    private fun schedulePartialSync(syncType: String) {
        scope.launch {
            try {
                immediateScheduleMutex.withLock { scheduleImmediateSync(syncType).await() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Could not persist partial sync request: ${error::class.simpleName}")
            }
        }
    }

    override suspend fun uploadPendingChanges(): SyncResult = defaultSyncManager.uploadPendingChanges()

    override suspend fun <T> whilePaused(block: suspend () -> T): T = defaultSyncManager.whilePaused(block)

    override suspend fun downloadRemoteChanges(): SyncResult = defaultSyncManager.downloadRemoteChanges()

    override suspend fun syncContent(): SyncResult = defaultSyncManager.syncContent()

    override suspend fun syncJournals(): SyncResult = defaultSyncManager.syncJournals()

    override suspend fun syncAssociations(): SyncResult = defaultSyncManager.syncAssociations()

    override suspend fun syncDrafts(): SyncResult = defaultSyncManager.syncDrafts()

    override suspend fun fullSync(): SyncResult = defaultSyncManager.fullSync()

    override val syncStatusFlow =
        combine(defaultSyncManager.syncStatusFlow, requestedBackupState) { status, requestState ->
            status.copy(requestState = requestState)
        }.stateIn(scope, SharingStarted.Eagerly, defaultSyncManager.syncStatusFlow.value)

    override suspend fun getSyncStatus(): SyncStatus = defaultSyncManager.getSyncStatus().copy(requestState = requestedBackupState.value)

    override fun observeDeadLetters(): kotlinx.coroutines.flow.Flow<List<app.logdate.client.sync.metadata.SyncDeadLetterRecord>> =
        defaultSyncManager.observeDeadLetters()

    override suspend fun retryDeadLetter(id: String) = defaultSyncManager.retryDeadLetter(id)

    override suspend fun discardDeadLetter(id: String) = defaultSyncManager.discardDeadLetter(id)
}
