@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.logdate.client.media.audio.AudioPlaybackManager
import app.logdate.client.media.audio.AudioPlaybackMetadata
import app.logdate.client.media.audio.AudioPlaybackStatus
import app.logdate.client.media.audio.AudioPlaybackStatusProvider
import app.logdate.client.media.device.AudioRouteRepository
import app.logdate.client.media.device.MediaDeviceKind
import app.logdate.client.media.device.MediaDeviceSelectionUiState
import app.logdate.client.media.device.systemControlledSelection
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Display metadata for the currently playing audio, used by the mini-player.
 */
data class AudioPlaybackDisplayInfo(
    val title: String? = null,
    val subtitle: String? = null,
    val accentColor: Long? = null,
)

/**
 * State for audio playback that is shared across the app.
 * Only one audio file can be playing at a time.
 */
data class AudioPlaybackState(
    val currentlyPlayingId: Uuid? = null,
    val currentUri: String? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val duration: Duration = Duration.ZERO,
    val displayInfo: AudioPlaybackDisplayInfo = AudioPlaybackDisplayInfo(),
    val outputSelection: MediaDeviceSelectionUiState =
        systemControlledSelection(MediaDeviceKind.AUDIO_OUTPUT),
    val play: (id: Uuid, uri: String, displayInfo: AudioPlaybackDisplayInfo?) -> Unit = { _, _, _ -> },
    val pause: () -> Unit = {},
    val stop: () -> Unit = {},
    val seekTo: (position: Float) -> Unit = {},
    val selectOutputDevice: (String) -> Unit = {},
)

/**
 * CompositionLocal for accessing the audio playback state from anywhere in the app.
 */
val LocalAudioPlaybackState =
    compositionLocalOf {
        AudioPlaybackState()
    }

/**
 * Provider composable that manages audio playback state and makes it available to all descendants.
 * This ensures only one audio can be played at a time throughout the app.
 */
@Composable
fun AudioPlaybackProvider(
    handoff: PlaybackHandoffState = PlaybackHandoffState(),
    content: @Composable () -> Unit,
) {
    val audioPlaybackManager: AudioPlaybackManager = koinInject()
    val audioContextProcessor: AudioContextProcessor = koinInject()
    val audioRouteRepository: AudioRouteRepository = koinInject()
    val statusProvider = audioPlaybackManager as? AudioPlaybackStatusProvider

    // State that will be managed at this level
    var currentlyPlayingId by remember { mutableStateOf<Uuid?>(null) }
    var currentAudioUri by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableStateOf(Duration.ZERO) }
    var displayInfo by remember { mutableStateOf(AudioPlaybackDisplayInfo()) }
    val playbackStatus =
        statusProvider?.playbackStatus?.collectAsState(
            initial = AudioPlaybackStatus(),
        )
    val outputSelection by audioRouteRepository.outputDevices.collectAsState()

    LaunchedEffect(playbackStatus?.value) {
        val statusValue = playbackStatus?.value ?: return@LaunchedEffect
        isPlaying = statusValue.isPlaying
        progress = statusValue.progress
        duration = statusValue.duration
        val metadata = statusValue.metadata
        val statusNoteId = metadata?.noteId
        val statusUri = statusValue.currentUri
        when {
            statusNoteId != null && statusUri != null -> {
                currentlyPlayingId = statusNoteId
                currentAudioUri = statusUri
                displayInfo =
                    AudioPlaybackDisplayInfo(
                        title = metadata.title,
                        subtitle = metadata.subtitle,
                        accentColor = metadata.accentColor,
                    )
            }

            !statusValue.isPlaying && statusValue.progress == 0f && statusUri == null -> {
                currentlyPlayingId = null
                currentAudioUri = null
                displayInfo = AudioPlaybackDisplayInfo()
            }
        }
    }

    // Functions to control playback
    val play: (id: Uuid, uri: String, info: AudioPlaybackDisplayInfo?) -> Unit = { id, uri, info ->
        // Stop current playback if different ID
        if (currentlyPlayingId != id) {
            audioPlaybackManager.stopPlayback()
            progress = 0f
        }
        currentlyPlayingId = id
        currentAudioUri = uri
        isPlaying = true
        displayInfo = info ?: AudioPlaybackDisplayInfo()

        audioPlaybackManager.startPlayback(
            uri = uri,
            metadata =
                AudioPlaybackMetadata(
                    title = info?.title,
                    subtitle = info?.subtitle,
                    noteId = id,
                    accentColor = info?.accentColor,
                ),
            onProgressUpdated = { newProgress ->
                progress = newProgress
            },
            onPlaybackCompleted = {
                // A playback error also invokes this callback (not just natural end-of-track), so
                // only force progress to 100% on a real completion — otherwise leave the actual
                // (partial or zero) progress in place instead of implying the track fully played.
                val failed = statusProvider?.playbackStatus?.value?.errorMessage != null
                isPlaying = false
                if (!failed) progress = 1f
            },
        )
    }

    val pause: () -> Unit = {
        isPlaying = false
        audioPlaybackManager.pausePlayback()
    }

    val stop: () -> Unit = {
        isPlaying = false
        currentlyPlayingId = null
        currentAudioUri = null
        progress = 0f
        displayInfo = AudioPlaybackDisplayInfo()
        audioPlaybackManager.stopPlayback()
    }

    val seekTo: (position: Float) -> Unit = { pos ->
        progress = pos
        audioPlaybackManager.seekTo(pos)
    }

    val selectOutputDevice: (String) -> Unit = { deviceId ->
        audioRouteRepository.selectOutputDevice(deviceId)
    }

    // Clean up when the composition is disposed
    DisposableEffect(audioPlaybackManager) {
        onDispose {
            audioPlaybackManager.release()
        }
    }

    // Create state object with current values and functions
    val playbackState =
        AudioPlaybackState(
            currentlyPlayingId = currentlyPlayingId,
            currentUri = currentAudioUri,
            isPlaying = isPlaying,
            progress = progress,
            duration = duration,
            displayInfo = displayInfo,
            outputSelection = outputSelection,
            play = play,
            pause = pause,
            stop = stop,
            seekTo = seekTo,
            selectOutputDevice = selectOutputDevice,
        )

    // Provide the state to all descendants
    CompositionLocalProvider(
        LocalAudioPlaybackState provides playbackState,
        LocalPlaybackHandoff provides handoff,
        LocalAudioContextProcessor provides audioContextProcessor,
    ) {
        content()
    }
}
