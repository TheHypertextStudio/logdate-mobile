package app.logdate.feature.editor.ui.audio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.feature.editor.audio.AudioLabelResolver
import app.logdate.feature.editor.audio.formatAudioLabel
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.LocalAudioCaptureStartGate
import app.logdate.feature.editor.ui.editor.LocalEditorInputTokens
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.feature.editor.ui.editor.audioInputRuntimeKey
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioResolver
import app.logdate.feature.editor.ui.editor.startReservedAudioCapture
import app.logdate.feature.editor.ui.layout.EditorBlockCornerRadius
import app.logdate.feature.editor.ui.layout.LocalEditorCorners
import app.logdate.shared.model.Location
import app.logdate.ui.audio.AudioContextProcessor
import app.logdate.util.formatDateLocalized
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A component that handles audio recording and playback within the editor.
 *
 * This component connects the UI with the ViewModel for audio operations,
 * while keeping proper separation of concerns. It manages state at this level
 * and passes down only the necessary state and callbacks to child components.
 *
 * @param block The audio block state
 * @param onBlockUpdated Callback when the block is updated
 * @param onDeleteRequested Callback when the block should be deleted
 * @param onResolverReady Called once this composable's own, block-scoped [AudioViewModel] is
 *   available, together with [block]'s id, so the editor's save path can bind a finalizer for
 *   this specific block (see [EntryEditorViewModel.bindAudioBlockFinalizer][app.logdate.feature.editor.ui.editor.EntryEditorViewModel]).
 * @param modifier Modifier for layout customization
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AudioBlockEditor(
    block: AudioBlockUiState,
    onBlockUpdated: (AudioBlockUiState) -> Unit,
    onDeleteRequested: () -> Unit,
    onResolverReady: (Uuid, PendingAudioResolver) -> Unit = { _, _ -> },
    onPersistRecordingTarget: suspend (Uuid, String, String?) -> Boolean = { _, _, _ -> true },
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    selected: Boolean = false,
    availableHeight: Dp = Dp.Infinity,
) {
    val labelResolver = remember { AudioLabelResolver() }
    val labelResult =
        remember(block.caption, block.timestamp, block.location) {
            labelResolver.resolve(
                createdAt = block.timestamp,
                caption = block.caption,
                latitude = block.location?.latitude,
                longitude = block.location?.longitude,
            )
        }
    val resolvedTitle = formatAudioLabel(labelResult)

    // Get the ViewModel at this level, not in child composables. Keyed by this block's own id so
    // multiple audio blocks composed on the same editor screen each get their own AudioViewModel
    // instance instead of sharing one -- a shared instance can only track one in-flight recording
    // (lastTargetNoteId) at a time, so a second block's recording would silently steal ownership
    // from the first.
    val inputToken = LocalEditorInputTokens.current[block.id]
    val audioViewModel: AudioViewModel = koinViewModel(key = audioInputRuntimeKey(block.id, inputToken))
    val captureStartGate = LocalAudioCaptureStartGate.current
    LaunchedEffect(audioViewModel, block.id) {
        audioViewModel.attachToRecording(block.id, (block.captureState as? AudioCaptureState.Recording)?.fallbackFilePath)
        onResolverReady(block.id, audioViewModel)
    }
    val audioRouteRepository: AudioRouteRepository = koinInject()
    // Collect audio state from ViewModel
    val audioUiState by audioViewModel.uiState.collectAsState()
    val inputSelection by audioRouteRepository.inputDevices.collectAsState()
    val outputSelection by audioRouteRepository.outputDevices.collectAsState()

    // Determine if we're in recording mode or playback mode
    val existingAudioUri = block.uri
    val isRecording = audioUiState.isRecording

    // Determine current recording state
    val recordingState =
        when {
            isRecording -> RecordingState.RECORDING
            block.captureState is AudioCaptureState.Stopping -> RecordingState.PROCESSING
            else -> RecordingState.INACTIVE
        }

    val audioLevels = audioUiState.audioLevels

    val playbackMetadata =
        remember(block, resolvedTitle) {
            val subtitle = formatDateLocalized(block.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date)
            AudioPlaybackMetadata(
                title = resolvedTitle,
                subtitle = subtitle,
                noteId = block.id,
            )
        }

    // Function to handle saving a recorded audio file
    val handleSaveRecording = { uri: String ->
        onBlockUpdated(
            block.copy(
                captureState =
                    AudioCaptureState.Ready(
                        uri = uri,
                        durationMs = audioUiState.duration.inWholeMilliseconds,
                    ),
                transcription = audioUiState.transcription ?: block.transcription,
            ),
        )
    }

    LaunchedEffect(audioUiState.recordedAudioUri, block.uri) {
        val recordedUri = audioUiState.recordedAudioUri
        if (recordedUri != null && block.uri == null) {
            handleSaveRecording(recordedUri)
            audioViewModel.clearRecordedAudio()
        }
    }

    LaunchedEffect(audioUiState.pendingRecoveryFilePath, audioUiState.recordingFallbackFilePath, block.captureState) {
        val pendingPath = audioUiState.pendingRecoveryFilePath ?: return@LaunchedEffect
        val pendingState =
            AudioCaptureState.Stopping(
                filePath = pendingPath,
                fallbackFilePath = audioUiState.recordingFallbackFilePath,
            )
        if (block.captureState != pendingState) {
            onBlockUpdated(block.copy(captureState = pendingState))
        }
        when (val recovered = audioViewModel.resolvePending(block.id)) {
            is AudioCaptureState.Ready -> onBlockUpdated(block.copy(captureState = recovered))
            is AudioCaptureState.Recording -> onBlockUpdated(block.copy(captureState = recovered))
            else -> Unit
        }
    }

    LaunchedEffect(
        audioUiState.isRecording,
        audioUiState.isStartingRecording,
        audioUiState.recordingTargetNoteId,
        audioUiState.recordingFilePath,
        audioUiState.recordingFallbackFilePath,
        block.captureState,
    ) {
        val ownsActiveRecording =
            (audioUiState.isRecording || audioUiState.isStartingRecording) && audioUiState.recordingTargetNoteId == block.id
        if (!ownsActiveRecording) return@LaunchedEffect
        val livePath = audioUiState.recordingFilePath
        val current = block.captureState
        if (livePath == null && current is AudioCaptureState.Recording && current.filePath != null) {
            return@LaunchedEffect
        }
        val needsUpdate =
            current !is AudioCaptureState.Recording ||
                current.filePath != livePath ||
                current.fallbackFilePath != audioUiState.recordingFallbackFilePath
        if (needsUpdate) {
            onBlockUpdated(
                block.copy(
                    captureState =
                        AudioCaptureState.Recording(
                            filePath = livePath,
                            fallbackFilePath = audioUiState.recordingFallbackFilePath,
                        ),
                ),
            )
        }
    }

    // A recording that ended without a file leaves nothing to keep. Drop the block back to
    // empty so the user can record again or save the entry without it, instead of a
    // permanently "recording" block that blocks every save.
    LaunchedEffect(audioUiState.failedRecordingTargetNoteId, block.captureState) {
        val failedFor = audioUiState.failedRecordingTargetNoteId
        if (failedFor == block.id && block.captureState is AudioCaptureState.Recording) {
            onBlockUpdated(block.copy(captureState = AudioCaptureState.Empty))
        }
    }

    LaunchedEffect(audioUiState.transcription, block.uri, block.transcription) {
        val latestTranscript = audioUiState.transcription?.trim().orEmpty()
        if (block.uri != null && latestTranscript.isNotBlank() && latestTranscript != block.transcription) {
            onBlockUpdated(block.copy(transcription = latestTranscript))
        }
    }

    // Reset stale playback state when the active block changes, but not on initial composition
    // (LaunchedEffect always fires on first composition regardless of key).
    val previousBlockId = remember { mutableStateOf<Uuid?>(null) }
    LaunchedEffect(block.id) {
        if (previousBlockId.value != null && previousBlockId.value != block.id) {
            audioViewModel.resetPlaybackState()
        }
        previousBlockId.value = block.id
    }

    val scope = rememberCoroutineScope()
    var recovering by remember(block.id) { mutableStateOf(false) }
    val requestRecordingStart = {
        startReservedAudioCapture(captureStartGate, block.id) {
            if (block.startOnOpen) onBlockUpdated(block.copy(startOnOpen = false))
            audioViewModel.startRecording(targetNoteId = block.id) { path, fallbackPath ->
                onPersistRecordingTarget(block.id, path, fallbackPath)
            }
        }
    }
    Box(modifier = modifier) {
        val failure = block.captureState as? AudioCaptureState.Failed
        if (failure != null) {
            RecordingRecoveryContent(
                failure,
                recovering = recovering,
                onRetry = {
                    if (!recovering) {
                        scope.launch {
                            recovering = true
                            try {
                                val recovered =
                                    audioViewModel.pendingAudioRecoverer.recover(
                                        AudioCaptureState.Stopping(failure.filePath, failure.fallbackFilePath),
                                    )
                                onBlockUpdated(block.copy(captureState = recovered))
                            } finally {
                                recovering = false
                            }
                        }
                    }
                },
            )
        } else if (existingAudioUri != null) {
            val waveformAmplitudes =
                rememberWaveformAmplitudes(
                    audioUri = existingAudioUri,
                    durationMs = block.duration,
                    createdAt = block.timestamp,
                    location = block.location,
                )
            AudioBlockContent(
                block = block,
                isExpanded = !inline || selected,
                showDeleteAction = !inline,
                isPlaying = audioUiState.isPlaying,
                timedTranscript = audioUiState.timedTranscript,
                playbackProgress = audioUiState.playbackProgress,
                onPlayPauseClicked = {
                    audioViewModel.togglePlayback(existingAudioUri, playbackMetadata)
                },
                onSeekPositionChanged = { position ->
                    audioViewModel.seekTo(position)
                },
                onSeekTimestampClicked = { positionMs ->
                    audioViewModel.seekToPositionMs(positionMs, block.duration)
                },
                onDeleteClicked = onDeleteRequested,
                outputSelection = outputSelection,
                onOutputDeviceSelected = audioRouteRepository::selectOutputDevice,
                waveformAmplitudes = waveformAmplitudes,
                availableHeight = availableHeight,
                cornerRadius = if (inline) EditorBlockCornerRadius else LocalEditorCorners.current.cardRadius,
                trailingActionInset = if (inline) 40.dp else 0.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            AudioPermissionWrapper {
                if (isRecording) {
                    ActiveRecordingDisplay(
                        audioLevels = audioLevels,
                        recordingDuration = audioUiState.duration,
                        transcriptionText = audioUiState.transcription,
                        transcriptionIsFinal = audioUiState.isTranscriptionFinal,
                        transcriptionIsRefining = audioUiState.isTranscriptionRefining,
                        transcriptionHasError = audioUiState.hasTranscriptionError,
                        isPaused = audioUiState.isPaused,
                        onRestart = {
                            audioViewModel.restartRecording { path, fallbackPath ->
                                onPersistRecordingTarget(block.id, path, fallbackPath)
                            }
                        },
                        onPause = { audioViewModel.toggleRecordingPause() },
                        onFinish = { audioViewModel.stopRecording() },
                        modifier = Modifier.fillMaxSize(),
                        inputSelection = inputSelection,
                        onInputSelected = audioRouteRepository::selectInputDevice,
                    )
                } else {
                    VoiceLaunchTriggerEffect(
                        enabled = block.startOnOpen,
                        blockId = block.id,
                        onStartRecording = { requestRecordingStart() },
                    )
                    AudioRecordingControls(
                        recordingState = recordingState,
                        audioLevels = audioLevels,
                        recordingDuration = audioUiState.duration,
                        onStartRecording = {
                            requestRecordingStart()
                        },
                        onStopRecording = { audioViewModel.stopRecording() },
                        modifier = Modifier.fillMaxSize(),
                        inputSelection = inputSelection,
                        onInputSelected = audioRouteRepository::selectInputDevice,
                        isStarting = audioUiState.isStartingRecording,
                        errorMessage = audioUiState.error?.let { stringResource(it) },
                    )
                }
            }
        }
    }
}

/**
 * Loads waveform amplitudes progressively without blocking the main composition thread.
 */
@Composable
private fun rememberWaveformAmplitudes(
    audioUri: String,
    durationMs: Long,
    createdAt: Instant,
    location: Location?,
    processor: AudioContextProcessor = koinInject(),
): List<Float> {
    val amplitudes by produceState(
        initialValue = emptyList(),
        audioUri,
        durationMs,
        createdAt,
        location,
    ) {
        try {
            processor
                .processProgressively(
                    audioUri = audioUri,
                    durationMs = durationMs,
                    createdAt = createdAt,
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                ).collect { value = it.amplitudes }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w(error) { "Unable to load the recording waveform" }
        }
    }
    return amplitudes
}

/**
 * Triggers audio recording for voice launches once the microphone permission is confirmed
 * and the hosting window gains focus (e.g. after the Quick Settings shade finishes closing).
 */
@Composable
@Suppress("ktlint:standard:function-naming")
private fun VoiceLaunchTriggerEffect(
    enabled: Boolean,
    blockId: Uuid,
    onStartRecording: () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(blockId, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        onStartRecording()
    }
}
