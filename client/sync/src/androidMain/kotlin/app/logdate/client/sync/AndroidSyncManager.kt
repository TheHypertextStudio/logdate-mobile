package app.logdate.client.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.networking.DataUsageMode
import app.logdate.client.networking.DataUsagePolicy
import app.logdate.client.networking.NetworkAvailabilityMonitor
import app.logdate.client.networking.NetworkState
import app.logdate.client.sync.metadata.UploadScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
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
) : SyncManager {
    private val workManager = WorkManager.getInstance(applicationContext)
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.IO)
    private val periodicScheduleDecider = PeriodicSyncScheduleDecider()
    private val requestedBackupState =
        workManager
            .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
            .map { work -> backupRequestState(work.map { BackupWorkSnapshot(it.state, it.runAttemptCount) }) }
            .stateIn(scope, SharingStarted.Eagerly, BackupRequestState.NONE)

    init {
        // Keep one owner for periodic scheduling. Separate auth and policy collectors used to
        // replace the same work twice at startup and could schedule again after sign-out.
        scope.launch {
            combine(
                sessionStorage.getSessionFlow().map { it != null },
                dataUsagePolicy.policy,
            ) { authenticated, mode -> periodicScheduleDecider.next(authenticated, mode) }
                .collect { change ->
                    when (change) {
                        is PeriodicSyncScheduleChange.Enable -> {
                            try {
                                setupPeriodicSync(change.requirement)
                                if (defaultSyncManager.hasDueWork() || defaultSyncManager.hasRemoteRefreshDue()) {
                                    resumeScheduledSync()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                Napier.e("Could not schedule authenticated sync")
                            }
                        }
                        PeriodicSyncScheduleChange.Disable -> disableBackgroundSync()
                        PeriodicSyncScheduleChange.Unchanged -> Unit
                    }
                }
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
        } catch (_: Exception) {
            Napier.e("Could not resume sync after network restoration")
        }
    }

    override fun sync(startNow: Boolean) {
        if (startNow) {
            scope.launch {
                runCatching { requestBackup() }
                    .onFailure { Napier.e("Could not request a manual backup") }
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
        } catch (_: Exception) {
            Napier.e("Could not release upload backoff for a manual backup")
        }
        return resumeScheduledSync(consent, userInitiated = true)
    }

    private suspend fun resumeScheduledSync(
        consent: UploadScope? = null,
        userInitiated: Boolean = false,
    ): Operation {
        val policy =
            try {
                val work =
                    workManager
                        .getWorkInfosForUniqueWorkFlow(AndroidLogDateSyncWorker.WORK_NAME_IMMEDIATE_SYNC)
                        .first()
                immediateSyncPolicy(
                    work.map { it.state },
                    hasMobileDataConsent = consent != null,
                    preserveConsentedRequest = work.any { !it.state.isFinished && MOBILE_DATA_SYNC_TAG in it.tags },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.e("Could not inspect scheduled sync; preserving any active request")
                if (consent != null) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            }
        return scheduleImmediateSync(
            AndroidLogDateSyncWorker.SYNC_TYPE_FULL,
            policy = policy,
            consent = consent,
            userInitiated = userInitiated,
        )
    }

    /**
     * Schedules immediate sync work with high priority.
     */
    internal fun scheduleImmediateSync(
        syncType: String = AndroidLogDateSyncWorker.SYNC_TYPE_FULL,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        consent: UploadScope? = null,
        userInitiated: Boolean = false,
    ): Operation {
        val constraints =
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val inputData =
            Data
                .Builder()
                .putAll(mobileDataSyncInput(consent))
                .putString(AndroidLogDateSyncWorker.KEY_SYNC_TYPE, syncType)
                .putBoolean(AndroidLogDateSyncWorker.KEY_USER_INITIATED, userInitiated)
                .build()

        val request =
            OneTimeWorkRequestBuilder<AndroidLogDateSyncWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .apply { if (consent != null) addTag(MOBILE_DATA_SYNC_TAG) }
                // Out of expedited quota, run it as ordinary work rather than drop it. This is what
                // "Back up now" schedules, and dropping it threw the request away with nothing on
                // screen changing: the backup waited for the next periodic run, hours later, while
                // the app showed items waiting and a button that seemed to do nothing.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

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
    private fun setupPeriodicSync(requirement: PeriodicNetworkRequirement) {
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
        workManager.enqueueUniquePeriodicWork(
            AndroidLogDateSyncWorker.WORK_NAME_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest,
        )

        Napier.d("Scheduled periodic sync")
    }

    /**
     * Disables all background sync work.
     * Called automatically when authentication state changes to unauthenticated.
     */
    private fun disableBackgroundSync() {
        workManager.cancelUniqueWork(AndroidLogDateSyncWorker.WORK_NAME_PERIODIC_SYNC)
        Napier.i("Disabled background sync")
    }

    /**
     * Schedules upload-only sync for immediate changes.
     */
    fun scheduleUploadSync() {
        scheduleImmediateSync(AndroidLogDateSyncWorker.SYNC_TYPE_UPLOAD)
    }

    /**
     * Schedules download-only sync for pulling remote changes.
     */
    fun scheduleDownloadSync() {
        scheduleImmediateSync(AndroidLogDateSyncWorker.SYNC_TYPE_DOWNLOAD)
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
