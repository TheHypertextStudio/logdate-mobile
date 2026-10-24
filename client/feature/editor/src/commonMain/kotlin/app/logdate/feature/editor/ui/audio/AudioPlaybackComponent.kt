package app.logdate.feature.editor.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.media.audio.AudioDurationResolver
import app.logdate.feature.editor.ui.formatMediaDuration
import app.logdate.ui.audio.AudioContextProcessor
import app.logdate.ui.audio.AudioWaveformComponent
import app.logdate.ui.platform.PlatformIcons
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.capture_state_playback_failed
import logdate.client.feature.editor.generated.resources.capture_state_playback_start_failed
import logdate.client.feature.editor.generated.resources.generating_transcription
import logdate.client.feature.editor.generated.resources.pause
import logdate.client.feature.editor.generated.resources.play
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A component that displays and controls audio playback.
 * Includes a play/pause button, progress slider, and time display.
 *
 * This implementation connects to the AudioViewModel to use the platform-specific
 * audio playback implementation instead of simulating playback.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AudioPlaybackComponent(
    audioUri: String,
    modifier: Modifier = Modifier,
    initiallyPlaying: Boolean = false,
    waveformEnabled: Boolean = true,
    showTranscription: Boolean = false,
    transcriptionText: String? = null,
    viewModel: AudioViewModel = koinViewModel(key = "entry-audio-playback"),
    knownDurationMs: Long = 0,
    contentPadding: Dp = 16.dp,
) {
    val durationResolver: AudioDurationResolver = koinInject()
    ValidatedAudioContent(audioUri, knownDurationMs, durationResolver::resolveDurationMs, modifier.padding(contentPadding)) { duration ->
        ResolvedAudioPlaybackComponent(
            audioUri,
            Modifier,
            initiallyPlaying,
            waveformEnabled,
            showTranscription,
            transcriptionText,
            viewModel,
            duration,
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun ResolvedAudioPlaybackComponent(
    audioUri: String,
    modifier: Modifier = Modifier,
    initiallyPlaying: Boolean = false,
    waveformEnabled: Boolean = true,
    showTranscription: Boolean = false,
    transcriptionText: String? = null,
    viewModel: AudioViewModel,
    resolvedDurationMs: Long,
) {
    val audioContextProcessor: AudioContextProcessor = koinInject()
    // Get the current UI state from the view model
    val uiState by viewModel.uiState.collectAsState()

    val resolvedDuration = resolvedDurationMs.milliseconds
    if (uiState.currentUri == audioUri &&
        (uiState.error == Res.string.capture_state_playback_failed || uiState.error == Res.string.capture_state_playback_start_failed)
    ) {
        AudioUnavailableContent(resolvedDurationMs, onRetry = { viewModel.startPlayback(audioUri) }, playbackFailure = true)
        return
    }

    val waveformAmplitudes by produceState(
        initialValue = emptyList<Float>(),
        key1 = audioUri,
        key2 = resolvedDuration,
    ) {
        runCatching {
            audioContextProcessor
                .processProgressively(
                    audioUri = audioUri,
                    durationMs = resolvedDuration.inWholeMilliseconds,
                    createdAt = Clock.System.now(),
                    latitude = null,
                    longitude = null,
                ).collect { context ->
                    value = context.amplitudes
                }
        }
    }

    // Create playback state that manages playback
    val playbackState =
        remember(audioUri) {
            // Create the state with a non-circular reference to the seek operation
            AudioPlaybackState(
                audioUri = audioUri,
                initialIsPlaying = initiallyPlaying,
                initialTotalDuration = resolvedDuration,
                onStartPlayback = { viewModel.startPlayback(audioUri) },
                onStopPlayback = { viewModel.pausePlaybackFor(audioUri) },
                onSeek = { duration, totalDuration ->
                    val ratio =
                        if (totalDuration > Duration.ZERO) {
                            (
                                duration.inWholeMilliseconds.toFloat() /
                                    totalDuration.inWholeMilliseconds.toFloat()
                            ).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    viewModel.seekPlaybackFor(audioUri, ratio)
                },
            )
        }

    // Connect to platform-specific implementation
    LaunchedEffect(audioUri) {
        playbackState.updateLoadingState(true)

        playbackState.updateLoadingState(false)

        // Start playback if initially playing
        if (initiallyPlaying) {
            playbackState.startPlayback()
        }
    }

    LaunchedEffect(resolvedDuration) {
        if (resolvedDuration > Duration.ZERO) {
            playbackState.updateTotalDuration(resolvedDuration)
        }
    }

    // Observe the view model's playback progress
    LaunchedEffect(uiState) {
        // Update progress state from view model
        if (uiState.currentUri == audioUri) {
            playbackState.updatePlaybackState(uiState.isPlaying)
            if (uiState.playbackProgress > 0f && playbackState.totalDuration > Duration.ZERO) {
                val progressDuration = (uiState.playbackProgress * playbackState.totalDuration.inWholeMilliseconds).toLong()
                val newDuration = Duration.parse(progressDuration.toString() + "ms")
                playbackState.updateProgress(newDuration)
            }
        } else {
            playbackState.updatePlaybackState(false)
        }
    }

    // Handle errors from the view model
    LaunchedEffect(uiState.error) {
        if (uiState.error != null) {
            playbackState.setError(true)
        }
    }

    // Clean up on disposal
    DisposableEffect(audioUri) {
        onDispose {
            if (viewModel.uiState.value.currentUri == audioUri && viewModel.uiState.value.isPlaying) {
                viewModel.pausePlaybackFor(audioUri)
            }
        }
    }

    Column(
        modifier =
            modifier.fillMaxWidth(),
    ) {
        // Optional transcription display
        if (showTranscription) {
            when {
                uiState.transcriptionInProgress -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.generating_transcription),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                transcriptionText != null || uiState.transcription != null -> {
                    Text(
                        text = transcriptionText ?: uiState.transcription ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                    )
                }
            }
        }

        // Audio waveform display if enabled
        if (waveformEnabled) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(bottom = 8.dp),
            ) {
                AudioWaveformComponent(
                    audioLevels = waveformAmplitudes,
                    waveformColor = MaterialTheme.colorScheme.primary,
                    minHeight = 48.dp,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (playbackState.progressPercentage > 0f) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(playbackState.progressPercentage)
                                .height(48.dp)
                                .align(Alignment.CenterStart)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    )
                }

                Slider(
                    value = playbackState.progressPercentage,
                    onValueChange = { playbackState.seekToPercentage(it) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center),
                    colors =
                        SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        ),
                )
            }
        }

        // Playback controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Play/pause button
            Surface(
                onClick = { playbackState.togglePlayback() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier =
                    Modifier
                        .size(48.dp)
                        .semantics { role = Role.Button },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (playbackState.isLoading || (uiState.currentUri == audioUri && uiState.isPlaying != playbackState.isPlaying)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Icon(
                            painter =
                                if (playbackState.isPlaying) {
                                    PlatformIcons.pause()
                                } else {
                                    PlatformIcons.play()
                                },
                            contentDescription =
                                if (playbackState.isPlaying) {
                                    stringResource(Res.string.pause)
                                } else {
                                    stringResource(Res.string.play)
                                },
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Time display
            Text(
                text = formatMediaDuration(playbackState.progress.inWholeMilliseconds, true),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 48.dp),
                maxLines = 1,
                softWrap = false,
            )

            if (!waveformEnabled) {
                // Only show slider if waveform is disabled
                Slider(
                    value = playbackState.progressPercentage,
                    onValueChange = { playbackState.seekToPercentage(it) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Total duration
            Text(
                text = formatMediaDuration(playbackState.totalDuration.inWholeMilliseconds, true),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 48.dp),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
