package app.logdate.feature.editor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Status of the autosave operation.
 */
enum class AutoSaveStatus {
    IDLE, // No autosave in progress
    SAVING, // Currently saving content
    SAVED, // Content was successfully saved
    ERROR, // Error occurred during saving
}

/**
 * Data class representing the auto-save state.
 */
data class AutoSaveState(
    val status: AutoSaveStatus = AutoSaveStatus.IDLE,
    val lastSavedTimestamp: Long? = null,
    val error: Throwable? = null,
    val saveAttempts: Int = 0,
)

/** Serializes autosave requests and owns retry and saved-content bookkeeping. */
internal class AutoSaveCoordinator<T>(
    private val maxSaveAttempts: Int,
    private val retryInitialDelayMs: Long,
    private val contentHash: (T) -> String,
    private val hasContentChanged: (T, String?) -> Boolean,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onStateChange: (AutoSaveState) -> Unit,
) {
    private val saveMutex = Mutex()
    private var state = AutoSaveState()
    private var lastSavedContentHash: String? = null

    suspend fun save(
        content: T,
        latestContent: () -> T,
        latestOnSave: () -> suspend (T) -> Boolean,
    ) = saveMutex.withLock {
        val requestedHash = contentHash(content)
        if (contentHash(latestContent()) != requestedHash) return@withLock
        if (!hasContentChanged(content, lastSavedContentHash)) return@withLock

        updateState(
            state.copy(
                status = AutoSaveStatus.SAVING,
                error = null,
                saveAttempts = 0,
            ),
        )

        val attemptBound = maxSaveAttempts.coerceAtLeast(1)
        var failedAttempts = 0
        while (failedAttempts < attemptBound) {
            if (contentHash(latestContent()) != requestedHash) {
                updateState(
                    state.copy(
                        status = AutoSaveStatus.IDLE,
                        error = null,
                        saveAttempts = 0,
                    ),
                )
                return@withLock
            }

            try {
                val persisted = latestOnSave()(content)
                if (!persisted) {
                    updateState(
                        state.copy(
                            status = AutoSaveStatus.IDLE,
                            error = null,
                            saveAttempts = 0,
                        ),
                    )
                    return@withLock
                }
                lastSavedContentHash = requestedHash
                val contentIsStillCurrent = contentHash(latestContent()) == requestedHash
                updateState(
                    AutoSaveState(
                        status = if (contentIsStillCurrent) AutoSaveStatus.SAVED else AutoSaveStatus.IDLE,
                        lastSavedTimestamp = now(),
                        saveAttempts = failedAttempts,
                    ),
                )
                return@withLock
            } catch (cancellation: CancellationException) {
                updateState(
                    state.copy(
                        status = AutoSaveStatus.IDLE,
                        error = null,
                        saveAttempts = 0,
                    ),
                )
                throw cancellation
            } catch (error: Exception) {
                if (contentHash(latestContent()) != requestedHash) {
                    updateState(
                        state.copy(
                            status = AutoSaveStatus.IDLE,
                            error = null,
                            saveAttempts = 0,
                        ),
                    )
                    return@withLock
                }
                failedAttempts++
                if (failedAttempts >= attemptBound) {
                    Napier.e("Auto-save failed after $failedAttempts attempt(s): ${error.message}", error)
                    updateState(
                        state.copy(
                            status = AutoSaveStatus.ERROR,
                            error = error,
                            saveAttempts = failedAttempts,
                        ),
                    )
                    return@withLock
                }

                updateState(
                    state.copy(
                        status = AutoSaveStatus.SAVING,
                        error = error,
                        saveAttempts = failedAttempts,
                    ),
                )
                try {
                    delay(retryInitialDelayMs * failedAttempts)
                } catch (cancellation: CancellationException) {
                    updateState(
                        state.copy(
                            status = AutoSaveStatus.IDLE,
                            error = null,
                            saveAttempts = 0,
                        ),
                    )
                    throw cancellation
                }
            }
        }
    }

    private fun updateState(updated: AutoSaveState) {
        state = updated
        onStateChange(updated)
    }

    fun clearIndicator(expectedState: AutoSaveState) {
        if (state == expectedState && state.status in setOf(AutoSaveStatus.SAVED, AutoSaveStatus.ERROR)) {
            updateState(state.copy(status = AutoSaveStatus.IDLE))
        }
    }
}

