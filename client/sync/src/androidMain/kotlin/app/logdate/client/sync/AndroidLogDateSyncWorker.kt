package app.logdate.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.sync.metadata.UploadScope
import io.github.aakira.napier.Napier
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException

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
            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL
            val consent = mobileDataSyncScope(inputData, sessionStorage.getOriginBoundSession())
            val run: suspend () -> SyncResult = {
                if (consent != null) {
                    withMobileDataConsent(consent) { runSyncBatches { runSync(syncType) } }
                } else {
                    runSyncBatches { runSync(syncType) }
                }
            }
            val result =
                withSyncForegroundRun(
                    statuses = syncManager.syncStatusFlow,
                    showStarting = {
                        try {
                            setForeground(getForegroundInfo())
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Napier.w("Backup running without a foreground notification")
                            false
                        }
                    },
                    showProgress = ::showProgress,
                    hasEligibleWork = {
                        if (consent == null) syncManager.hasDueWork() else withMobileDataConsent(consent) { syncManager.hasDueWork() }
                    },
                    run = run,
                )

            when (SyncWorkerRetryPolicy.decide(result, runAttemptCount)) {
                SyncWorkerRetryPolicy.Outcome.SUCCESS -> {
                    val current = sessionStorage.getOriginBoundSession()?.let { UploadScope(it.session.accountId, it.origin) }
                    Result.success(mediaRecoveryCompletionOutput(syncType, result.recoveredMediaScope?.takeIf { it == current }))
                }

                SyncWorkerRetryPolicy.Outcome.RETRY -> {
                    Result.retry()
                }

                SyncWorkerRetryPolicy.Outcome.FAILURE -> {
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
            Napier.w("Could not show backup progress in the notification: ${e::class.simpleName}")
            false
        }

    companion object {
        const val KEY_SYNC_TYPE = "sync_type"
        const val SYNC_TYPE_UPLOAD = "upload"
        const val SYNC_TYPE_DOWNLOAD = "download"
        const val SYNC_TYPE_FULL = "full"

        const val WORK_NAME_PERIODIC_SYNC = "periodic_sync"
        const val WORK_NAME_IMMEDIATE_SYNC = "immediate_sync"
    }
}
