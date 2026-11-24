package app.logdate.feature.core.restore

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.logdate.client.datastore.SessionStorage
import app.logdate.client.domain.restore.ArchiveRecoveryMediaIndex
import app.logdate.client.domain.restore.RecoveryMediaRole
import app.logdate.client.domain.restore.RestoreArchiveReader
import app.logdate.client.domain.restore.RestoreOptions
import app.logdate.client.domain.restore.RestoreUserDataUseCase
import app.logdate.client.domain.restore.mediaIntegrity
import app.logdate.client.media.MediaManager
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.restore_source_label_fallback
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.uuid.Uuid

/**
 * WorkManager worker for restoring user data from a LogDate export archive.
 */
class RestoreWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    companion object {
        const val WORK_NAME = "restore_user_data"
        const val SOURCE_URI_KEY = "restore_source_uri"
        const val INCLUDE_DRAFTS_KEY = "restore_include_drafts"
        const val INCLUDE_MEDIA_KEY = "restore_include_media"
        const val PRESERVE_POPULATED_PROFILE_KEY = "restore_preserve_populated_profile"
        const val DELETE_SOURCE_AFTER_RESTORE_KEY = "restore_delete_source_after_restore"
        const val REPLACEMENT_SOURCE_KEY = "archive_recovery_replacement"
        const val EXPECTED_OWNER_KEY = "archive_recovery_owner"
        const val EXPECTED_ORIGIN_KEY = "archive_recovery_origin"
        const val RECOVERY_ID_KEY = "archive_recovery_id"
        const val SUMMARY_JSON_KEY = "restore_summary_json"
        const val ERROR_KEY = "restore_error"
        const val SILENT_RECOVERY_KEY = "archive_recovery_silent"
        const val RESTORE_OPERATION_ID_KEY = "restore_diagnostic_operation_id"
    }

    private val restoreUserDataUseCase: RestoreUserDataUseCase by inject()
    private val mediaManager: MediaManager by inject()
    private val sessionStorage: SessionStorage by inject()
    private val restoreLauncher: RestoreLauncher by inject()
    private val diagnosticEvents: RestoreDiagnosticEvents? by lazy { getKoin().getOrNull() }
    private val json = Json { ignoreUnknownKeys = true }
    private val notificationHelper = RestoreNotificationHelper(context, Uuid.parse(id.toString()))

    private val sourceUri: Uri? = inputData.getString(SOURCE_URI_KEY)?.toUri()
    private val includeDrafts: Boolean = inputData.getBoolean(INCLUDE_DRAFTS_KEY, true)
    private val includeMedia: Boolean = inputData.getBoolean(INCLUDE_MEDIA_KEY, true)
    private val preservePopulatedProfile: Boolean = inputData.getBoolean(PRESERVE_POPULATED_PROFILE_KEY, false)
    private val deleteSourceAfterRestore: Boolean = inputData.getBoolean(DELETE_SOURCE_AFTER_RESTORE_KEY, false)

    override suspend fun getForegroundInfo(): ForegroundInfo = notificationHelper.createForegroundInfo(RestoreStage.PREPARING)

    override suspend fun doWork(): Result =
        ArchiveRecoveryLocks.withLock(inputData.getString(RECOVERY_ID_KEY) ?: id.toString()) {
            try {
                restore()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: app.logdate.client.domain.restore.UnsupportedExportVersionException) {
                invalidArchive()
            } catch (_: java.util.zip.ZipException) {
                invalidArchive()
            } catch (_: Exception) {
                Napier.w("Restore retained its work and will retry")
                Result.retry()
            }
        }

    private val store by lazy { context.archiveRecoveryStore() }
    private val recoveryId get() = inputData.getString(RECOVERY_ID_KEY) ?: id.toString()
    private var sourceAccepted = false
    private var sourceRejected = false
    private var activeAttempt: Attempt? = null

    private data class Attempt(
        val scope: ArchiveRecoveryScope,
        val previous: ArchiveRecoveryRecord?,
        val native: NativeRestoreWorkState,
        val source: DiagnosticSource?,
        val diagnosticId: String,
        val replacement: Boolean,
    )

    private suspend fun restore(): Result {
        val native = NativeRestoreWorkState(context, inputData, sessionStorage)
        nativeValidationResult(native)?.let { return it }
        val scope = currentArchiveRecoveryScope(getKoin())
        val expectedOwner = inputData.getString(EXPECTED_OWNER_KEY)
        val expectedOrigin = inputData.getString(EXPECTED_ORIGIN_KEY)
        if ((expectedOwner != null && expectedOwner != scope.ownerId) || (expectedOrigin != null && expectedOrigin != scope.serverOrigin)) {
            completeRestore(RestoreOutcome.Cancelled)
            return Result.success()
        }
        val previous = store.read(recoveryId)
        if (previous?.pausedByUser == true) return Result.success()
        if (previous != null && previous.scope != scope) {
            completeRestore(RestoreOutcome.Cancelled)
            return Result.success()
        }
        val attempt =
            Attempt(
                scope,
                previous,
                native,
                runCatching { diagnosticEvents?.source() }.getOrNull(),
                Uuid.random().toString(),
                inputData.getBoolean(REPLACEMENT_SOURCE_KEY, false) && previous?.sourceWorkId != id.toString(),
            )
        activeAttempt = attempt
        reportDiagnostic(DiagnosticOutcome.STARTED, attempt.source, attempt.diagnosticId)
        if (!inputData.getBoolean(SILENT_RECOVERY_KEY, false)) (restoreLauncher as? AndroidRestoreLauncher)?.beginRestoreWork(id)
        trySetForeground(getForegroundInfo())
        emitProgress(RestoreStage.PREPARING, 0)
        if (previous?.metadataApplied == true && !attempt.replacement) {
            val summary = repairSummary(requireNotNull(previous.summary))
            return finishAttempt(attempt, summary)
        }
        return openAndRestore(attempt)
    }

    private suspend fun nativeValidationResult(native: NativeRestoreWorkState): Result? =
        when (native.validation()) {
            NativeRestoreWorkState.Validation.CURRENT -> null
            NativeRestoreWorkState.Validation.STALE -> Result.success()
            NativeRestoreWorkState.Validation.WAITING_FOR_SESSION -> Result.retry()
        }

    private suspend fun openAndRestore(attempt: Attempt): Result {
        val retained = if (attempt.previous != null && !attempt.replacement) store.source(recoveryId).takeIf { it.isFile } else null
        val uri = retained?.let(Uri::fromFile) ?: sourceUri ?: return missingSource(attempt.previous)
        val label =
            attempt.previous?.sourceLabel
                ?: context.contentResolver.resolveDisplayName(uri)
                ?: getString(Res.string.restore_source_label_fallback)
        emitProgress(RestoreStage.COPYING_ARCHIVE, 5)
        val file = retained ?: copyToCache(uri) ?: return Result.retry()
        try {
            emitProgress(RestoreStage.OPENING_ARCHIVE, 10)
            val zip =
                try {
                    ZipFile(file)
                } catch (_: java.util.zip.ZipException) {
                    return invalidArchive()
                }
            zip.use { return restoreArchive(attempt, file, it, label) }
        } finally {
            if (retained == null) file.delete()
            if (sourceAccepted || sourceRejected) sourceUri?.let(::deleteOwnedSource)
        }
    }

    private suspend fun restoreArchive(
        attempt: Attempt,
        file: File,
        zip: ZipFile,
        label: String,
    ): Result {
        emitProgress(RestoreStage.READING_CONTENTS, 20)
        val archive =
            try {
                RestoreArchiveReader.readBytes(zip.entries().toList().map { it.name }) { readOptionalEntry(zip, it) }.also {
                    app.logdate.client.domain.restore.ArchivePayloadValidator
                        .validate(it)
                }
            } catch (_: IllegalArgumentException) {
                return invalidArchive()
            } catch (_: IllegalStateException) {
                return invalidArchive()
            }
        nativeValidationResult(attempt.native)?.let { return it }
        check(currentArchiveRecoveryScope(getKoin()) == attempt.scope) { "Archive recovery account changed" }
        val record = store.begin(recoveryId, attempt.scope, file, ImportOptions(includeDrafts, includeMedia), label)
        if (store.read(recoveryId)?.pausedByUser == true) return Result.success()
        if (attempt.previous != null && attempt.replacement) store.replaceSource(recoveryId, file, id.toString())
        sourceAccepted = true
        emitProgress(RestoreStage.RESTORING_JOURNALS, 40)
        val base =
            if (record.metadataApplied && !(attempt.replacement && record.requiresSource)) {
                requireNotNull(record.summary)
            } else {
                val imported = restoreMetadata(attempt, archive, zip, label)
                val prior = record.summary
                if (prior == null) {
                    imported
                } else {
                    imported.copy(
                        journalsImported = prior.journalsImported + imported.journalsImported,
                        notesImported = prior.notesImported + imported.notesImported,
                        draftsImported = prior.draftsImported + imported.draftsImported,
                        journalLinksImported = prior.journalLinksImported + imported.journalLinksImported,
                        mediaImported = prior.mediaImported + imported.mediaImported,
                    )
                }
            }
        check(currentArchiveRecoveryScope(getKoin()) == attempt.scope) { "Archive recovery account changed" }
        store.finish(recoveryId, base.copy(recoveryId = recoveryId))
        val summary = repairSummary(base)
        return finishAttempt(attempt, summary)
    }

    private suspend fun finishAttempt(
        attempt: Attempt,
        summary: RestoreSummary,
    ): Result {
        if (summary.unavailableOriginals + summary.unavailablePreviews > 0) {
            ArchiveRecoveryScheduler.enqueueAll(
                context,
                getKoin(),
                ArchiveRecoveryReadinessChange(restartRunningWork = true),
            )
        }
        if (!attempt.native.complete()) return Result.retry()
        notificationHelper.showCompletionNotification(summary.unavailableOriginals + summary.unavailablePreviews)
        completeRestore(RestoreOutcome.Success(summary))
        reportDiagnostic(DiagnosticOutcome.SUCCEEDED, attempt.source, attempt.diagnosticId)
        return Result.success(workDataOf(SUMMARY_JSON_KEY to json.encodeToString(summary.forWorkData())))
    }

    private fun missingSource(record: ArchiveRecoveryRecord?): Result {
        if (record != null) {
            store.requireSource(record.id)
            restoreLauncher.updateRecovery(requireNotNull(store.read(record.id)).recoverySummary())
        }
        completeRestore(RestoreOutcome.Failure(RestoreError.MISSING_SOURCE, record?.id))
        return failure(RestoreError.MISSING_SOURCE, record?.id)
    }

    private fun invalidArchive(): Result {
        sourceRejected = true
        activeAttempt?.let { reportDiagnostic(DiagnosticOutcome.FAILED, it.source, it.diagnosticId, DiagnosticReason.CORRUPT_PAYLOAD) }
        val retainedId = runCatching { store.read(recoveryId)?.id }.getOrNull()
        if (retainedId != null) {
            store.requireSource(retainedId)
            restoreLauncher.updateRecovery(requireNotNull(store.read(retainedId)).recoverySummary())
        }
        completeRestore(RestoreOutcome.Failure(RestoreError.INVALID_ARCHIVE, retainedId))
        return failure(RestoreError.INVALID_ARCHIVE, retainedId)
    }

    private suspend fun restoreMetadata(
        attempt: Attempt,
        archive: app.logdate.client.domain.restore.RestoreArchiveBundle,
        zip: ZipFile,
        label: String,
    ): RestoreSummary {
        val index = ArchiveRecoveryMediaIndex(archive)
        return restoreUserDataUseCase
            .restore(
                archive = archive,
                options =
                    RestoreOptions(
                        includeDrafts = includeDrafts,
                        includeMedia = includeMedia,
                        preservePopulatedLocalProfile = preservePopulatedProfile || attempt.previous != null,
                    ),
                mediaImporter =
                    AndroidArchiveMediaImporter(
                        context,
                        mediaManager,
                        zip,
                        archive.root,
                        getKoin().get(),
                        archive.mediaIntegrity,
                        getKoin().get(),
                    ),
                onProgress = { phase ->
                    check(currentArchiveRecoveryScope(getKoin()) == attempt.scope) { "Archive recovery account changed" }
                    val info = phase.toProgressInfo()
                    if (!inputData.getBoolean(SILENT_RECOVERY_KEY, false)) restoreLauncher.updateProgress(info)
                    if (info is RestoreProgressInfo.Active) trySetForeground(notificationHelper.createForegroundInfo(info.stage))
                },
                onPendingMedia = { store.recordPending(recoveryId, it.copy(expectedSha256 = index.expectedChecksum(it))) },
                mediaRecoveryId = recoveryId,
                expectedNoteScope =
                    app.logdate.client.repository.journals
                        .JournalMergeScope(attempt.scope.ownerId, attempt.scope.serverOrigin),
            ).toSummary(label)
    }

    private suspend fun repairSummary(base: RestoreSummary): RestoreSummary {
        val record = requireNotNull(store.read(recoveryId))
        if (includeMedia && record.pending.isNotEmpty()) return ArchiveMediaRecoveryEngine(context, getKoin()).repair(record)
        val pending = record.pending
        return base
            .copy(
                recoveryId = recoveryId,
                recoveringMedia = false,
                unavailableOriginals = pending.count { it.role == RecoveryMediaRole.ORIGINAL },
                unavailablePreviews = pending.count { it.role == RecoveryMediaRole.THUMBNAIL },
            ).also { store.finish(recoveryId, it) }
    }

    private fun reportDiagnostic(
        outcome: DiagnosticOutcome,
        source: DiagnosticSource?,
        attemptId: String,
        reason: DiagnosticReason = DiagnosticReason.NONE,
    ) {
        val operationId =
            inputData
                .getString(RESTORE_OPERATION_ID_KEY)
                ?.takeIf(DiagnosticReportCodec::isCorrelationId)
                ?: id.toString()
        val event =
            SyncDiagnosticEvent(
                DiagnosticPhase.RESTORE,
                outcome,
                reason,
                operationId = operationId,
                attemptId = attemptId,
            )
        runCatching { diagnosticEvents?.record(event, source) }
    }

    private fun completeRestore(outcome: RestoreOutcome) {
        if (inputData.getBoolean(SILENT_RECOVERY_KEY, false)) {
            if (outcome is RestoreOutcome.Success) restoreLauncher.updateRecovery(outcome.summary)
            return
        }
        val androidLauncher = restoreLauncher as? AndroidRestoreLauncher
        if (androidLauncher != null) {
            androidLauncher.completeRestoreForWork(id, outcome)
        } else {
            restoreLauncher.completeRestore(outcome)
        }
    }

    private fun deleteOwnedSource(uri: Uri) {
        if (deleteSourceAfterRestore && uri.scheme == "file") {
            val file = File(uri.path ?: return)
            val retainedSources = context.filesDir.resolve("archive_recovery/sources").canonicalPath + File.separator
            if (file.canonicalPath.startsWith(retainedSources)) return
            runCatching { file.delete() }
                .onFailure { Napier.w("Failed to delete temporary cloud restore archive") }
        }
    }

    private suspend fun emitProgress(
        stage: RestoreStage,
        percent: Int,
    ) {
        if (!inputData.getBoolean(SILENT_RECOVERY_KEY, false)) restoreLauncher.updateProgress(stage.toProgressInfo(percent))
        trySetForeground(notificationHelper.createForegroundInfo(stage))
    }

    /**
     * Attempts to set foreground notification. If it fails (e.g. missing
     * POST_NOTIFICATIONS permission), the restore continues without notification.
     */
    private suspend fun trySetForeground(foregroundInfo: ForegroundInfo) {
        try {
            setForeground(foregroundInfo)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Napier.w("Could not show foreground notification, restore continues without it")
        }
    }

    private fun failure(
        error: RestoreError,
        recoveryId: String? = null,
    ): Result = Result.failure(workDataOf(ERROR_KEY to error.name, RECOVERY_ID_KEY to recoveryId))

    private fun copyToCache(uri: Uri): File? {
        val tempFile = File.createTempFile("logdate_restore", ".zip", context.cacheDir)
        return try {
            val input =
                context.contentResolver.openInputStream(uri) ?: run {
                    tempFile.delete()
                    return null
                }
            input.use {
                FileOutputStream(tempFile).use { output ->
                    it.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Napier.e("Failed to copy restore archive to cache")
            tempFile.delete()
            null
        }
    }

    private fun readOptionalEntry(
        zipFile: ZipFile,
        entryName: String,
    ): ByteArray? {
        val entry = zipFile.getEntry(entryName) ?: return null
        return zipFile.getInputStream(entry).use { input ->
            input.readBytes()
        }
    }
}
