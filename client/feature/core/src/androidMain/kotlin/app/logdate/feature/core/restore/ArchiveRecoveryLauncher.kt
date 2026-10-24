package app.logdate.feature.core.restore

import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.domain.restore.RecoveryMediaRole
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.koin.core.Koin
import java.util.UUID

/** Reconnects the restore sheet to recovery retained across process death or native transfer. */
internal class ArchiveRecoveryLauncher(
    private val context: Context,
    private val koin: Koin,
) {
    suspend fun enqueueSelected(
        uri: Uri,
        options: ImportOptions,
        recoveryId: String?,
        session: OriginBoundSession?,
        backend: String,
        begin: (UUID) -> Unit,
        commit: (() -> Unit) -> Unit,
    ) {
        val scope = currentArchiveRecoveryScope(koin)
        check(
            koin.get<SessionStorage>().getOriginBoundSession() == session &&
                koin.get<LogDateConfigRepository>().getCurrentBackendUrl().trimEnd('/') == backend,
        ) { "Archive recovery account changed before enqueue" }
        val input =
            Data
                .Builder()
                .putString(RestoreWorker.SOURCE_URI_KEY, uri.toString())
                .putString(RestoreWorker.RECOVERY_ID_KEY, recoveryId)
                .putBoolean(RestoreWorker.REPLACEMENT_SOURCE_KEY, recoveryId != null)
                .putString(RestoreWorker.EXPECTED_OWNER_KEY, scope.ownerId)
                .putString(RestoreWorker.EXPECTED_ORIGIN_KEY, scope.serverOrigin)
                .putBoolean(RestoreWorker.INCLUDE_DRAFTS_KEY, options.includeDrafts)
                .putBoolean(RestoreWorker.INCLUDE_MEDIA_KEY, options.includeMedia)
                .build()
        val work = OneTimeWorkRequestBuilder<RestoreWorker>().setInputData(input).setConstraints(archiveRecoveryConstraints).build()
        val operationContext = currentCoroutineContext()
        commit {
            operationContext.ensureActive()
            recoveryId?.let { context.archiveRecoveryStore().setPaused(it, false) }
            begin(work.id)
            WorkManager.getInstance(context).enqueueUniqueWork(RestoreWorker.WORK_NAME, ExistingWorkPolicy.REPLACE, work)
        }
    }

    suspend fun resume(
        id: String,
        commit: (() -> Unit) -> Unit,
    ): RestoreSummary? {
        val store = context.archiveRecoveryStore()
        val record = store.read(id) ?: return null
        if (!record.canResumeFromRetainedSource(currentArchiveRecoveryScope(koin), store.source(id).isFile)) return null
        val operationContext = currentCoroutineContext()
        commit {
            operationContext.ensureActive()
            store.prepareResumeCheckpoint(id)
            ArchiveRecoveryScheduler.enqueue(context, requireNotNull(store.read(id)), ExistingWorkPolicy.REPLACE)
        }
        return requireNotNull(store.read(id)).recoverySummary()
    }

    suspend fun reconnect(
        completedWorkId: UUID?,
        begin: (UUID) -> Unit,
        commit: (() -> Unit) -> Unit,
        complete: (RestoreOutcome) -> Unit,
    ) {
        val operationContext = currentCoroutineContext()
        val scope = currentArchiveRecoveryScope(koin)
        val manager = WorkManager.getInstance(context)
        val active =
            manager
                .getWorkInfosForUniqueWork(RestoreWorker.WORK_NAME)
                .get()
                .firstOrNull {
                    it.id != completedWorkId && it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
                }
        if (currentArchiveRecoveryScope(koin) != scope) return
        if (active != null) {
            commitArchiveResumeRequest(operationContext, commit) { begin(active.id) }
            return
        }
        val store = context.archiveRecoveryStore()
        val record = store.pending(scope).lastOrNull() ?: return
        if (currentArchiveRecoveryScope(koin) != scope) return
        commitArchiveResumeRequest(operationContext, commit) {
            if (!record.pausedByUser) ArchiveRecoveryScheduler.enqueue(context, record)
            complete(
                RestoreOutcome.Success(
                    record.recoverySummary(
                        record.pausedByUser && record.canResumeFromRetainedSource(scope, store.source(record.id).isFile),
                    ),
                ),
            )
        }
    }
}

internal fun ArchiveRecoveryStore.prepareResumeCheckpoint(id: String) {
    val resumed = requireNotNull(read(id))
    if (resumed.metadataApplied) {
        updateProgress(id, resumed.pending, resumed.recoverySummary().copy(recoveringMedia = true))
    }
    setPaused(id, false)
}

internal fun ArchiveRecoveryRecord.canResumeFromRetainedSource(
    scope: ArchiveRecoveryScope,
    sourceAvailable: Boolean,
): Boolean =
    this.scope == scope &&
        sourceAvailable &&
        !requiresSource &&
        (!metadataApplied || (options.includeMedia && pending.isNotEmpty()))

internal fun ArchiveRecoveryRecord.recoverySummary(canResume: Boolean = false): RestoreSummary =
    (
        summary ?: RestoreSummary(
            source = sourceLabel,
            journalsImported = 0,
            notesImported = 0,
            draftsImported = 0,
            journalLinksImported = 0,
            mediaImported = 0,
        )
    ).copy(
        recoveryId = id,
        canResume = canResume,
        metadataIncomplete = !metadataApplied,
        recoveringMedia =
            !pausedByUser &&
                (
                    (!metadataApplied && !requiresSource) || (metadataApplied && pending.isNotEmpty() && summary?.recoveringMedia == true)
                ),
        unavailableOriginals = pending.count { it.role == RecoveryMediaRole.ORIGINAL },
        unavailablePreviews = pending.count { it.role == RecoveryMediaRole.THUMBNAIL },
    )
