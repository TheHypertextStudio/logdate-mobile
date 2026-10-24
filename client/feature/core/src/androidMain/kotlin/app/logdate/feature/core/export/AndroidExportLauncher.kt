package app.logdate.feature.core.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.logdate.client.domain.export.ExportFormat
import app.logdate.client.text.toUiText
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.export_error_destination_access
import logdate.client.feature.core.generated.resources.export_progress_preparing
import java.util.UUID

/**
 * Android-specific [ExportLauncher] backed by Storage Access Framework and WorkManager.
 *
 * ## Completion signaling
 *
 * Success and failure use distinct channels to preserve [ExportStats] delivery:
 *
 * - **Success** — [ExportWorker] calls [updateProgress] with a non-null
 *   [ExportProgressInfo.completedFilePath]. The ViewModel observes [exportProgress],
 *   which carries the full stats. The WorkManager observer takes no action on `SUCCEEDED`.
 * - **Failure / cancellation** — The WorkManager LiveData observer fires [completionCallback]
 *   with [ExportOutcome.Failed] (carrying the worker's error message) or
 *   [ExportOutcome.Cancelled], depending on the terminal state. This is the only signal for
 *   these cases; there is no direct worker call.
 *
 * This asymmetry keeps success single-path (flow only) and failure single-path (observer
 * only), avoiding the need for a once-fire guard.
 */
