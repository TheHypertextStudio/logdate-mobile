package app.logdate.feature.core.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.export.ExportCounts
import app.logdate.client.domain.export.ExportStats
import app.logdate.client.domain.export.GetExportCountsUseCase
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.export_error_default

sealed class ExportState {
    data object Idle : ExportState()

    data class Configuring(
        val options: ExportOptions = ExportOptions(),
        val counts: ExportCounts? = null,
    ) : ExportState()

    data object Selecting : ExportState()

    data class Exporting(
        val progressPercent: Int = 0,
        val message: UiText? = null,
    ) : ExportState()

    data class Completed(
        val path: String,
        val fileName: String,
        val stats: ExportStats? = null,
    ) : ExportState()

    data class Failed(
        val reason: UiText,
        val canRetry: Boolean = true,
    ) : ExportState()
}

class UserDataExportViewModel(
    private val exportLauncher: ExportLauncher,
    private val getExportCountsUseCase: GetExportCountsUseCase,
) : ViewModel() {
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _isSheetVisible = MutableStateFlow(false)
    val isSheetVisible: StateFlow<Boolean> = _isSheetVisible.asStateFlow()

    private var lastExportOptions: ExportOptions = ExportOptions()

    init {
        // The callback fires only for the non-success outcomes; successful completion is
        // handled by the exportProgress flow, which carries stats. Cancellation and failure
        // are handled distinctly so a cancelled export returns to idle quietly while a real
        // failure surfaces its reason.
        exportLauncher.setExportCompletionCallback { outcome ->
            val current = _exportState.value
            // Guard against late callbacks overwriting a terminal state.
            if (current is ExportState.Completed || current is ExportState.Failed) {
                return@setExportCompletionCallback
            }
            if (current !is ExportState.Exporting && current !is ExportState.Selecting) {
                return@setExportCompletionCallback
            }
            when (outcome) {
                is ExportOutcome.Cancelled -> {
                    _exportState.update { ExportState.Idle }
                    _isSheetVisible.value = false
                }

                is ExportOutcome.Failed -> {
                    _exportState.update {
                        ExportState.Failed(outcome.reason ?: Res.string.export_error_default.toUiText())
                    }
                    _isSheetVisible.value = true
                }
            }
        }

        viewModelScope.launch {
            exportLauncher.exportProgress.collect { progressInfo ->
                val completedPath = progressInfo.completedFilePath
                if (completedPath != null) {
                    val fileName = completedPath.substringAfterLast("/").substringAfterLast(":")
                    _exportState.update {
                        ExportState.Completed(
                            path = completedPath,
                            fileName = fileName,
                            stats = progressInfo.stats,
                        )
                    }
                    _isSheetVisible.value = true
                } else if (progressInfo.isActive) {
                    _exportState.update { current ->
                        if (current !is ExportState.Completed) {
                            ExportState.Exporting(
                                progressPercent = progressInfo.progressPercent,
                                message = progressInfo.message,
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    fun showExportOptions() {
        val current = _exportState.value
        // If an export is active or showing results, just re-show the sheet
        if (current is ExportState.Exporting ||
            current is ExportState.Selecting ||
            current is ExportState.Completed ||
            current is ExportState.Failed
        ) {
            _isSheetVisible.value = true
            return
        }
        _exportState.update { ExportState.Configuring() }
        _isSheetVisible.value = true
        viewModelScope.launch {
            runCatching { getExportCountsUseCase() }
                .onSuccess { counts ->
                    _exportState.update { current ->
                        if (current is ExportState.Configuring) {
                            current.copy(counts = counts)
                        } else {
                            current
                        }
                    }
                }.onFailure { error ->
                    Napier.e("Failed to load export counts")
                }
        }
    }

    fun updateExportOptions(options: ExportOptions) {
        _exportState.update { current ->
            if (current is ExportState.Configuring) {
                current.copy(options = options)
            } else {
                current
            }
        }
    }

    /**
     * Dismisses the bottom sheet. If an export is in progress, it continues
     * running in the background — only [cancelExport] stops it.
     */
    fun dismissSheet() {
        val current = _exportState.value
        _isSheetVisible.value = false
        // Only reset state for pre-export or post-export states
        when (current) {
            is ExportState.Configuring,
            is ExportState.Completed,
            is ExportState.Failed,
            -> _exportState.update { ExportState.Idle }

            // Exporting/Selecting: export continues in background, state unchanged
            else -> Unit
        }
    }

    fun confirmExport() {
        val current = _exportState.value
        if (current is ExportState.Configuring) {
            lastExportOptions = current.options
        }
        _exportState.update { ExportState.Selecting }
        exportLauncher.startExport(lastExportOptions)
    }

    /**
     * Explicitly cancels the running export. This is the only way to stop
     * an in-progress export — UI dismissal does not cancel it.
     */
    fun cancelExport() {
        val current = _exportState.value
        if (current is ExportState.Exporting || current is ExportState.Selecting) {
            exportLauncher.cancelExport()
            _exportState.update { ExportState.Idle }
            _isSheetVisible.value = false
        }
    }

    fun retryExport() {
        _exportState.update { ExportState.Selecting }
        _isSheetVisible.value = true
        exportLauncher.startExport(lastExportOptions)
    }
}
