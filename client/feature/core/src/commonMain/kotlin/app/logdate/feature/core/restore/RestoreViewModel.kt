package app.logdate.feature.core.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.export.ExportSchemaVersion
import app.logdate.client.domain.restore.ArchivePreview
import app.logdate.client.domain.restore.PreviewArchiveUseCase
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

sealed class RestoreState {
    data object Idle : RestoreState()

    data object Confirming : RestoreState()

    data object Selecting : RestoreState()

    data class Previewing(
        val preview: ArchivePreview,
        val fileName: String,
        val options: ImportOptions = ImportOptions(),
    ) : RestoreState()

    data class Restoring(
        val stage: RestoreStage = RestoreStage.PREPARING,
        val progressPercent: Int = 0,
    ) : RestoreState()

    data class Completed(
        val summary: RestoreSummary,
        val operationId: Uuid = Uuid.random(),
    ) : RestoreState()

    data class Failed(
        val error: RestoreError,
        val canRetry: Boolean = true,
        val recoveryId: String? = null,
    ) : RestoreState()
}

class UserDataRestoreViewModel(
    private val restoreLauncher: RestoreLauncher,
    private val previewArchiveUseCase: PreviewArchiveUseCase,
) : ViewModel() {
    private var restoreOperationId = Uuid.random()
    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _isSheetVisible = MutableStateFlow(false)
    val isSheetVisible: StateFlow<Boolean> = _isSheetVisible.asStateFlow()
    private var selectionRecoveryState: RestoreState? = null

    init {
        restoreLauncher.setRecoveryUpdateCallback { summary ->
            _restoreState.update { current ->
                if (current is RestoreState.Completed &&
                    current.summary.recoveryId == summary.recoveryId
                ) {
                    current.copy(summary = summary)
                } else {
                    current
                }
            }
            val previous = selectionRecoveryState as? RestoreState.Completed
            if (previous?.summary?.recoveryId == summary.recoveryId) selectionRecoveryState = previous.copy(summary = summary)
        }
        restoreLauncher.setRestoreCompletionCallback { outcome ->
            when (outcome) {
                is RestoreOutcome.Started -> {
                    _restoreState.update { RestoreState.Restoring() }
                }

                is RestoreOutcome.Cancelled -> {
                    _restoreState.update { RestoreState.Idle }
                    _isSheetVisible.value = false
                }

                is RestoreOutcome.Success -> {
                    selectionRecoveryState = null
                    _restoreState.update { RestoreState.Completed(outcome.summary, restoreOperationId) }
                    _isSheetVisible.value = true
                }

                is RestoreOutcome.Failure -> {
                    _restoreState.update {
                        RestoreState.Failed(outcome.error, recoveryId = outcome.recoveryId ?: recoveryIdentity(selectionRecoveryState))
                    }
                    _isSheetVisible.value = true
                }
            }
        }

        restoreLauncher.setFileSelectedCallback { fileInfo ->
            if (fileInfo == null) {
                _restoreState.value = selectionRecoveryState ?: RestoreState.Idle
                _isSheetVisible.value = selectionRecoveryState != null
                return@setFileSelectedCallback
            }
            try {
                val metadataJson = fileInfo.metadataJson ?: throw IllegalArgumentException("Missing archive metadata")
                val preview = previewArchiveUseCase.preview(metadataJson)
                if (preview.version.major > ExportSchemaVersion.CURRENT.major) {
                    _restoreState.update {
                        RestoreState.Failed(RestoreError.UNSUPPORTED_VERSION, recoveryId = recoveryIdentity(selectionRecoveryState))
                    }
                    return@setFileSelectedCallback
                }
                _restoreState.update {
                    RestoreState.Previewing(
                        preview = preview,
                        fileName = fileInfo.displayName,
                    )
                }
            } catch (e: Exception) {
                Napier.e("Failed to parse archive metadata")
                _restoreState.update {
                    RestoreState.Failed(RestoreError.INVALID_ARCHIVE, recoveryId = recoveryIdentity(selectionRecoveryState))
                }
            }
        }

        viewModelScope.launch {
            restoreLauncher.restoreProgress.collect { progress ->
                when (progress) {
                    is RestoreProgressInfo.Active -> {
                        _restoreState.update { current ->
                            if (current is RestoreState.Restoring) {
                                current.copy(
                                    stage = progress.stage,
                                    progressPercent = progress.progressPercent,
                                )
                            } else {
                                current
                            }
                        }
                    }

                    RestoreProgressInfo.Idle -> {
                        Unit
                    }
                }
            }
        }
    }

    fun showRestoreSheet() {
        val current = _restoreState.value
        if (current is RestoreState.Restoring ||
            current is RestoreState.Selecting ||
            current is RestoreState.Previewing ||
            current is RestoreState.Completed ||
            current is RestoreState.Failed
        ) {
            _isSheetVisible.value = true
            return
        }
        _restoreState.update { RestoreState.Confirming }
        _isSheetVisible.value = true
    }

    fun selectFile() {
        selectionRecoveryState = null
        _restoreState.update { RestoreState.Selecting }
        restoreLauncher.startFileSelection()
    }

    fun updateImportOptions(options: ImportOptions) {
        _restoreState.update { current ->
            if (current is RestoreState.Previewing) {
                current.copy(options = options)
            } else {
                current
            }
        }
    }

    fun confirmImport() {
        val current = _restoreState.value
        if (current !is RestoreState.Previewing) return
        restoreOperationId = Uuid.random()
        _restoreState.update { RestoreState.Restoring() }
        restoreLauncher.startRestore(current.options)
    }

    /**
     * Dismisses the bottom sheet. If a restore is in progress, it continues
     * running in the background — only [cancelRestore] stops it.
     */
    fun dismissSheet() {
        val current = _restoreState.value
        _isSheetVisible.value = false
        if (current is RestoreState.Completed &&
            (
                current.summary.needsAnotherArchive ||
                    current.summary.metadataIncomplete ||
                    current.summary.unavailableOriginals + current.summary.unavailablePreviews > 0
            )
        ) {
            return
        }
        if (current is RestoreState.Failed && current.recoveryId != null) return
        when (current) {
            is RestoreState.Confirming,
            is RestoreState.Previewing,
            is RestoreState.Completed,
            is RestoreState.Failed,
            -> _restoreState.update { selectionRecoveryState ?: RestoreState.Idle }

            else -> Unit
        }
    }

    /**
     * Explicitly cancels the running restore. This is the only way to stop
     * an in-progress restore — UI dismissal does not cancel it.
     */
    fun cancelRestore() {
        val current = _restoreState.value
        if (current is RestoreState.Restoring || current is RestoreState.Selecting) {
            restoreLauncher.cancelRestore()
            _restoreState.update { RestoreState.Idle }
            _isSheetVisible.value = false
        }
    }

    fun retryRestore() {
        val recoveryId = recoveryIdentity(_restoreState.value)
        if (recoveryId != null) {
            selectionRecoveryState = _restoreState.value
            _restoreState.value = RestoreState.Selecting
            restoreLauncher.selectArchiveForMediaRecovery(recoveryId)
            return
        }
        _restoreState.update { RestoreState.Selecting }
        _isSheetVisible.value = true
        restoreLauncher.startFileSelection()
    }

    private fun recoveryIdentity(state: RestoreState?): String? =
        when (state) {
            is RestoreState.Completed -> state.summary.recoveryId
            is RestoreState.Failed -> state.recoveryId
            else -> null
        }
}
