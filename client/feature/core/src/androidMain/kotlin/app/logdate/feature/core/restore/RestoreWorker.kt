package app.logdate.feature.core.restore

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.logdate.client.domain.restore.MediaImporter
import app.logdate.client.domain.restore.RestoreArchiveReader
import app.logdate.client.domain.restore.RestoreOptions
import app.logdate.client.domain.restore.RestoreUserDataUseCase
import app.logdate.client.media.MediaManager
import app.logdate.client.sync.diagnostics.DiagnosticSource
import app.logdate.shared.model.diagnostics.DiagnosticOutcome
import app.logdate.shared.model.diagnostics.DiagnosticPhase
import app.logdate.shared.model.diagnostics.DiagnosticReason
import app.logdate.shared.model.diagnostics.DiagnosticReportCodec
import app.logdate.shared.model.diagnostics.SyncDiagnosticEvent
import io.github.aakira.napier.Napier
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        const val SUMMARY_JSON_KEY = "restore_summary_json"
        const val ERROR_KEY = "restore_error"
        const val RESTORE_OPERATION_ID_KEY = "restore_diagnostic_operation_id"
    }

    private val restoreUserDataUseCase: RestoreUserDataUseCase by inject()
    private val mediaManager: MediaManager by inject()
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

    override suspend fun doWork(): Result {
        val source = runCatching { diagnosticEvents?.source() }.getOrNull()
        val attemptId = Uuid.random().toString()
        reportDiagnostic(DiagnosticOutcome.STARTED, source, attemptId)
        (restoreLauncher as? AndroidRestoreLauncher)?.beginRestoreWork(id)
        trySetForeground(getForegroundInfo())
        emitProgress(RestoreStage.PREPARING, 0)

        val restoreUri =
            sourceUri
                ?: run {
                    reportDiagnostic(DiagnosticOutcome.FAILED, source, attemptId, DiagnosticReason.LOCAL_STORAGE)
                    return failure(RestoreError.MISSING_SOURCE)
                }

        val sourceLabel = context.contentResolver.resolveDisplayName(restoreUri) ?: restoreUri.toString()

        emitProgress(RestoreStage.COPYING_ARCHIVE, 5)
        val tempFile =
            copyToCache(restoreUri)
                ?: run {
                    deleteOwnedSource(restoreUri)
                    reportDiagnostic(DiagnosticOutcome.FAILED, source, attemptId, DiagnosticReason.LOCAL_STORAGE)
                    return failure(RestoreError.FILE_NOT_ACCESSIBLE)
                }

        emitProgress(RestoreStage.OPENING_ARCHIVE, 10)

        val zipFile =
            runCatching { ZipFile(tempFile) }
                .getOrElse { error ->
                    tempFile.delete()
                    deleteOwnedSource(restoreUri)
                    completeRestore(RestoreOutcome.Failure(RestoreError.RESTORE_FAILED))
                    reportDiagnostic(DiagnosticOutcome.FAILED, source, attemptId, DiagnosticReason.CORRUPT_PAYLOAD)
                    return failure(RestoreError.INVALID_ARCHIVE)
                }

        return try {
            emitProgress(RestoreStage.READING_CONTENTS, 20)

            val archive =
                RestoreArchiveReader.read(zipFile.entries().toList().map { it.name }) { entryName ->
                    readOptionalEntry(zipFile, entryName)
                }
            val root = archive.root

            emitProgress(RestoreStage.RESTORING_JOURNALS, 40)

            val mediaImporter =
                if (includeMedia) {
                    object : MediaImporter {
                        override suspend fun importMedia(exportPath: String): String? =
                            this@RestoreWorker.importMedia(zipFile, root, exportPath)
                    }
                } else {
                    null
                }

            val options =
                RestoreOptions(
                    includeDrafts = includeDrafts,
                    includeMedia = includeMedia,
                    preservePopulatedLocalProfile = preservePopulatedProfile,
                )

            val result =
                restoreUserDataUseCase.restore(
                    archive = archive,
                    options = options,
                    mediaImporter = mediaImporter,
                    onProgress = { phase ->
                        val info = phase.toProgressInfo()
                        restoreLauncher.updateProgress(info)
                        if (info is RestoreProgressInfo.Active) {
                            trySetForeground(notificationHelper.createForegroundInfo(info.stage))
                        }
                    },
                )

            val summary = result.toSummary(source = sourceLabel)

            notificationHelper.showCompletionNotification()
            completeRestore(RestoreOutcome.Success(summary))
            reportDiagnostic(DiagnosticOutcome.SUCCEEDED, source, attemptId)

            Result.success(
                workDataOf(
                    SUMMARY_JSON_KEY to json.encodeToString(summary),
                ),
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                reportDiagnostic(DiagnosticOutcome.INTERRUPTED, source, attemptId)
                throw e
            }
            Napier.e("Restore failed")
            notificationHelper.showErrorNotification("Unable to restore this archive")
            completeRestore(RestoreOutcome.Failure(RestoreError.RESTORE_FAILED))
            reportDiagnostic(DiagnosticOutcome.FAILED, source, attemptId, DiagnosticReason.UNKNOWN)
            failure(RestoreError.RESTORE_FAILED)
        } finally {
            restoreLauncher.updateProgress(RestoreProgressInfo.Idle)
            zipFile.close()
            tempFile.delete()
            deleteOwnedSource(restoreUri)
        }
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
        val androidLauncher = restoreLauncher as? AndroidRestoreLauncher
        if (androidLauncher != null) {
            androidLauncher.completeRestoreForWork(id, outcome)
        } else {
            restoreLauncher.completeRestore(outcome)
        }
    }

    private fun deleteOwnedSource(uri: Uri) {
        if (deleteSourceAfterRestore && uri.scheme == "file") {
            runCatching { File(uri.path ?: "").delete() }
                .onFailure { Napier.w("Failed to delete temporary cloud restore archive") }
        }
    }

    private suspend fun emitProgress(
        stage: RestoreStage,
        percent: Int,
    ) {
        restoreLauncher.updateProgress(stage.toProgressInfo(percent))
        trySetForeground(notificationHelper.createForegroundInfo(stage))
    }

    /**
     * Attempts to set foreground notification. If it fails (e.g. missing
     * POST_NOTIFICATIONS permission), the restore continues without notification.
     */
    private suspend fun trySetForeground(foregroundInfo: ForegroundInfo) {
        try {
            setForeground(foregroundInfo)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not show foreground notification, restore continues without it")
        }
    }

    private fun failure(error: RestoreError): Result = Result.failure(workDataOf(ERROR_KEY to error.name))

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
    ): String? {
        val entry = zipFile.getEntry(entryName) ?: return null
        return zipFile.getInputStream(entry).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private suspend fun importMedia(
        zipFile: ZipFile,
        root: String,
        exportPath: String,
    ): String? {
        val normalizedPath = exportPath.trimStart('/')
        val entry = zipFile.getEntry(root + normalizedPath)
        if (entry == null) {
            Napier.w("Restore attachment missing")
            return null
        }
        if (entry.isDirectory) {
            Napier.w("Restore attachment is a directory")
            return null
        }
        val fileName = normalizedPath.substringAfterLast('/')
        val tempFile = File.createTempFile("logdate_media_", "_$fileName", context.cacheDir)
        try {
            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            val fromExtension = resolveMimeType(fileName)
            // Always read magic bytes once and reuse — avoids double file I/O when both
            // the extension path and the verification path would otherwise call detectMimeTypeFromBytes.
            val fromBytes = detectMimeTypeFromBytes(tempFile)
            val mimeType =
                when {
                    fromExtension == "application/octet-stream" -> {
                        if (fromBytes != "application/octet-stream") {
                            Napier.d("Restore attachment type detected")
                        }
                        fromBytes
                    }
                    fromBytes != "application/octet-stream" && fromBytes != fromExtension -> {
                        // Extension may be inferred (e.g. .jpg for a HEIC file from a bare
                        // MediaStore content URI). Magic bytes win when they disagree.
                        Napier.w("Restore attachment type mismatch")
                        fromBytes
                    }
                    else -> fromExtension
                }
            val savedPath =
                if (mimeType.startsWith("audio/")) {
                    saveAudioToInternalStorage(tempFile, fileName)
                } else {
                    mediaManager.saveMediaFromFile(
                        sourceFilePath = tempFile.absolutePath,
                        fileName = fileName,
                        mimeType = mimeType,
                    )
                }
            Napier.d("Restore attachment imported")
            return savedPath
        } catch (e: Exception) {
            Napier.e("Restore attachment import failed")
            return null
        } finally {
            tempFile.delete()
        }
    }

    private fun resolveMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType =
            if (extension.isNotBlank()) {
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            } else {
                null
            }
        return mimeType ?: "application/octet-stream"
    }

    private fun saveAudioToInternalStorage(
        sourceFile: File,
        fileName: String,
    ): String {
        val audioDir = File(context.filesDir, "audio_notes").apply { mkdirs() }
        val destFile = File(audioDir, fileName)
        sourceFile.copyTo(destFile, overwrite = true)
        return Uri.fromFile(destFile).toString()
    }

    private fun detectMimeTypeFromBytes(file: File): String =
        try {
            val header = ByteArray(16)
            file.inputStream().use { it.read(header) }
            when {
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() ->
                    "image/jpeg"
                header[0] == 0x89.toByte() &&
                    header[1] == 0x50.toByte() &&
                    header[2] == 0x4E.toByte() &&
                    header[3] == 0x47.toByte() ->
                    "image/png"
                header[0] == 0x52.toByte() &&
                    header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() &&
                    header[3] == 0x46.toByte() &&
                    header[8] == 0x57.toByte() &&
                    header[9] == 0x45.toByte() &&
                    header[10] == 0x42.toByte() &&
                    header[11] == 0x50.toByte() ->
                    "image/webp"
                header[0] == 0x47.toByte() &&
                    header[1] == 0x49.toByte() &&
                    header[2] == 0x46.toByte() &&
                    header[3] == 0x38.toByte() ->
                    "image/gif"
                header[4] == 0x66.toByte() &&
                    header[5] == 0x74.toByte() &&
                    header[6] == 0x79.toByte() &&
                    header[7] == 0x70.toByte() -> {
                    val brand = String(header, 8, 4, Charsets.US_ASCII)
                    when {
                        brand.startsWith("hei") || brand.startsWith("mif") || brand.startsWith("avi") -> "image/heic"
                        brand.startsWith("M4A") || brand.startsWith("m4a") -> "audio/mp4"
                        else -> "video/mp4"
                    }
                }
                else -> "application/octet-stream"
            }
        } catch (e: Exception) {
            Napier.w("Restore attachment type detection failed")
            "application/octet-stream"
        }
}
