package app.logdate.feature.core.restore

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.logdate.client.domain.export.ExportFormat
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android-specific [RestoreLauncher] backed by Storage Access Framework and WorkManager.
 *
 * ## Completion signaling
 *
 * Terminal outcomes ([RestoreOutcome.Success], [RestoreOutcome.Failure],
 * [RestoreOutcome.Cancelled]) are delivered to the registered completion callback exactly
 * once per restore operation, regardless of which path fires first:
 *
 * 1. **Direct** — [RestoreWorker] calls [completeRestore] before returning, carrying the
 *    typed outcome directly.
 * 2. **Fallback** — The [WorkManager] LiveData observer in [setupWorkObserver] fires when
 *    the worker's terminal [WorkInfo.State] is delivered. This covers the case where the
 *    worker is killed before it can call [completeRestore].
 *
 * [restoreCompleted] gates the callback via compare-and-set so only the first path to
 * arrive fires it. It is reset for each WorkManager restore ID.
 *
 * ## File selection
 *
 * [onRestoreSourceSelected] extracts the v2 manifest or v1 metadata from the chosen archive for preview
 * without copying the archive when the provider allows it. This I/O is dispatched to [launcherScope] so it never
 * blocks the main thread. Any in-flight extraction is cancelled before a new one begins,
 * tracked by [metadataExtractionJob].
 */
