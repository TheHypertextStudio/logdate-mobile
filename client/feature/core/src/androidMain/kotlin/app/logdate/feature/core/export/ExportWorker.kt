package app.logdate.feature.core.export

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.logdate.client.domain.export.archive.ArchiveExportOptions
import app.logdate.client.domain.export.archive.ArchiveExportProgress
import app.logdate.client.domain.export.archive.ArchiveExportSummary
import app.logdate.client.domain.export.archive.ExportArchiveUseCase
import app.logdate.client.text.toUiText
import io.github.aakira.napier.Napier
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.export_error_archive_write
import logdate.client.feature.core.generated.resources.export_error_not_completed
import logdate.client.feature.core.generated.resources.export_progress_completed
import logdate.client.feature.core.generated.resources.export_progress_starting
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * WorkManager worker for exporting user data according to the LogDate export specification.
 */
class ExportWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    companion object {
        const val WORK_NAME = "export_user_data"
        const val PROGRESS_KEY = "export_progress"
        const val MESSAGE_KEY = "export_message"
        const val FILE_PATH_KEY = "export_file_path"
        const val ERROR_KEY = "export_error"
        const val DESTINATION_URI_KEY = "destination_uri"
        const val INCLUDE_JOURNALS_KEY = "include_journals"
        const val INCLUDE_NOTES_KEY = "include_notes"
        const val INCLUDE_DRAFTS_KEY = "include_drafts"
        const val INCLUDE_MEDIA_KEY = "include_media"
        const val DATE_CUTOFF_MILLIS_KEY = "date_cutoff_millis"
    }

    private val exportArchiveUseCase: ExportArchiveUseCase by inject()
    private val exportLauncher: ExportLauncher by inject()
    private val notificationHelper = ExportNotificationHelper(context, Uuid.parse(id.toString()))

    private val destinationUri: Uri? = inputData.getString(DESTINATION_URI_KEY)?.toUri()
    private val includeJournals: Boolean = inputData.getBoolean(INCLUDE_JOURNALS_KEY, true)
    private val includeNotes: Boolean = inputData.getBoolean(INCLUDE_NOTES_KEY, true)
    private val includeDrafts: Boolean = inputData.getBoolean(INCLUDE_DRAFTS_KEY, true)
    private val includeMedia: Boolean = inputData.getBoolean(INCLUDE_MEDIA_KEY, true)
    private val dateRangeCutoff: Instant? =
        inputData
            .getLong(DATE_CUTOFF_MILLIS_KEY, -1L)
            .takeIf { it >= 0 }
            ?.let { Instant.fromEpochMilliseconds(it) }

    override suspend fun doWork(): Result {
        Napier.i("ExportWorker.doWork() STARTED - export worker is executing")

        // Try to promote to foreground service — if this fails (e.g. missing
        // POST_NOTIFICATIONS permission on Android 13+), the export still runs.
        trySetForeground(getForegroundInfo())
        Napier.i("ExportWorker: Foreground service setup complete")

        return runArchiveExport()
    }

    /**
     * Where a 2.0 archive is written. [commit] puts a finished archive in its place and [discard]
     * removes whatever was written if the export does not finish.
     */
    private class ArchiveTarget(
        val stream: OutputStream,
        val path: String,
        val commit: () -> Unit,
        val discard: () -> Unit,
    )

    private fun openArchiveTarget(): ArchiveTarget? {
        val destination = destinationUri
        if (destination != null) {
            val stream = context.contentResolver.openOutputStream(destination) ?: return null
            return ArchiveTarget(stream, destination.toString(), commit = {}, discard = { discardDocument(destination) })
        }
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), generateExportFileName())
        val partial = File(file.parentFile, "${file.name}.part")
        return ArchiveTarget(
            FileOutputStream(partial),
            file.absolutePath,
            commit = { Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) },
            discard = { partial.delete() },
        )
    }

    /** Deletes a partly written document, or empties it when the provider cannot delete, so it cannot pass for a finished archive. */
    private fun discardDocument(uri: Uri) {
        val deleted =
            try {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } catch (failure: Exception) {
                Napier.w("Could not delete the partly written export")
                false
            }
        if (deleted) return
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.close()
        } catch (failure: Exception) {
            Napier.e("Could not remove or empty the partly written export")
        }
    }

    /**
     * Writes a 2.0 archive to its destination. If the export fails or is cancelled the partly
     * written file is removed, so a truncated archive never sits where a real one should, and a
     * file already at the destination is not replaced until the new archive is complete.
     */
    private suspend fun runArchiveExport(): Result {
        val target =
            try {
                openArchiveTarget()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Napier.e("Could not open the export destination")
                null
            } ?: return archiveFailed(Res.string.export_error_archive_write)
        var finished = false
        try {
            var summary: ArchiveExportSummary? = null
            var failure: StringResource? = null
            val options = ArchiveExportOptions(includeJournals, includeNotes, includeDrafts, includeMedia, from = dateRangeCutoff)
            ZipOutputStream(target.stream.buffered()).use { zip ->
                exportArchiveUseCase.export(options, ZipStreamArchiveContainer(zip)).collect { progress ->
                    when (progress) {
                        ArchiveExportProgress.Starting -> {
                            trySetForeground(notificationHelper.createForegroundInfo(0, getString(Res.string.export_progress_starting)))
                            emitProgress(0, Res.string.export_progress_starting)
                        }

                        is ArchiveExportProgress.InProgress -> {
                            val percent = (progress.fraction * 100).toInt()
                            trySetForeground(notificationHelper.createForegroundInfo(percent, getString(progress.stage.defaultMessage)))
                            emitProgress(percent, progress.stage.defaultMessage)
                        }

                        is ArchiveExportProgress.Completed -> {
                            summary = progress.summary
                        }

                        is ArchiveExportProgress.Failed -> {
                            failure = progress.error.defaultMessage
                        }
                    }
                }
            }
            val completed = summary
            if (failure != null || completed == null) {
                return archiveFailed(failure ?: Res.string.export_error_not_completed)
            }

            target.commit()
            finished = true
            return archiveSucceeded(target, completed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Throwable) {
            Napier.e("Archive export failed")
            return archiveFailed(Res.string.export_error_archive_write)
        } finally {
            // ZipOutputStream.close() skips closing the stream under it when finishing the zip fails.
            runCatching { target.stream.close() }
            if (!finished) target.discard()
        }
    }

    private suspend fun archiveSucceeded(
        target: ArchiveTarget,
        summary: ArchiveExportSummary,
    ): Result {
        trySetForeground(notificationHelper.createCompletionInfo(target.path))
        exportLauncher.updateProgress(
            ExportProgressInfo(
                isActive = false,
                progressPercent = 100,
                message = Res.string.export_progress_completed.toUiText(),
                completedFilePath = target.path,
                stats = summary.counts.toExportStats(),
            ),
        )
        val completedMessage = getString(Res.string.export_progress_completed)
        return Result.success(workDataOf(PROGRESS_KEY to 100, MESSAGE_KEY to completedMessage, FILE_PATH_KEY to target.path))
    }

    /**
     * Shows [reason] in the export notification and reports it as the work's failure. The words are
     * resolved here because the notification and the WorkManager output both carry plain text.
     */
    private suspend fun archiveFailed(reason: StringResource): Result {
        val message = getString(reason)
        trySetForeground(notificationHelper.createErrorInfo(message))
        return failureResult(message)
    }

    private fun failureResult(message: String): Result =
        Result.failure(
            workDataOf(
                ERROR_KEY to message,
            ),
        )

    /**
     * Updates in-app progress via the injected [ExportLauncher], which
     * the ViewModel observes through its [ExportLauncher.exportProgress] flow.
     */
    private fun emitProgress(
        percent: Int,
        message: StringResource,
    ) {
        exportLauncher.updateProgress(
            ExportProgressInfo(
                isActive = true,
                progressPercent = percent,
                message = message.toUiText(),
            ),
        )
    }

    /**
     * Attempts to set foreground notification. If it fails (e.g. missing
     * POST_NOTIFICATIONS permission), the export continues without notification.
     */
    private suspend fun trySetForeground(foregroundInfo: ForegroundInfo) {
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            Napier.w("Could not show foreground notification, export continues without it")
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notificationHelper.createForegroundInfo(
            progress = 0,
            message = getString(Res.string.export_progress_starting),
        )
}