internal suspend fun <T> runPeriodicBackups(
    intervalMs: Long,
    latestContent: () -> T,
    latestOnSave: () -> suspend (T) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        delay(intervalMs)
        latestOnSave()(latestContent())
    }
}

/**
 * A composable function that handles auto-saving editor content.
 * This provides debounced saving, regular backup saves, and error handling with retries.
 *
 * @param content The content to monitor for changes and save
 * @param onSave Persists content and returns true only when the durable write completed
 * @param hasContentChanged Function to determine if content has meaningful changes
 * @param debounceMs Time in milliseconds to wait after changes before saving (default: 2000ms)
 * @param backupIntervalMs Interval for periodic backup saves (default: 30000ms)
 * @param indicatorDisplayMs Time to display save indicators before returning to IDLE (default: 2000ms)
 * @param maxRetryAttempts Maximum total save attempts, including the initial attempt (default: 3)
 * @param retryInitialDelayMs Delay before the first retry; later retries use linear backoff
 * @param enabled Whether autosave is enabled (default: true)
 * @return An AutoSaveState object containing the current auto-save state
 */
@Composable
fun <T> rememberAutoSaveHandler(
    content: T,
    onSave: suspend (T) -> Boolean,
    hasContentChanged: (T, String?) -> Boolean,
    debounceMs: Long = 2000,
    backupIntervalMs: Long = 30000,
    indicatorDisplayMs: Long = 2000,
    maxRetryAttempts: Int = 3,
    retryInitialDelayMs: Long = 1000,
    enabled: Boolean = true,
): AutoSaveState {
    var autoSaveState by remember { mutableStateOf(AutoSaveState()) }
    val saveScope = rememberCoroutineScope()
    val currentContentHash = getContentHash(content)
    val latestContent = rememberUpdatedState(content)
    val latestOnSave = rememberUpdatedState(onSave)
    val latestHasContentChanged = rememberUpdatedState(hasContentChanged)
    val coordinator =
        remember(maxRetryAttempts, retryInitialDelayMs) {
            AutoSaveCoordinator<T>(
                maxSaveAttempts = maxRetryAttempts,
                retryInitialDelayMs = retryInitialDelayMs,
                contentHash = { value -> getContentHash(value) },
                hasContentChanged = { value, lastHash ->
                    latestHasContentChanged.value(value, lastHash)
                },
                onStateChange = { autoSaveState = it },
            )
        }

    // Debounced auto-save logic
    LaunchedEffect(currentContentHash, enabled, debounceMs) {
        if (!enabled) return@LaunchedEffect
        val contentAtStart = content
        delay(debounceMs)
        if (getContentHash(latestContent.value) == currentContentHash) {
            saveScope.launch {
                coordinator.save(
                    content = contentAtStart,
                    latestContent = { latestContent.value },
                    latestOnSave = { latestOnSave.value },
                )
            }
        }
    }

    // Regular interval backup save
    LaunchedEffect(enabled, backupIntervalMs) {
        if (!enabled) return@LaunchedEffect
        runPeriodicBackups(
            intervalMs = backupIntervalMs,
            latestContent = { latestContent.value },
            latestOnSave = {
                { latest ->
                    coordinator.save(
                        content = latest,
                        latestContent = { latestContent.value },
                        latestOnSave = { latestOnSave.value },
                    )
                }
            },
        )
    }

    LaunchedEffect(autoSaveState, indicatorDisplayMs) {
        if (autoSaveState.status == AutoSaveStatus.SAVED || autoSaveState.status == AutoSaveStatus.ERROR) {
            val completedState = autoSaveState
            delay(indicatorDisplayMs)
            coordinator.clearIndicator(completedState)
        }
    }

    return autoSaveState
}

/**
 * A simplified composable that handles auto-saving specifically for EditorState.
 * This version is compatible with the existing EntryEditorViewModel.autoSaveEntry method.
 *
 * @param editorState The editor state to monitor for changes
 * @param onAutoSave Callback to execute when content should be saved
 * @param debounceMs Time in milliseconds to wait after changes before saving
 * @param backupIntervalMs Interval for periodic backup saves
 * @param enabled Whether autosave is enabled
 * @return AutoSaveState object containing the current autosave state
 */
