package app.logdate.feature.core.settings.ui

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.device.crypto.IdentityKeyManager
import app.logdate.client.sync.SyncReadinessGate
import app.logdate.client.sync.cloud.BackupMetadata
import app.logdate.client.sync.cloud.CloudBackupDataSource
import app.logdate.feature.core.export.CloudArchiveCipher
import app.logdate.feature.core.export.CloudBackupAttemptStore
import app.logdate.feature.core.export.CloudBackupScheduler
import app.logdate.feature.core.export.CloudBackupWorker
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.cancellation.CancellationException

/** Progress is local; only a server response establishes a completed backup. */
class AndroidCloudArchiveStatusSource(
    context: Context,
    private val identityKeyManager: IdentityKeyManager,
    private val cloudBackupDataSource: CloudBackupDataSource,
    private val scheduler: CloudBackupScheduler,
    private val sessionStorage: SessionStorage,
    private val readinessGate: SyncReadinessGate,
) : CloudArchiveStatusSource {
    private val workManager = WorkManager.getInstance(context)
    private val attempts = CloudBackupAttemptStore(context.noBackupFilesDir)

    override fun observe(session: UserSession): Flow<CloudArchiveStatus> =
        flow {
            emit(CloudArchiveStatus(CloudArchivePhase.CHECKING))
            var lastWorkKey: Triple<ArchiveWorkState, Long?, List<String>>? = null
            var lastLookupSession: UserSession? = null
            var confirmedAt: Long? = null
            var lookupFailed = true
            val scope = sessionStorage.getOriginBoundSession()?.takeIf { it.session.accountId == session.accountId }
            combine(
                workManager.getWorkInfosForUniqueWorkFlow("${CloudBackupWorker.WORK_NAME}:immediate"),
                workManager.getWorkInfosForUniqueWorkFlow(CloudBackupWorker.WORK_NAME),
                sessionStorage.getSessionFlow(),
                readinessGate.reason,
            ) { immediate, periodic, _, _ -> immediate to periodic }.collect { (immediate, periodic) ->
                val current = sessionStorage.getOriginBoundSession()
                if (scope == null || current?.origin != scope.origin || current.session.accountId != session.accountId) {
                    emit(CloudArchiveStatus(CloudArchivePhase.UNAVAILABLE))
                    return@collect
                }
                try {
                    if (!identityKeyManager.hasIdentityKey() || !identityKeyManager.canUseForAccount(session.accountId)) {
                        emit(CloudArchiveStatus(CloudArchivePhase.NEEDS_RECOVERY))
                        return@collect
                    }
                    val work = (immediate + periodic).filter { workMatchesScope(it, current) }
                    val selected = archiveWorkSelection(immediate, periodic, attempts.read(current), current)
                    val state = selected?.state ?: ArchiveWorkState.IDLE
                    val key = Triple(state, selected?.order, work.map { it.id.toString() }.sorted())
                    if (key != lastWorkKey || lastLookupSession != current.session) {
                        val remote = cloudBackupDataSource.listBackups(current.session.accessToken)
                        val afterLookup = sessionStorage.getOriginBoundSession()
                        if (afterLookup?.origin != scope.origin || afterLookup.session.accountId != session.accountId) {
                            emit(CloudArchiveStatus(CloudArchivePhase.UNAVAILABLE))
                            return@collect
                        }
                        confirmedAt = remote.getOrNull()?.let { confirmedArchiveAt(selected, it) }
                        lookupFailed = remote.isFailure ||
                            (selected?.state == ArchiveWorkState.SUCCEEDED && selected.backupId != null && confirmedAt == null)
                        lastWorkKey = key
                        lastLookupSession = current.session
                    }
                    val progress = archiveProgress(work, current)
                    emit(resolveCloudArchiveStatus(true, state, confirmedAt, lookupFailed, progress))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Napier.w("Could not read Cloud archive backup status", error)
                    val state =
                        archiveWorkSelection(
                            immediate,
                            periodic,
                            runCatching { attempts.read(current) }.getOrNull(),
                            current,
                        )?.state ?: ArchiveWorkState.IDLE
                    emit(resolveCloudArchiveStatus(true, state, null, true))
                }
            }
        }

    private fun confirmedArchiveAt(
        selected: CloudArchiveAttempt?,
        backups: List<BackupMetadata>,
    ): Long? =
        if (selected?.state == ArchiveWorkState.SUCCEEDED && selected.backupId != null) {
            confirmedArchiveAttemptAt(selected, backups, CloudArchiveCipher.MANIFEST)
        } else {
            latestEncryptedArchiveAt(backups, CloudArchiveCipher.MANIFEST)
        }

    private fun archiveProgress(
        work: List<WorkInfo>,
        scope: OriginBoundSession,
    ): CloudArchiveProgress? {
        return work.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let { data ->
            val stage =
                CloudArchiveStage.entries.firstOrNull {
                    it.name ==
                        data.getString(
                            CloudBackupWorker.PROGRESS_STAGE,
                        )
                }
            if (stage == null ||
                data.getString(CloudBackupWorker.PROGRESS_ACCOUNT) != scope.session.accountId ||
                data.getString(CloudBackupWorker.PROGRESS_ORIGIN) != scope.origin
            ) {
                return@let null
            }
            val fraction =
                data
                    .getFloat(CloudBackupWorker.PROGRESS_FRACTION, Float.NaN)
                    .takeIf { stage == CloudArchiveStage.EXPORTING && it.isFinite() && it in 0f..1f }
            CloudArchiveProgress(stage, fraction)
        }
    }

    override suspend fun requestBackup() = scheduler.enqueueImmediateBackup()
}

