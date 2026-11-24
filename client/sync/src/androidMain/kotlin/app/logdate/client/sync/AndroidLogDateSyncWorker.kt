package app.logdate.client.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.AppInfoProvider
import app.logdate.client.sync.metadata.UploadScope
import io.github.aakira.napier.Napier
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes

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
            Napier.i("Starting Android background sync worker")
            val appInfo = appInfoProvider.getAppInfo()
            syncManager.resumeAfterUpgrade("${appInfo.versionCode}:${appInfo.versionName}", upgradeResumption)
            val syncType = inputData.getString(KEY_SYNC_TYPE) ?: SYNC_TYPE_FULL
            val consent = mobileDataSyncScope(inputData, sessionStorage.getOriginBoundSession())
            val result =
                executeSyncRun(syncType, consent, isManualRequest)
                    ?: run {
                        Napier.w("Sync worker reached wall-clock budget; yielding for retry")
                        return if (runAttemptCount < SyncWorkerRetryPolicy.MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
                    }
            toWorkerResult(syncType, result)
        } catch (e: CancellationException) {
            // WorkManager cancellation is terminal. Propagating it prevents a cancelled job from
            // being reported as a retry and accidentally resurrected by the scheduler.
            throw e
        } catch (e: Exception) {
            Napier.e("Unexpected error in sync worker")
            if (runAttemptCount < SyncWorkerRetryPolicy.MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }

    private suspend fun executeSyncRun(
        syncType: String,
        consent: UploadScope?,
        isManualRequest: Boolean,
    ): SyncResult? {
        var promotedToForeground = false

        suspend fun promoteOnce(foregroundInfo: ForegroundInfo): Boolean {
            if (!SyncForegroundPolicy.shouldPromoteToForeground(isManualRequest, consent != null)) return false
            return try {
                setForeground(foregroundInfo)
                promotedToForeground = true
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.w("Backup running without a foreground notification")
                false
            }
        }

        val run: suspend () -> SyncResult = {
            if (consent != null) {
                withMobileDataConsent(consent) { runSyncBatches { runSync(syncType) } }
            } else {
                runSyncBatches { runSync(syncType) }
            }
        }

        return withTimeoutOrNull(MAX_SYNC_RUN_DURATION) {
            withSyncForegroundRun(
                statuses = syncManager.syncStatusFlow,
                showStarting = { promotedToForeground || promoteOnce(getForegroundInfo()) },
                showProgress = { progress ->
                    val text =
                        applicationContext.getString(R.string.sync_notification_progress, progress.completed, progress.total)
                    if (!promotedToForeground) {
                        promoteOnce(SyncForegroundNotification.info(applicationContext, text, progress.completed, progress.total))
                    } else {
                        SyncForegroundNotification.updateProgress(applicationContext, text, progress.completed, progress.total)
                    }
                },
                hasEligibleWork = {
                    if (consent == null) syncManager.hasDueWork() else withMobileDataConsent(consent) { syncManager.hasDueWork() }
                },
                run = run,
            )
        }
    }

    private fun toWorkerResult(
        syncType: String,
        result: SyncResult,
    ): Result =
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

    companion object {
        const val KEY_SYNC_TYPE = "sync_type"
        const val KEY_USER_INITIATED = "user_initiated"
        const val SYNC_TYPE_UPLOAD = "upload"
        const val SYNC_TYPE_DOWNLOAD = "download"
        const val SYNC_TYPE_FULL = "full"

        const val WORK_NAME_PERIODIC_SYNC = "periodic_sync"
        const val WORK_NAME_IMMEDIATE_SYNC = "immediate_sync"

        internal val executionGate = SyncWorkerExecutionGate()
        private val MAX_SYNC_RUN_DURATION = 15.minutes
    }
}