class AndroidExportLauncher(
    private val context: Context,
) : ExportLauncher {
    private var pendingExportCallback: (() -> Unit)? = null
    private var lastSelectedUri: Uri? = null
    private var workInfoObserver: Observer<List<WorkInfo>>? = null
    private var pendingExportOptions: ExportOptions = ExportOptions()

    @Volatile private var completionCallback: ((ExportOutcome) -> Unit)? = null

    /**
     * Id of the export [androidx.work.WorkRequest] this launcher most recently started.
     * The WorkManager LiveData replays the last persisted terminal [WorkInfo] whenever the
     * observer (re-)attaches, so a failure from an earlier session would otherwise be
     * reported again on every screen open. Guarding on this id makes the observer act only
     * on the current export's terminal transition.
     */
    @Volatile private var currentWorkId: UUID? = null

    private val _exportProgress = MutableStateFlow(ExportProgressInfo())
    override val exportProgress: StateFlow<ExportProgressInfo> = _exportProgress.asStateFlow()

    /** SAF launcher for `ACTION_CREATE_DOCUMENT`. Set by the host activity before [startExport]. */
    private var createDocumentLauncher: ActivityResultLauncher<Intent>? = null

    /**
     * Registers the SAF [launcher] used to show the system file-save dialog.
     * Must be called from the host activity before [startExport].
     */
    fun setupActivityResultLauncher(launcher: ActivityResultLauncher<Intent>) {
        createDocumentLauncher = launcher
    }

    /**
     * Attaches a lifecycle-aware WorkManager observer for this [lifecycleOwner].
     *
     * Handles failure and cancellation only — success is signaled via [updateProgress].
     * Must be called from the host activity; typically in `onCreate`.
     */
    fun setupWorkObserver(lifecycleOwner: LifecycleOwner) {
        workInfoObserver?.let {
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(ExportWorker.WORK_NAME)
                .removeObserver(it)
        }

        workInfoObserver =
            Observer { workInfoList ->
                if (workInfoList.isEmpty()) return@Observer
                val workInfo = workInfoList[0]

                // Ignore terminal WorkInfos that aren't from the export this launcher
                // started (stale results replayed on observer re-attach).
                if (workInfo.state.isFinished && workInfo.id != currentWorkId) {
                    return@Observer
                }

                when (workInfo.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        // Success is signaled via updateProgress() by the worker.
                        // The exportProgress flow is the source of truth for success.
                        currentWorkId = null
                        Napier.i("Export work succeeded")
                    }

                    WorkInfo.State.FAILED -> {
                        currentWorkId = null
                        _exportProgress.value = ExportProgressInfo()
                        val reason = workInfo.outputData.getString(ExportWorker.ERROR_KEY)
                        Napier.i("Export failed")
                        completionCallback?.invoke(ExportOutcome.Failed(reason?.toUiText()))
                    }

                    WorkInfo.State.CANCELLED -> {
                        currentWorkId = null
                        _exportProgress.value = ExportProgressInfo()
                        Napier.i("Export cancelled")
                        completionCallback?.invoke(ExportOutcome.Cancelled)
                    }

                    else -> {
                        Unit
                    }
                }
            }

        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(ExportWorker.WORK_NAME)
            .observe(lifecycleOwner, workInfoObserver!!)
    }

    override fun updateProgress(info: ExportProgressInfo) {
        Napier.d("Export progress changed")
        _exportProgress.value = info
    }

    override fun setExportCompletionCallback(callback: (ExportOutcome) -> Unit) {
        completionCallback = callback
    }

    override fun startExport(options: ExportOptions) {
        pendingExportOptions = options
        val defaultFileName = generateExportFileName()

        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = ExportFormat.MIME_TYPE
                putExtra(Intent.EXTRA_TITLE, defaultFileName)
            }

        try {
            pendingExportCallback = {
                if (lastSelectedUri != null) {
                    startExportWorker(lastSelectedUri!!)
                } else {
                    Napier.w("No URI selected for export")
                    completionCallback?.invoke(ExportOutcome.Cancelled)
                }
            }

            createDocumentLauncher?.launch(intent) ?: run {
                Napier.w("No document launcher available, falling back to default export path")
                startExportWorker(null)
            }
        } catch (e: Exception) {
            Napier.e("Error launching file picker")
            startExportWorker(null)
        }
    }

    override fun cancelExport() {
        pendingExportCallback = null
        WorkManager.getInstance(context).cancelUniqueWork(ExportWorker.WORK_NAME)
        _exportProgress.value = ExportProgressInfo()
        completionCallback?.invoke(ExportOutcome.Cancelled)
        Napier.i("Export cancelled")
    }

    /**
     * Called when the user selects an export destination or dismisses the file-save dialog.
     *
     * Takes a persistent write permission on the URI before starting the worker. If [uri]
     * is null, the user cancelled and the completion callback is notified.
     */
    fun onExportDestinationSelected(uri: Uri?) {
        lastSelectedUri = uri

        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                Napier.i("Export destination selected")
                pendingExportCallback?.invoke()
            } catch (e: Exception) {
                Napier.e("Failed to take persistent URI permission")
                completionCallback?.invoke(ExportOutcome.Failed(Res.string.export_error_destination_access.toUiText()))
            }
        } else {
            Napier.i("User cancelled export destination selection")
            completionCallback?.invoke(ExportOutcome.Cancelled)
        }

        pendingExportCallback = null
    }

    /**
     * Enqueues an [ExportWorker] with the given destination [uri].
     *
     * If [uri] is null, the worker saves to the public Downloads directory instead.
     * Immediately emits an active progress state so the UI transitions out of [Selecting]
     * before the worker starts.
     */
    private fun startExportWorker(uri: Uri?) {
        Napier.i("Starting export worker")
        _exportProgress.value =
            ExportProgressInfo(
                isActive = true,
                progressPercent = 0,
                message = Res.string.export_progress_preparing.toUiText(),
            )

        val dataBuilder =
            Data
                .Builder()
                .putBoolean(ExportWorker.INCLUDE_JOURNALS_KEY, pendingExportOptions.includeJournals)
                .putBoolean(ExportWorker.INCLUDE_NOTES_KEY, pendingExportOptions.includeNotes)
                .putBoolean(ExportWorker.INCLUDE_DRAFTS_KEY, pendingExportOptions.includeDrafts)
                .putBoolean(ExportWorker.INCLUDE_MEDIA_KEY, pendingExportOptions.includeMedia)
                .putLong(
                    ExportWorker.DATE_CUTOFF_MILLIS_KEY,
                    pendingExportOptions.dateRange.toCutoffInstant()?.toEpochMilliseconds() ?: -1L,
                )

        if (uri != null) {
            dataBuilder.putString(ExportWorker.DESTINATION_URI_KEY, uri.toString())
        }

        val inputData = dataBuilder.build()

        val workRequest =
            OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        currentWorkId = workRequest.id

        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(
                ExportWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )

        Napier.i("Export work enqueued with WorkManager")
    }
}