class AndroidRestoreLauncher(
    private val context: Context,
) : RestoreLauncher,
    org.koin.core.component.KoinComponent {
    private val json = Json { ignoreUnknownKeys = true }
    private val metadataReader = ArchiveMetadataReader(context)

    /**
     * Scope for metadata extraction coroutines. Lives for the lifetime of this singleton;
     * individual jobs are tracked via [metadataExtractionJob] and cancelled as needed.
     */
    private val launcherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Guards the completion callback so it fires at most once per restore operation.
     * Reset in [beginRestoreWork]; set atomically in [completeRestore] and the WorkManager
     * fallback observer.
     */
    private val restoreCompleted = AtomicBoolean(false)
    private val workCompletionLock = Any()

    @Volatile private var activeWorkId: UUID? = null

    @Volatile private var completedWorkId: UUID? = null
    private var enqueueJob: Job? = null
    private var reconnectJob: Job? = null
    private var recoveryReadinessJob: Job? = null

    /** The active metadata extraction job, if any. Cancelled before starting a new one. */
    private var metadataExtractionJob: Job? = null
    private val pickerRequests = ArchivePickerRequestGate()

    private var lastSelectedUri: Uri? = null
    private var selectedRecoveryId: String? = null
    private var workInfoObserver: Observer<List<WorkInfo>>? = null

    @Volatile private var completionCallback: ((RestoreOutcome) -> Unit)? = null

    @Volatile private var recoveryUpdateCallback: ((RestoreSummary) -> Unit)? = null

    @Volatile private var fileSelectedCallback: ((ArchiveFileInfo?) -> Unit)? = null

    private val _restoreProgress = MutableStateFlow<RestoreProgressInfo>(RestoreProgressInfo.Idle)
    override val restoreProgress: StateFlow<RestoreProgressInfo> = _restoreProgress.asStateFlow()

    private var openDocumentLauncher: ActivityResultLauncher<Intent>? = null

    /**
     * Registers the SAF [launcher] used to open the system file picker.
     * Must be called from the host activity before [startFileSelection].
     */
    fun setupActivityResultLauncher(launcher: ActivityResultLauncher<Intent>) {
        openDocumentLauncher = launcher
    }

    /**
     * Attaches a lifecycle-aware WorkManager observer for this [lifecycleOwner].
     *
     * The observer acts as a fallback completion signal: it fires the callback only if
     * [completeRestore] was not already called by the worker. Must be called from the
     * host activity; typically in `onCreate`.
     */
    fun setupWorkObserver(lifecycleOwner: LifecycleOwner) {
        if (recoveryReadinessJob?.isActive != true) {
            recoveryReadinessJob =
                launcherScope.launch {
                    observeCurrentArchiveRecovery(context, getKoin())
                }
        }
        workInfoObserver?.let {
            WorkManager
                .getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(RestoreWorker.WORK_NAME)
                .removeObserver(it)
        }

        workInfoObserver =
            Observer { workInfoList ->
                synchronized(workCompletionLock) {
                    val currentWorkId = activeWorkId ?: return@Observer
                    val workInfo = workInfoList.firstOrNull { it.id == currentWorkId } ?: return@Observer

                    val outcome =
                        when (workInfo.state) {
                            WorkInfo.State.SUCCEEDED -> {
                                completedWorkOutcome(workInfo)
                            }

                            WorkInfo.State.FAILED -> {
                                RestoreOutcome.Failure(
                                    RestoreError.RESTORE_FAILED,
                                    workInfo.outputData.getString(RestoreWorker.RECOVERY_ID_KEY),
                                )
                            }

                            WorkInfo.State.CANCELLED -> {
                                RestoreOutcome.Cancelled
                            }

                            else -> {
                                null
                            }
                        }
                    outcome?.let { completeRestoreForWork(currentWorkId, it) }
                }
            }

        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWorkLiveData(RestoreWorker.WORK_NAME)
            .observe(lifecycleOwner, workInfoObserver!!)
    }

    private fun completedWorkOutcome(workInfo: WorkInfo): RestoreOutcome {
        val summaryJson = workInfo.outputData.getString(RestoreWorker.SUMMARY_JSON_KEY)
        val summary = summaryJson?.let { runCatching { json.decodeFromString<RestoreSummary>(it) }.getOrNull() }
        if (summary != null) return RestoreOutcome.Success(summary)
        return RestoreOutcome.Failure(if (summaryJson == null) RestoreError.NO_SUMMARY_RETURNED else RestoreError.INVALID_SUMMARY)
    }

    override fun setRestoreCompletionCallback(callback: (RestoreOutcome) -> Unit) {
        synchronized(workCompletionLock) {
            invalidateArchiveSelection()
            completionCallback = callback
            reconnectJob?.cancel()
            reconnectJob =
                launcherScope.launch {
                    try {
                        ArchiveRecoveryLauncher(context, getKoin()).reconnect(completedWorkId, { workId ->
                            beginRestoreWork(workId)
                            if (completionCallback === callback) callback(RestoreOutcome.Started)
                        }, ::commitRestoreWork) { outcome ->
                            if (activeWorkId == null && completionCallback === callback) {
                                if (outcome is RestoreOutcome.Success) selectedRecoveryId = outcome.summary.recoveryId
                                callback(outcome)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Napier.w("Could not reconnect archive recovery")
                    }
                }
        }
    }

    override fun setRecoveryUpdateCallback(callback: (RestoreSummary) -> Unit) {
        recoveryUpdateCallback = callback
    }

    override fun updateRecovery(summary: RestoreSummary) {
        recoveryUpdateCallback?.invoke(summary)
    }

    override fun setFileSelectedCallback(callback: (ArchiveFileInfo?) -> Unit) {
        synchronized(workCompletionLock) {
            invalidateArchiveSelection()
            fileSelectedCallback = callback
        }
    }

    override fun updateProgress(info: RestoreProgressInfo) {
        _restoreProgress.value = info
    }

    /**
     * Primary completion signal, called directly by [RestoreWorker] before it returns.
     *
     * Fires the completion callback exactly once. If the WorkManager fallback observer
     * arrives first (rare), this call is a no-op.
     */
    override fun completeRestore(outcome: RestoreOutcome) {
        synchronized(workCompletionLock) {
            _restoreProgress.value = RestoreProgressInfo.Idle
            if (restoreCompleted.compareAndSet(false, true)) {
                completedWorkId = activeWorkId
                activeWorkId = null
                completionCallback?.invoke(outcome)
            }
        }
    }

    /** Every WorkManager restore gets its own completion gate, including queued cloud restores. */
    fun beginRestoreWork(workId: UUID) {
        synchronized(workCompletionLock) {
            if (activeWorkId != workId) {
                activeWorkId = workId
                restoreCompleted.set(false)
            }
        }
    }

    /** An older queued worker cannot report completion after a newer restore has started. */
    fun completeRestoreForWork(
        workId: UUID,
        outcome: RestoreOutcome,
    ) {
        synchronized(workCompletionLock) {
            if (activeWorkId != workId) return
            completeRestore(outcome)
        }
    }

    override fun selectArchiveForMediaRecovery(recoveryId: String) {
        synchronized(workCompletionLock) {
            invalidateArchiveSelection()
            selectedRecoveryId = recoveryId
            reconnectJob?.cancel()
            enqueueJob?.cancel()
            enqueueJob =
                launcherScope.launch {
                    val operationContext = currentCoroutineContext()
                    val resumed =
                        try {
                            ArchiveRecoveryLauncher(context, getKoin()).resume(recoveryId, ::commitRestoreWork)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            reportArchiveResumeFailure(
                                operationContext,
                                ::commitRestoreWork,
                                pause = { context.archiveRecoveryStore().setPaused(recoveryId, true) },
                                notifyFailure = {
                                    completionCallback?.invoke(RestoreOutcome.Failure(RestoreError.RESTORE_FAILED, recoveryId))
                                },
                            )
                            return@launch
                        }
                    if (resumed != null) {
                        commitArchiveResumeRequest(operationContext, ::commitRestoreWork) {
                            completionCallback?.invoke(RestoreOutcome.Success(resumed))
                        }
                    } else {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            commitArchiveResumeRequest(operationContext, ::commitRestoreWork) {
                                startFileSelection()
                                selectedRecoveryId = recoveryId
                            }
                        }
                    }
                }
        }
    }

    override fun startFileSelection() {
        synchronized(workCompletionLock) {
            reconnectJob?.cancel()
            enqueueJob?.cancel()
            invalidateArchiveSelection()
            selectedRecoveryId = null
            pickerRequests.beginSelection()?.let(::launchArchivePicker)
        }
    }

    private fun launchArchivePicker(request: Long) {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, ExportFormat.ACCEPTED_IMPORT_MIME_TYPES)
            }

        try {
            openDocumentLauncher?.launch(intent) ?: run {
                pickerRequests.launchFailed(request)
                Napier.w("No document launcher available for restore")
                if (pickerRequests.isCurrent(request)) {
                    completionCallback?.invoke(RestoreOutcome.Failure(RestoreError.FILE_PICKER_UNAVAILABLE))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            pickerRequests.launchFailed(request)
            Napier.e("Error launching restore file picker")
            if (pickerRequests.isCurrent(request)) {
                completionCallback?.invoke(RestoreOutcome.Failure(RestoreError.FILE_PICKER_FAILED))
            }
        }
    }

    override fun startRestore(options: ImportOptions) {
        val uri =
            lastSelectedUri ?: run {
                Napier.w("No URI selected for restore")
                completionCallback?.invoke(RestoreOutcome.Failure(RestoreError.MISSING_SOURCE))
                return
            }

        completionCallback?.invoke(RestoreOutcome.Started)
        _restoreProgress.value = RestoreProgressInfo.Idle

        val recoveryId = selectedRecoveryId
        val session = getKoin().get<app.logdate.client.datastore.SessionStorage>().getOriginBoundSession()
        val backend = getKoin().get<app.logdate.shared.config.LogDateConfigRepository>().getCurrentBackendUrl().trimEnd('/')
        synchronized(workCompletionLock) {
            reconnectJob?.cancel()
            enqueueJob?.cancel()
            enqueueJob =
                launcherScope.launch {
                    val operationContext = currentCoroutineContext()
                    try {
                        ArchiveRecoveryLauncher(
                            context,
                            getKoin(),
                        ).enqueueSelected(uri, options, recoveryId, session, backend, ::beginRestoreWork, ::commitRestoreWork)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Napier.w("Could not enqueue archive recovery")
                        commitArchiveResumeRequest(operationContext, ::commitRestoreWork) {
                            completionCallback?.invoke(RestoreOutcome.Failure(RestoreError.RESTORE_FAILED, recoveryId))
                        }
                    }
                }
        }
    }

    override fun cancelRestore() {
        synchronized(workCompletionLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            enqueueJob?.cancel()
            enqueueJob = null
            invalidateArchiveSelection()
            val activeId = activeWorkId?.toString()
            val store = context.archiveRecoveryStore()
            runCatching {
                listOfNotNull(activeId, selectedRecoveryId).distinct().forEach {
                    store.setPaused(it, true)
                    ArchiveRecoveryScheduler.cancel(context, it)
                }
            }.onFailure { Napier.w("Could not pause retained recovery") }
            WorkManager.getInstance(context).cancelUniqueWork(RestoreWorker.WORK_NAME)
            WorkManager.getInstance(context).cancelAllWorkByTag(CloudRestoreWorker.WORK_TAG)
            _restoreProgress.value = RestoreProgressInfo.Idle
            if (restoreCompleted.compareAndSet(false, true)) {
                completionCallback?.invoke(RestoreOutcome.Cancelled)
            }
            Napier.i("Restore cancelled")
        }
    }

    private fun commitRestoreWork(action: () -> Unit) {
        synchronized(workCompletionLock) { action() }
    }

    /**
     * Called when the user selects a restore archive or dismisses the file picker.
     *
     * Reads the v2 manifest or v1 metadata from the archive on [launcherScope] for preview. A provider that
     * cannot be opened in place has its archive copied into the cache first; that copy stops
     * when the extraction is cancelled. Any previously in-flight extraction is cancelled first. On
     * success, delivers an [ArchiveFileInfo] to the file-selected callback. On failure
     * (unparseable archive, missing entry, or permission error), delivers a null file
     * info and an [RestoreOutcome.Failure] to the completion callback.
     */
    fun onRestoreSourceSelected(uri: Uri?) {
        synchronized(workCompletionLock) {
            when (val result = pickerRequests.consumeResult()) {
                ArchivePickerResult.Ignored -> {
                    Unit
                }

                is ArchivePickerResult.Launch -> {
                    launchArchivePicker(result.request)
                }

                is ArchivePickerResult.Selected -> {
                    if (uri == null) {
                        Napier.i("User cancelled restore source selection")
                        lastSelectedUri = null
                        fileSelectedCallback?.invoke(null)
                    } else {
                        readSelectedArchive(result.request, uri)
                    }
                }
            }
        }
    }

    private fun readSelectedArchive(
        request: Long,
        uri: Uri,
    ) {
        metadataExtractionJob?.cancel()
        metadataExtractionJob =
            launcherScope.launch {
                val operationContext = currentCoroutineContext()
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    operationContext.ensureActive()
                    val displayName = context.contentResolver.resolveDisplayName(uri) ?: uri.lastPathSegment ?: "archive"
                    operationContext.ensureActive()
                    val metadataJson = metadataReader.read(uri)
                    commitArchiveSelectionRequest(operationContext, request, pickerRequests, ::commitRestoreWork) {
                        if (metadataJson == null) {
                            lastSelectedUri = null
                            Napier.w("Could not extract metadata from selected archive")
                            fileSelectedCallback?.invoke(null)
                            completionCallback?.invoke(RestoreOutcome.Failure(RestoreError.INVALID_ARCHIVE))
                        } else {
                            lastSelectedUri = uri
                            fileSelectedCallback?.invoke(ArchiveFileInfo(displayName, uri.toString(), metadataJson))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    commitArchiveSelectionRequest(operationContext, request, pickerRequests, ::commitRestoreWork) {
                        lastSelectedUri = null
                        Napier.w("Could not read the selected archive")
                        completionCallback?.invoke(RestoreOutcome.Failure(RestoreError.FILE_NOT_ACCESSIBLE))
                    }
                }
            }
    }

    private fun invalidateArchiveSelection() {
        pickerRequests.invalidate()
        metadataExtractionJob?.cancel()
        metadataExtractionJob = null
        lastSelectedUri = null
    }
}

internal fun commitArchiveResumeRequest(
    operationContext: kotlin.coroutines.CoroutineContext,
    commit: (() -> Unit) -> Unit,
    action: () -> Unit,
) {
    commit {
        operationContext.ensureActive()
        action()
    }
}

internal fun reportArchiveResumeFailure(
    operationContext: kotlin.coroutines.CoroutineContext,
    commit: (() -> Unit) -> Unit,
    pause: () -> Unit,
    notifyFailure: () -> Unit,
) {
    commitArchiveResumeRequest(operationContext, commit) {
        try {
            pause()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Napier.w("Could not retain the recovery pause")
        }
        Napier.w("Archive recovery remains available to retry")
        notifyFailure()
    }
}
