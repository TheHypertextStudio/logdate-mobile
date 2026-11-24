package app.logdate.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.AppInfoProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AndroidLogDateSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    private val syncManager: DefaultSyncManager by inject()
    private val sessionStorage: SessionStorage by inject()
    private val appInfoProvider: AppInfoProvider by inject()
    private val upgradeResumption: SyncUpgradeResumption by inject()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        SyncForegroundNotification.info(
            applicationContext,
            applicationContext.getString(R.string.sync_notification_starting),
        )

    override suspend fun doWork(): Result {
        val isManualRequest = inputData.getBoolean(KEY_USER_INITIATED, false)
        val hasMobileDataConsentTag = MOBILE_DATA_SYNC_TAG in tags
        return executionGate.withGate(
            onBusy = {
                Napier.i("Skipping overlapping sync worker run")
                if (isManualRequest || hasMobileDataConsentTag) Result.retry() else Result.success()
            },
        ) {
            runGuardedWork(isManualRequest)
        }
    }

    private suspend fun runGuardedWork(isManualRequest: Boolean): Result =
        try {
            val boundSession = sessionStorage.getOriginBoundSession()
            if (boundSession == null) {
                Napier.d("Skipping background sync worker; no signed-in session")
                return Result.success()
            }

            val appInfo = appInfoProvider.getAppInfo()
            syncManager.resumeAfterUpgrade("${appInfo.versionCode}:${appInfo.versionName}", upgradeResumption)

            val consent = mobileDataSyncScope(inputData, boundSession)
            val hasWork = isManualRequest || consent != null || syncManager.hasDueWork() || syncManager.hasRemoteRefreshDue()
            if (!hasWork) {
                Napier.d("Skipping background sync worker; no due work")
                return Result.success()
            }

            Napier.i("Starting Android background sync worker")
            // Only promote when user-initiated or already in foreground so API 31+ background
            // wakes don't throw ForegroundServiceStartNotAllowedException or poison WorkManager.
            val promoted =
                if (SyncForegroundPolicy.shouldPromoteToForeground(isManualRequest, consent != null)) {
                    try {
                        setForeground(getForegroundInfo())
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Napier.w("Backup running without a foreground notification", e)
                        false
                    }
                } else {
                    false
                }

            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL
            val run: suspend () -> SyncResult = {
                if (consent != null) withMobileDataConsent(consent) { runSync(syncType) } else runSync(syncType)
            }
            val result =
                withTimeoutOrNull(MAX_SYNC_RUN_DURATION) {
                    if (promoted) withProgressNotification(run) else run()
                }
            if (result == null) {
                Napier.w("Sync worker reached wall-clock budget; yielding for retry")
                return if (runAttemptCount < SyncWorkerRetryPolicy.MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
            }

            when (SyncWorkerRetryPolicy.decide(result, runAttemptCount)) {
                SyncWorkerRetryPolicy.Outcome.SUCCESS -> {
                    Napier.i("Sync run finished")
                    Result.success()
                }

                SyncWorkerRetryPolicy.Outcome.RETRY -> {
                    Napier.w("Sync run will retry")
                    Result.retry()
                }

                SyncWorkerRetryPolicy.Outcome.FAILURE -> {
                    Napier.e("Sync run failed; the next scheduled run will try again")
                    Result.failure()
                }
            }
        } catch (e: CancellationException) {
            // WorkManager cancellation is terminal. Propagating it prevents a cancelled job from
            // being reported as a retry and accidentally resurrected by the scheduler.
            throw e
        } catch (e: Exception) {
            Napier.e("Unexpected error in sync worker", e)
            if (runAttemptCount < SyncWorkerRetryPolicy.MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }

    private suspend fun runSync(syncType: String): SyncResult =
        when (syncType) {
            SYNC_TYPE_UPLOAD -> {
                Napier.d("Performing upload sync")
                syncManager.uploadPendingChanges()
            }

            SYNC_TYPE_DOWNLOAD -> {
                Napier.d("Performing download sync")
                syncManager.downloadRemoteChanges()
            }

            SYNC_TYPE_FULL -> {
                Napier.d("Performing full sync")
                syncManager.fullSync()
            }

            else -> {
                Napier.w("Unknown sync type, defaulting to full sync")
                syncManager.fullSync()
            }
        }

    /**
     * Runs [sync] while the notification follows the run's progress via `NotificationManager.notify`,
     * joining the progress collector before returning so no update can fire after worker completion.
     */
    private suspend fun <T> withProgressNotification(sync: suspend () -> T): T =
        coroutineScope {
            val progressUpdatesActive = AtomicBoolean(true)
            if (progressUpdatesActive.get()) {
                SyncForegroundNotification.updateProgress(
                    applicationContext,
                    applicationContext.getString(R.string.sync_notification_checking),
                )
            }
            val statuses = syncManager.syncStatusFlow
            val since = statuses.value
            val updates =
                launch {
                    statuses
                        .runProgressUpdates(since = since, period = PROGRESS_UPDATE_PERIOD)
                        .takeWhile { progress ->
                            progressUpdatesActive.get() && showProgress(progress)
                        }.collect()
                }
            try {
                sync()
            } finally {
                progressUpdatesActive.set(false)
                withContext(NonCancellable) {
                    updates.cancelAndJoin()
                }
            }
        }

    /**
     * Shows [progress] in the existing foreground notification via `NotificationManager.notify`.
     * Returns false once the notification can no longer be updated.
     */
    private fun showProgress(progress: SyncRunProgress): Boolean {
        val text = applicationContext.getString(R.string.sync_notification_progress, progress.completed, progress.total)
        return SyncForegroundNotification.updateProgress(
            applicationContext,
            text,
            progress.completed,
            progress.total,
        )
    }

    companion object {
        const val KEY_SYNC_TYPE = "sync_type"
        const val KEY_USER_INITIATED = "user_initiated"
        const val SYNC_TYPE_UPLOAD = "upload"
        const val SYNC_TYPE_DOWNLOAD = "download"
        const val SYNC_TYPE_FULL = "full"

        const val WORK_NAME_PERIODIC_SYNC = "periodic_sync"
        const val WORK_NAME_IMMEDIATE_SYNC = "immediate_sync"

        internal val executionGate = SyncWorkerExecutionGate()

        /** Often enough to watch a backup move, rarely enough not to redraw on every entry. */
        private val PROGRESS_UPDATE_PERIOD = 1.seconds
        private val MAX_SYNC_RUN_DURATION = 15.minutes
    }
}
