@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.audio

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.text.asString
import app.logdate.feature.editor.ui.layout.LocalEditorCorners
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.media.MediaDeviceSelectorSheet
import app.logdate.ui.media.MediaDeviceSelectorTags
import app.logdate.ui.platform.PlatformSheet
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.change_microphone
import logdate.client.feature.editor.generated.resources.finish
import logdate.client.feature.editor.generated.resources.hide_transcript
import logdate.client.feature.editor.generated.resources.listening
import logdate.client.feature.editor.generated.resources.microphone
import logdate.client.feature.editor.generated.resources.recorder_bubble_pause
import logdate.client.feature.editor.generated.resources.recorder_bubble_paused
import logdate.client.feature.editor.generated.resources.recorder_bubble_recording
import logdate.client.feature.editor.generated.resources.recorder_bubble_resume
import logdate.client.feature.editor.generated.resources.restart_recording
import logdate.client.feature.editor.generated.resources.show_transcript
import logdate.client.feature.editor.generated.resources.transcript_status_listening
import logdate.client.feature.editor.generated.resources.transcription_improving
import logdate.client.feature.editor.generated.resources.transcription_ready
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/**
 * A component that displays the active recording interface with controls.
 *
 * Layout (top to bottom):
 * The transcript is the primary surface. When transcription fails, it collapses and the
 * waveform takes its space without changing the recording or discarding captured text.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ActiveRecordingDisplay(
    audioLevels: List<Float>,
    recordingDuration: Duration,
    onRestart: () -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    inputSelection: MediaDeviceSelectionUiState? = null,
    onInputSelected: (String) -> Unit = {},
    transcriptionText: String? = null,
    transcriptionIsFinal: Boolean = false,
    transcriptionIsRefining: Boolean = false,
    /**
     * True when the live transcription attempt has failed (e.g. the on-device model
     * couldn't be loaded). Without this, a transcription failure was indistinguishable
     * from "still listening" -- the transcript pane just stayed on the same placeholder
     * forever, silently, with no indication anything had gone wrong. The recording
     * itself is unaffected; only the text conversion failed.
     */
    transcriptionHasError: Boolean = false,
    isPaused: Boolean = false,
) {
    var failedTranscriptExpanded by remember(transcriptionHasError) { mutableStateOf(false) }
    val transcriptCollapsed = transcriptionHasError && !failedTranscriptExpanded
    FoldableTabletopLayout(
        modifier = modifier,
        minPaneHeight = 220.dp,
        topPane = {
            ActiveRecordingTranscriptPane(
                transcriptionText = transcriptionText,
                transcriptionIsFinal = transcriptionIsFinal,
                transcriptionIsRefining = transcriptionIsRefining,
                transcriptionHasError = transcriptionHasError,
                isPaused = isPaused,
                collapsed = transcriptCollapsed,
                onToggleTranscript = { failedTranscriptExpanded = !failedTranscriptExpanded },
                modifier =
                    Modifier
                        .then(if (transcriptCollapsed) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                        .padding(bottom = 8.dp),
            )
        },
        bottomPane = {
            ActiveRecordingTransportPane(
                audioLevels = audioLevels,
                recordingDuration = recordingDuration,
                onRestart = onRestart,
                onPause = onPause,
                onFinish = onFinish,
                isPaused = isPaused,
                inputSelection = inputSelection,
                onInputSelected = onInputSelected,
                waveformFocused = transcriptCollapsed,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .then(if (transcriptCollapsed) Modifier.fillMaxSize() else Modifier.fillMaxWidth()),
            )
        },
        standardContent = {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                ActiveRecordingTranscriptPane(
                    transcriptionText = transcriptionText,
                    transcriptionIsFinal = transcriptionIsFinal,
                    transcriptionIsRefining = transcriptionIsRefining,
                    transcriptionHasError = transcriptionHasError,
                    isPaused = isPaused,
                    collapsed = transcriptCollapsed,
                    onToggleTranscript = { failedTranscriptExpanded = !failedTranscriptExpanded },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .then(if (transcriptCollapsed) Modifier else Modifier.weight(1f))
                            .padding(bottom = 8.dp),
                )
                ActiveRecordingTransportPane(
                    audioLevels = audioLevels,
                    recordingDuration = recordingDuration,
                    onRestart = onRestart,
                    onPause = onPause,
                    onFinish = onFinish,
                    isPaused = isPaused,
                    inputSelection = inputSelection,
                    onInputSelected = onInputSelected,
                    waveformFocused = transcriptCollapsed,
                    modifier = Modifier.fillMaxWidth().then(if (transcriptCollapsed) Modifier.weight(1f) else Modifier),
                )
            }
        },
    )
}

