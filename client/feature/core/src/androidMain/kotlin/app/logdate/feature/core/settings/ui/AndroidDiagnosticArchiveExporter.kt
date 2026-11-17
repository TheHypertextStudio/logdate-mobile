package app.logdate.feature.core.settings.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.logdate.client.feature.core.R
import app.logdate.client.sync.diagnostics.DiagnosticReportBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** A shareable ZIP stays in private cache and has a durable expiry worker. */
class AndroidDiagnosticArchiveExporter(
    private val context: Context,
    private val enqueueCleanup: suspend (File) -> Boolean = { archive ->
        val cleanup =
            OneTimeWorkRequestBuilder<DiagnosticReportCleanupWorker>()
                .setInitialDelay(REPORT_EXPIRY_MINUTES, TimeUnit.MINUTES)
                .setInputData(workDataOf(DiagnosticReportCleanupWorker.FILE_NAME_KEY to archive.name))
                .build()
        val future = WorkManager.getInstance(context).enqueue(cleanup).result
        suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    if (continuation.isActive) continuation.resume(runCatching { future.get() }.isSuccess)
                },
                Executor { command -> command.run() },
            )
            continuation.invokeOnCancellation { future.cancel(true) }
        }
    },
    private val launchShare: (File) -> Boolean = { file ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val share =
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            Intent
                .createChooser(share, context.getString(R.string.share_diagnostic_report))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        true
    },
) : DiagnosticArchiveExporter {
    override suspend fun export(bundle: DiagnosticReportBundle): Boolean {
        var prepared: File? = null
        val shared = AtomicBoolean(false)
        try {
            val file =
                withContext(Dispatchers.IO) {
                    val directory = reportDirectory(context)
                    if (!directory.exists() && !directory.mkdirs()) return@withContext null
                    val archive = File.createTempFile("report-", ".zip", directory)
                    prepared = archive
                    try {
                        DiagnosticBundleArchive.write(FileSystem.SYSTEM, archive.absolutePath.toPath(), bundle)
                        if (!enqueueCleanup(archive)) error("Unable to schedule private report expiry")
                        archive
                    } catch (cancelled: CancellationException) {
                        archive.delete()
                        throw cancelled
                    } catch (_: Exception) {
                        archive.delete()
                        null
                    }
                } ?: return false
            return withContext(Dispatchers.Main) {
                try {
                    launchShare(file).also { shared.set(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    file.delete()
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            if (!shared.get()) prepared?.delete()
            throw cancelled
        }
    }

    private companion object {
        const val REPORT_EXPIRY_MINUTES = 30L
    }
}

/** Deletes only a named file inside the app's private diagnostic cache. */
class DiagnosticReportCleanupWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val name = inputData.getString(FILE_NAME_KEY) ?: return Result.failure()
        if (!name.startsWith("report-") || !name.endsWith(".zip") || name.contains('/') || name.contains('\\')) {
            return Result.failure()
        }
        val file = File(reportDirectory(applicationContext), name)
        return if (!file.exists() || file.delete()) Result.success() else Result.retry()
    }

    companion object {
        const val FILE_NAME_KEY = "diagnostic_report_file_name"
    }
}

private fun reportDirectory(context: Context): File = File(context.cacheDir, "sync-diagnostic-reports")