@Composable
fun rememberEditorAutoSave(
    editorState: EditorState,
    onAutoSave: suspend (EditorState) -> Boolean,
    debounceMs: Long = 2000,
    backupIntervalMs: Long = 30000,
    enabled: Boolean = true,
): AutoSaveState {
    // Create an autosave state handler with EditorState-specific behavior
    return rememberAutoSaveHandler(
        content = editorState,
        onSave = { onAutoSave(it) },
        hasContentChanged = { state, lastHash ->
            // Check if the content has meaningful changes that need to be saved
            val hasDurableDraft =
                state.hasContent() ||
                    state.draftState is DraftState.Active ||
                    state.hasJournalSelectionChanges
            val isDirty = state.isDirty
            val isNewOrChanged = lastHash == null || lastHash != getContentHash(state)

            Napier.i(
                "AutoSave check: hasDurableDraft=$hasDurableDraft, " +
                    "isModified=${state.isModified}, isNewOrChanged=$isNewOrChanged",
            )

            // An active draft must also persist a transition to empty content.
            hasDurableDraft && (isDirty || state.isModified) && isNewOrChanged
        },
        debounceMs = debounceMs,
        backupIntervalMs = backupIntervalMs,
        enabled = enabled,
    )
}

/**
 * Helper function to create a consistent hash from blocks for comparison
 */
internal fun getEditorDraftFingerprint(state: EditorState): String =
    buildString {
        appendFingerprintField("mark")
        appendFingerprintField(state.markId)
        appendFingerprintField(state.entryEditBase)
        appendFingerprintField("visit")
        appendFingerprintField(state.visitContext)
        appendFingerprintField("journals")
        state.selectedJournalIds.forEach { appendFingerprintField(it) }
        appendFingerprintField("blocks")
        state.blocks
            .filterNot { state.isReadOnly(it.id) }
            .forEach { block ->
                when (block) {
                    is HandwritingBlockUiState -> {
                        appendFingerprintField("handwriting")
                        appendCommonBlockFields(block)
                        appendFingerprintField(block.page.encode())
                    }

                    is TextBlockUiState -> {
                        appendFingerprintField("text")
                        appendCommonBlockFields(block)
                        appendFingerprintField(block.content)
                    }

                    is ImageBlockUiState -> {
                        appendFingerprintField("image")
                        appendCommonBlockFields(block)
                        appendFingerprintField(block.uri)
                        appendFingerprintField(block.caption)
                        appendFingerprintField(block.presentation)
                    }

                    is VideoBlockUiState -> {
                        appendFingerprintField("video")
                        appendCommonBlockFields(block)
                        appendFingerprintField(block.uri)
                        appendFingerprintField(block.caption)
                        appendFingerprintField(block.durationMs)
                    }

                    is AudioBlockUiState -> {
                        appendFingerprintField("audio")
                        appendCommonBlockFields(block)
                        appendFingerprintField(block.captureState)
                        appendFingerprintField(block.caption)
                        appendFingerprintField(block.transcription)
                    }

                    is CameraBlockUiState -> {
                        appendFingerprintField("camera")
                        appendCommonBlockFields(block)
                        appendFingerprintField(block.uri)
                        appendFingerprintField(block.pendingFilePath)
                        appendFingerprintField(block.caption)
                        appendFingerprintField(block.mediaType)
                        appendFingerprintField(block.durationMs)
                    }
                }
            }
    }

private fun StringBuilder.appendCommonBlockFields(block: EntryBlockUiState) {
    appendFingerprintField(block.id)
    appendFingerprintField(block.timestamp)
    appendFingerprintField(block.location)
}

private fun StringBuilder.appendFingerprintField(value: Any?) {
    val encoded = value?.toString().orEmpty()
    append(encoded.length)
    append(':')
    append(encoded)
}

private fun getContentHash(content: Any?): String =
    when (content) {
        is EditorState -> getEditorDraftFingerprint(content)
        null -> ""
        else -> content.toString()
    }