internal fun archiveWorkState(
    immediate: List<WorkInfo>,
    periodic: List<WorkInfo>,
): ArchiveWorkState = archiveWorkSelection(immediate, periodic)?.state ?: ArchiveWorkState.IDLE

private fun archiveWorkSelection(
    immediate: List<WorkInfo>,
    periodic: List<WorkInfo>,
    recorded: CloudArchiveAttempt? = null,
    scope: OriginBoundSession? = null,
): CloudArchiveAttempt? {
    fun snapshot(
        work: WorkInfo,
        isPeriodic: Boolean,
    ): CloudArchiveAttempt {
        val state =
            when (work.state) {
                WorkInfo.State.RUNNING -> {
                    ArchiveWorkState.RUNNING
                }

                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    when {
                        work.runAttemptCount > 0 -> ArchiveWorkState.RETRYING
                        isPeriodic -> ArchiveWorkState.IDLE
                        else -> ArchiveWorkState.QUEUED
                    }
                }

                WorkInfo.State.FAILED -> {
                    ArchiveWorkState.FAILED
                }

                WorkInfo.State.SUCCEEDED -> {
                    ArchiveWorkState.SUCCEEDED
                }

                else -> {
                    ArchiveWorkState.IDLE
                }
            }
        val output = work.outputData
        val requestOrder =
            work.tags
                .filter { it.startsWith(CloudBackupWorker.REQUEST_TIME_TAG) }
                .mapNotNull { it.removePrefix(CloudBackupWorker.REQUEST_TIME_TAG).toLongOrNull() }
                .maxOrNull() ?: 0L
        val order =
            output.getLong(CloudBackupWorker.ATTEMPT_ORDER, 0).takeIf { it > 0 }
                ?: work.progress.getLong(CloudBackupWorker.ATTEMPT_ORDER, 0).takeIf { it > 0 } ?: requestOrder
        return CloudArchiveAttempt(
            work.id.toString(),
            state,
            order,
            output.getString(CloudBackupWorker.ATTEMPT_BACKUP_ID),
            output.getString(CloudBackupWorker.ATTEMPT_DEVICE_ID),
        )
    }
    val matchingImmediate = immediate.filter { scope == null || workMatchesScope(it, scope) }
    val matchingPeriodic = periodic.filter { scope == null || workMatchesScope(it, scope) }
    return selectArchiveWork(matchingImmediate.map { snapshot(it, false) } + matchingPeriodic.map { snapshot(it, true) }, recorded)
}

private fun workMatchesScope(
    work: WorkInfo,
    scope: OriginBoundSession,
): Boolean {
    val metadata = work.progress.takeIf { it.getString(CloudBackupWorker.PROGRESS_ACCOUNT) != null } ?: work.outputData
    val account = metadata.getString(CloudBackupWorker.PROGRESS_ACCOUNT) ?: return true
    return account == scope.session.accountId && metadata.getString(CloudBackupWorker.PROGRESS_ORIGIN) == scope.origin
}
