package app.logdate.feature.core.restore

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import java.util.concurrent.TimeUnit

/** Keeps accepted entries usable while their originals recover independently. */
class ArchiveMediaRecoveryWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    override suspend fun doWork(): Result {
        val recoveryId = inputData.getString(RestoreWorker.RECOVERY_ID_KEY) ?: return Result.success()
        return ArchiveRecoveryLocks.withLock(recoveryId) {
            try {
                val record = context.archiveRecoveryStore().read(recoveryId) ?: return@withLock Result.success()
                if (record.pausedByUser ||
                    record.scope != currentArchiveRecoveryScope(getKoin()) ||
                    !record.metadataApplied ||
                    !record.options.includeMedia
                ) {
                    return@withLock Result.success()
                }
                try {
                    setForeground(
                        RestoreNotificationHelper(
                            context,
                            kotlin.uuid.Uuid.parse(this.id.toString()),
                        ).createForegroundInfo(RestoreStage.IMPORTING_MEDIA),
                    )
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (_: Exception) {
                    Napier.w("Media recovery continues without a foreground notification")
                }
                val summary = ArchiveMediaRecoveryEngine(context, getKoin()).repair(record)
                getKoin().get<RestoreLauncher>().updateRecovery(summary)
                if (!summary.shouldRetryMediaRecovery()) {
                    Result.success()
                } else {
                    scheduleRetry(recoveryId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Napier.w("Attachment recovery retained its checkpoint and will retry")
                scheduleRetry(recoveryId)
            }
        }
    }

    private suspend fun scheduleRetry(recoveryId: String): Result =
        if (
            enqueueArchiveMediaRetry {
                ArchiveRecoveryScheduler.enqueueRetry(context, recoveryId, inputData)
            }
        ) {
            Result.success()
        } else {
            Result.retry()
        }
}

internal suspend fun enqueueArchiveMediaRetry(enqueue: suspend () -> Unit): Boolean =
    try {
        enqueue()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

internal object ArchiveRecoveryScheduler {
    private val gate = ArchiveRecoveryScheduleGate()

    suspend fun enqueueRetry(
        context: Context,
        id: String,
        inputData: androidx.work.Data,
    ) = withContext(Dispatchers.IO) {
        val attempt = inputData.getInt(RETRY_ATTEMPT_KEY, 0) + 1
        val retryData =
            Data
                .Builder()
                .putAll(inputData.keyValueMap)
                .putInt(RETRY_ATTEMPT_KEY, attempt)
                .build()
        val request =
            OneTimeWorkRequestBuilder<ArchiveMediaRecoveryWorker>()
                .setInputData(retryData)
                .setConstraints(archiveRecoveryConstraints)
                .addTag(RETRY_DELAYED_TAG)
                .setInitialDelay(retryDelayMillis(attempt), TimeUnit.MILLISECONDS)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(workName(id, media = true), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .result
            .get()
    }

    internal fun retryDelayMillis(attempt: Int) = minOf(900_000L, 30_000L shl (attempt - 1).coerceIn(0, 4))

    suspend fun enqueueAll(
        context: Context,
        koin: org.koin.core.Koin,
        change: ArchiveRecoveryReadinessChange,
    ) {
        val scope = currentArchiveRecoveryScope(koin)
        val records = context.archiveRecoveryStore().pending(scope)
        if (currentArchiveRecoveryScope(koin) != scope) return
        withContext(Dispatchers.IO) {
            val manager = WorkManager.getInstance(context)
            records.forEach { record ->
                val uniqueName = workName(record.id, record.metadataApplied)
                var workStates: List<ArchiveRecoveryWorkState>? = null

                suspend fun currentWorkStates(): List<ArchiveRecoveryWorkState> =
                    workStates ?: manager
                        .getWorkInfosForUniqueWork(uniqueName)
                        .get()
                        .map { workInfo ->
                            ArchiveRecoveryWorkState(
                                phase =
                                    when (workInfo.state) {
                                        WorkInfo.State.RUNNING -> ArchiveRecoveryWorkPhase.RUNNING

                                        WorkInfo.State.ENQUEUED -> ArchiveRecoveryWorkPhase.ENQUEUED

                                        WorkInfo.State.BLOCKED -> ArchiveRecoveryWorkPhase.BLOCKED

                                        WorkInfo.State.SUCCEEDED,
                                        WorkInfo.State.FAILED,
                                        WorkInfo.State.CANCELLED,
                                        -> ArchiveRecoveryWorkPhase.FINISHED
                                    },
                                runAttemptCount = workInfo.runAttemptCount,
                                explicitDelay = RETRY_DELAYED_TAG in workInfo.tags,
                            )
                        }.also { workStates = it }
                gate.enqueue(
                    hasQueuedWork = { currentWorkStates().hasQueuedArchiveRecoveryWork() },
                    hasRunningWork = { currentWorkStates().hasRunningArchiveRecoveryWork() },
                    change = change,
                    isDelayedRetry = { currentWorkStates().hasDelayedArchiveRecoveryRetry() },
                    replaceRunningWork = {
                        if (currentArchiveRecoveryScope(koin) == scope) {
                            enqueue(context, record, ExistingWorkPolicy.REPLACE)?.result?.get()
                        }
                    },
                    replaceDelayedRetry = {
                        if (currentArchiveRecoveryScope(koin) == scope) {
                            enqueue(context, record, ExistingWorkPolicy.REPLACE)?.result?.get()
                        }
                    },
                    enqueueSuccessor = {
                        if (currentArchiveRecoveryScope(koin) == scope) {
                            enqueue(context, record, ExistingWorkPolicy.APPEND_OR_REPLACE)?.result?.get()
                        }
                    },
                )
            }
        }
    }

    fun workName(
        id: String,
        media: Boolean,
    ) = "archive_${if (media) "media" else "metadata"}_recovery_$id"

    private const val RETRY_ATTEMPT_KEY = "archive_media_retry_attempt"
    internal const val RETRY_DELAYED_TAG = "archive_media_retry_delayed"

    fun cancel(
        context: Context,
        id: String,
    ) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(workName(id, false))
        manager.cancelUniqueWork(workName(id, true))
        manager.cancelUniqueWork("archive_recovery_$id")
    }

    fun enqueue(
        context: Context,
        record: ArchiveRecoveryRecord,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ): Operation? {
        if (record.pausedByUser ||
            (
                record.requiresSource &&
                    !record.metadataApplied
            ) ||
            (
                record.metadataApplied &&
                    !record.options.includeMedia
            )
        ) {
            return null
        }
        val input =
            workDataOf(
                RestoreWorker.RECOVERY_ID_KEY to record.id,
                RestoreWorker.SILENT_RECOVERY_KEY to true,
                RestoreWorker.EXPECTED_OWNER_KEY to record.scope.ownerId,
                RestoreWorker.EXPECTED_ORIGIN_KEY to record.scope.serverOrigin,
                RestoreWorker.INCLUDE_DRAFTS_KEY to record.options.includeDrafts,
                RestoreWorker.INCLUDE_MEDIA_KEY to record.options.includeMedia,
                RestoreWorker.PRESERVE_POPULATED_PROFILE_KEY to true,
            )
        val work =
            if (record.metadataApplied) {
                OneTimeWorkRequestBuilder<ArchiveMediaRecoveryWorker>()
                    .setInputData(
                        input,
                    ).setConstraints(archiveRecoveryConstraints)
                    .build()
            } else {
                OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(input).setConstraints(archiveRecoveryConstraints).build()
            }
        return WorkManager.getInstance(context).enqueueUniqueWork(workName(record.id, record.metadataApplied), policy, work)
    }
}