@Composable
private fun ActiveRecordingTranscriptPane(
    transcriptionText: String?,
    transcriptionIsFinal: Boolean,
    transcriptionIsRefining: Boolean,
    transcriptionHasError: Boolean,
    isPaused: Boolean,
    collapsed: Boolean,
    onToggleTranscript: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val transcriptToggleDescription = stringResource(if (collapsed) Res.string.show_transcript else Res.string.hide_transcript)
    LaunchedEffect(transcriptionText, collapsed) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Surface(
        modifier = modifier.testTag("recording_transcript_surface"),
        shape = RoundedCornerShape(LocalEditorCorners.current.cardRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (collapsed) 8.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (transcriptionHasError) 8.dp else 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    RecordingTranscriptStatus(
                        isPaused = isPaused,
                        isFinal = transcriptionIsFinal && !transcriptionHasError,
                        isRefining = transcriptionIsRefining && !transcriptionHasError,
                        hasTranscript = !transcriptionText.isNullOrBlank() || transcriptionHasError,
                    )
                }
                if (transcriptionHasError && !transcriptionText.isNullOrBlank()) {
                    Surface(
                        onClick = onToggleTranscript,
                        modifier =
                            Modifier.size(48.dp).semantics {
                                role = Role.Button
                                contentDescription = transcriptToggleDescription
                            },
                        shape = CircleShape,
                        color = Color.Transparent,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.rotate(if (collapsed) 0f else 180f),
                            )
                        }
                    }
                }
            }
            if (!collapsed) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState),
                ) {
                    val text = transcriptionText.takeUnless { it.isNullOrBlank() }
                    if (text != null) {
                        LiveTranscriptText(text = text, isRefining = transcriptionIsRefining)
                    } else if (!transcriptionHasError) {
                        Text(
                            text = stringResource(Res.string.listening),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveRecordingTransportPane(
    audioLevels: List<Float>,
    recordingDuration: Duration,
    onRestart: () -> Unit,
    onPause: () -> Unit,
    onFinish: () -> Unit,
    isPaused: Boolean,
    inputSelection: MediaDeviceSelectionUiState?,
    onInputSelected: (String) -> Unit,
    waveformFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        shape = RoundedCornerShape(topStart = LocalEditorCorners.current.cardRadius, topEnd = LocalEditorCorners.current.cardRadius),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecordingDockHeader(recordingDuration, isPaused, inputSelection, onInputSelected, onRestart, !waveformFocused)
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .then(if (waveformFocused) Modifier.weight(1f) else Modifier.height(56.dp))
                        .testTag("recording_waveform"),
                shape = RoundedCornerShape(LocalEditorCorners.current.cardRadius),
                color = Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RecordingWaveform(
                            levels = audioLevels,
                            isRecording = !isPaused,
                            focused = waveformFocused,
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).height(if (waveformFocused) 160.dp else 56.dp),
                        )
                        if (waveformFocused) {
                            Text(
                                text = formatDuration(recordingDuration),
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecordingActionSurface(
                    label = stringResource(if (isPaused) Res.string.recorder_bubble_resume else Res.string.recorder_bubble_pause),
                    icon = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    onClick = onPause,
                    primary = false,
                    modifier = Modifier.width(56.dp),
                )
                RecordingActionSurface(
                    label = stringResource(Res.string.finish),
                    icon = Icons.Default.Check,
                    onClick = onFinish,
                    primary = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RecordingDockHeader(
    recordingDuration: Duration,
    isPaused: Boolean,
    inputSelection: MediaDeviceSelectionUiState?,
    onInputSelected: (String) -> Unit,
    onRestart: () -> Unit,
    showDuration: Boolean,
) {
    val selectableInputs =
        remember(inputSelection?.devices) {
            inputSelection
                ?.devices
                .orEmpty()
                .filter {
                    it.isAvailable && it.kind == MediaDeviceKind.AUDIO_INPUT && it.category != MediaDeviceCategory.SYSTEM_DEFAULT
                }.distinctBy { it.groupKey }
        }
    val canSwitch = inputSelection != null && inputSelection.isSelectionControllable && selectableInputs.size > 1
    if (!showDuration && !canSwitch && !isPaused) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (inputSelection != null && canSwitch) {
            Box(Modifier.weight(1f)) {
                RecordingInputControl(inputSelection, onInputSelected)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (showDuration) {
            Text(
                text = formatDuration(recordingDuration),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Normal,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isPaused) {
            val restartDescription = stringResource(Res.string.restart_recording)
            Surface(
                onClick = onRestart,
                modifier =
                    Modifier.size(48.dp).semantics {
                        role = Role.Button
                        contentDescription = restartDescription
                    },
                shape = RoundedCornerShape(8.dp),
                color = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingInputControl(
    selection: MediaDeviceSelectionUiState,
    onInputSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val inputLabel = selection.selectedDevice?.label?.asString() ?: stringResource(Res.string.microphone)
    Surface(
        onClick = { pickerOpen = true },
        modifier = modifier.heightIn(min = 48.dp).testTag(MediaDeviceSelectorTags.chip("Microphone")),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                inputLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = stringResource(Res.string.change_microphone),
                modifier = Modifier.size(18.dp),
            )
        }
    }
    if (pickerOpen) {
        PlatformSheet(onDismissRequest = { pickerOpen = false }) {
            MediaDeviceSelectorSheet(
                selection = selection,
                title = stringResource(Res.string.microphone),
                onDeviceSelected = {
                    onInputSelected(it)
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
        }
    }
}

@Composable
private fun RecordingActionSurface(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val restingCorner = LocalEditorCorners.current.controlRadius
    val corner by animateDpAsState(
        targetValue = if (pressed) restingCorner / 2 else restingCorner,
        animationSpec = spring(dampingRatio = .85f, stiffness = 500f),
        label = "Recording action shape",
    )
    Surface(
        onClick = onClick,
        modifier =
            modifier.height(56.dp).semantics {
                role = Role.Button
                if (!primary) contentDescription = label
            },
        shape = RoundedCornerShape(corner),
        interactionSource = interactionSource,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (primary) 24.dp else 28.dp))
            if (primary) {
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun RecordingTranscriptStatus(
    isPaused: Boolean,
    isFinal: Boolean,
    isRefining: Boolean,
    hasTranscript: Boolean,
) {
    val label =
        when {
            isPaused -> stringResource(Res.string.recorder_bubble_paused)
            isRefining -> stringResource(Res.string.transcription_improving)
            isFinal -> stringResource(Res.string.transcription_ready)
            hasTranscript -> stringResource(Res.string.recorder_bubble_recording)
            else -> stringResource(Res.string.transcript_status_listening)
        }
    val indicatorColor =
        when {
            isPaused -> MaterialTheme.colorScheme.outline
            isFinal -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier =
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(indicatorColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LiveTranscriptText(
    text: String,
    isRefining: Boolean,
) {
    val paragraphs = remember(text) { text.split(PARAGRAPH_BREAK).map { it.trim() }.filter { it.isNotBlank() } }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        paragraphs.forEachIndexed { index, paragraph ->
            val isLatest = index == paragraphs.lastIndex
            Text(
                text = paragraph,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
                color =
                    if (isLatest && isRefining) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else if (isLatest && paragraphs.size > 1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}

/** Formats [duration] as MM:SS, the elapsed-time format every recording surface uses. */
internal fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeSeconds
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60

    val minutesStr = minutes.toString().padStart(2, '0')
    val secondsStr = if (seconds < 10) "0$seconds" else "$seconds"

    return "$minutesStr:$secondsStr"
}

private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
