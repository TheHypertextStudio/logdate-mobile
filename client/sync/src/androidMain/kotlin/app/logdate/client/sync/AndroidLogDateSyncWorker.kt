package app.logdate.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.AppInfoProvider
import io.github.aakira.napier.Napier
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException
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

    override suspend fun doWork(): Result =
        try {
            Napier.i("Starting Android background sync worker")
            val appInfo = appInfoProvider.getAppInfo()
            syncManager.resumeAfterUpgrade("${appInfo.versionCode}:${appInfo.versionName}", upgradeResumption)
            // Without this the sync is ordinary background work and dies when the app leaves the
            // screen - part way through, with the queue half drained. Failing to promote is not
            // fatal (the permission may be denied); the sync just goes back to being cancellable.
            val promoted =
                runCatching { setForeground(getForegroundInfo()) }
                    .onFailure { Napier.w("Backup running without a foreground notification") }
                    .isSuccess

            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL
            val consent = mobileDataSyncScope(inputData, sessionStorage.getOriginBoundSession())
            val run: suspend () -> SyncResult = {
                if (consent != null) withMobileDataConsent(consent) { runSync(syncType) } else runSync(syncType)
            }
            val result = if (promoted) withProgressNotification(run) else run()

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
            Napier.e("Unexpected error in sync worker")
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
     * Runs [sync] while the notification follows the run's progress, and stops following it as
     * soon as [sync] returns. A backup of several hundred entries takes minutes, and a notification
     * that says "Starting…" for all of them reads as a backup that has stopped.
     */
    private suspend fun <T> withProgressNotification(sync: suspend () -> T): T =
        coroutineScope {
            val statuses = syncManager.syncStatusFlow
            val since = statuses.value
            val updates =
                launch {
                    statuses
                        .runProgressUpdates(since = since, period = PROGRESS_UPDATE_PERIOD)
                        .takeWhile { progress -> showProgress(progress) }
                        .collect()
                }
            try {
                sync()
            } finally {
                updates.cancel()
            }
        }

    /**
     * Shows [progress] in the foreground notification. Returns false once the notification can no
     * longer be updated; the backup carries on regardless, under the notification it already has.
     */
    private suspend fun showProgress(progress: SyncRunProgress): Boolean =
        try {
            val text = applicationContext.getString(R.string.sync_notification_progress, progress.completed, progress.total)
            setForeground(SyncForegroundNotification.info(applicationContext, text, progress.completed, progress.total))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not show backup progress in the notification")
            false
        }

    companion object {
        const val KEY_SYNC_TYPE = "sync_type"
        const val SYNC_TYPE_UPLOAD = "upload"
        const val SYNC_TYPE_DOWNLOAD = "download"
        const val SYNC_TYPE_FULL = "full"

        const val WORK_NAME_PERIODIC_SYNC = "periodic_sync"
        const val WORK_NAME_IMMEDIATE_SYNC = "immediate_sync"

        /** Often enough to watch a backup move, rarely enough not to redraw on every entry. */
        private val PROGRESS_UPDATE_PERIOD = 1.seconds
    }
}
