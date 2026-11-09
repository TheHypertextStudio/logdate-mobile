@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.audio.AudioWaveformComponent
import app.logdate.ui.audio.RecordingStopButton
import app.logdate.ui.media.MediaDeviceSelector
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.platform.rememberLogDateHaptics
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.checking_recording
import logdate.client.feature.editor.generated.resources.microphone
import logdate.client.feature.editor.generated.resources.ready_to_record
import logdate.client.feature.editor.generated.resources.record
import logdate.client.feature.editor.generated.resources.recording_in_progress
import logdate.client.feature.editor.generated.resources.recording_recovering
import logdate.client.feature.editor.generated.resources.recording_starting
import logdate.client.feature.editor.generated.resources.start_recording
import logdate.client.feature.editor.generated.resources.stop
import logdate.client.feature.editor.generated.resources.stop_recording
import logdate.client.feature.editor.generated.resources.tap_record_to_begin
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

/**
 * Audio recording controls that display current recording state, buttons,
 * and visualizations.
 *
 * @param recordingState Current state of recording
 * @param audioLevels Audio level data for visualization
 * @param recordingDuration Current duration of the recording
 * @param onStartRecording Callback when recording should start
 * @param onStopRecording Callback when recording should stop
 * @param modifier Modifier for the root component
 * @param isStarting True while a start is waiting on the platform; the record button is
 *   disabled so repeated taps cannot queue a second session
 * @param errorMessage Message shown under the controls when the last start or stop failed
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AudioRecordingControls(
    recordingState: RecordingState,
    audioLevels: List<Float>,
    recordingDuration: Duration,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
    inputSelection: MediaDeviceSelectionUiState? = null,
    onInputSelected: (String) -> Unit = {},
    isStarting: Boolean = false,
    errorMessage: String? = null,
) {
    val haptics = rememberLogDateHaptics()
    val handleStart = {
        haptics.recordingStarted()
        onStartRecording()
    }
    val handleStop = {
        haptics.recordingFinished()
        onStopRecording()
    }
    val canControlRecording = !isStarting && recordingState != RecordingState.PROCESSING
    val canSelectInput = recordingState != RecordingState.RECORDING && recordingState != RecordingState.PROCESSING

    FoldableTabletopLayout(
        modifier = modifier,
        minPaneHeight = 160.dp,
        topPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RecordingStateRow(
                    recordingState = recordingState,
                    recordingDuration = recordingDuration,
                    isStarting = isStarting,
                )
                RecordingWaveformBox(
                    recordingState = recordingState,
                    audioLevels = audioLevels,
                    onStartRecording = handleStart,
                    enabled = canControlRecording,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        bottomPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                RecordingInputSelector(
                    inputSelection = inputSelection,
                    onInputSelected = onInputSelected,
                    enabled = canSelectInput,
                )
                RecordingActionButton(
                    recordingState = recordingState,
                    onStartRecording = handleStart,
                    onStopRecording = handleStop,
                    enabled = canControlRecording,
                    modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(0.8f),
                )
                RecordingErrorText(errorMessage)
            }
        },
        standardContent = {
            AudioRecordingControlsColumn(
                recordingState = recordingState,
                audioLevels = audioLevels,
                recordingDuration = recordingDuration,
                inputSelection = inputSelection,
                onInputSelected = onInputSelected,
                isStarting = isStarting,
                errorMessage = errorMessage,
                onStartRecording = handleStart,
                onStopRecording = handleStop,
                enabled = canControlRecording,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
            )
        },
    )
}

@Composable
private fun AudioRecordingControlsColumn(
    recordingState: RecordingState,
    audioLevels: List<Float>,
    recordingDuration: Duration,
    inputSelection: MediaDeviceSelectionUiState?,
    onInputSelected: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    isStarting: Boolean,
    errorMessage: String?,
    enabled: Boolean = !isStarting && recordingState != RecordingState.PROCESSING,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecordingStateRow(
            recordingState = recordingState,
            recordingDuration = recordingDuration,
            isStarting = isStarting,
        )

        RecordingInputSelector(
            inputSelection = inputSelection,
            onInputSelected = onInputSelected,
            enabled = recordingState != RecordingState.RECORDING && recordingState != RecordingState.PROCESSING,
        )

        RecordingWaveformBox(
            recordingState = recordingState,
            audioLevels = audioLevels,
            onStartRecording = onStartRecording,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )

        RecordingActionButton(
            recordingState = recordingState,
            onStartRecording = onStartRecording,
            onStopRecording = onStopRecording,
            enabled = enabled,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth(0.8f),
        )
        RecordingErrorText(errorMessage)
    }
}

@Composable
private fun RecordingErrorText(
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    if (errorMessage.isNullOrBlank()) return
    Text(
        text = errorMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.testTag("audio_record_error"),
    )
}

@Composable
private fun RecordingStateRow(
    recordingState: RecordingState,
    recordingDuration: Duration,
    modifier: Modifier = Modifier,
    isStarting: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text =
                when {
                    recordingState == RecordingState.RECORDING -> stringResource(Res.string.recording_in_progress)

                    recordingState == RecordingState.PROCESSING -> stringResource(Res.string.recording_recovering)

                    // The tap was accepted and the button is disabled, but without this the
                    // wait for the platform to confirm the recorder is running looked
                    // identical to nothing having happened at all.
                    isStarting -> stringResource(Res.string.recording_starting)

                    else -> stringResource(Res.string.ready_to_record)
                },
            style = MaterialTheme.typography.titleMedium,
            color =
                if (recordingState == RecordingState.RECORDING) {
                    MaterialTheme.colorScheme.error
                } else if (recordingState == RecordingState.PROCESSING) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )

        if (recordingState == RecordingState.RECORDING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                )
                Text(
                    text = recordingDuration.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RecordingInputSelector(
    inputSelection: MediaDeviceSelectionUiState?,
    onInputSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    inputSelection?.let { selection ->
        MediaDeviceSelector(
            selection = selection,
            onDeviceSelected = onInputSelected,
            label = stringResource(Res.string.microphone),
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RecordingWaveformBox(
    recordingState: RecordingState,
    audioLevels: List<Float>,
    onStartRecording: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val canStart = recordingState == RecordingState.INACTIVE && enabled
    val boxBackground =
        if (recordingState == RecordingState.RECORDING) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }
    val tapModifier =
        if (canStart) {
            Modifier.clickable(
                onClick = onStartRecording,
                onClickLabel = stringResource(Res.string.start_recording),
                role = Role.Button,
            )
        } else {
            Modifier
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(boxBackground)
                .then(tapModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (audioLevels.isEmpty() && recordingState != RecordingState.RECORDING) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = PlatformIcons.mic(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Text(
                    text =
                        if (recordingState == RecordingState.PROCESSING) {
                            stringResource(Res.string.checking_recording)
                        } else {
                            stringResource(Res.string.tap_record_to_begin)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        } else {
            AudioWaveformComponent(
                audioLevels = audioLevels,
                isRecording = recordingState == RecordingState.RECORDING,
                waveformColor =
                    if (recordingState == RecordingState.RECORDING) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                strokeWidth = 3.dp,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun RecordingActionButton(
    recordingState: RecordingState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (recordingState == RecordingState.PROCESSING) {
        Button(onClick = {}, enabled = false, modifier = modifier) {
            Text(stringResource(Res.string.recording_recovering))
        }
    } else if (recordingState == RecordingState.RECORDING) {
        RecordingStopButton(
            label = stringResource(Res.string.stop),
            onStop = onStopRecording,
            contentDescription = stringResource(Res.string.stop_recording),
            enabled = enabled,
            modifier = modifier.testTag("audio_record_stop_button"),
        )
    } else {
        Button(
            onClick = onStartRecording,
            enabled = enabled,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            modifier = modifier.testTag("audio_record_start_button"),
        ) {
            Icon(
                painter = PlatformIcons.mic(),
                contentDescription = stringResource(Res.string.start_recording),
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = stringResource(Res.string.record),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
}
