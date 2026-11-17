package app.logdate.screenshots.components.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.awareness.daylight.DaylightPeriod
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.feature.editor.ui.audio.ActiveRecordingDisplay
import app.logdate.feature.editor.ui.audio.AudioRecordingControls
import app.logdate.feature.editor.ui.audio.AudioRecordingDisplay
import app.logdate.feature.editor.ui.audio.AudioTranscriptionUi
import app.logdate.feature.editor.ui.audio.AudioUiState
import app.logdate.feature.editor.ui.audio.EmptyAudioBlockContent
import app.logdate.feature.editor.ui.audio.expansion.ImmersiveAudioScreen
import app.logdate.feature.editor.ui.editor.RecordingState
import app.logdate.screenshots.common.ScreenshotTestData
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.audio.expansion.SpatialExpandedAudioBlock
import app.logdate.ui.audio.model.AudioPalette
import com.android.tools.screenshot.PreviewTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val mockPalette =
    AudioPalette(
        waveformGradientStart = 0xFFE8A044,
        waveformGradientEnd = 0xFFD4603A,
        playedFillColor = 0xFFE8A044,
        accentColor = 0xFFE8A044,
        immersiveBackground = 0xFF1A0F05,
    )

private val mockCreatedAt = Instant.fromEpochMilliseconds(1_740_000_000_000L)

// ─── Audio Block States ─────────────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_AudioBlock_EmptyState() {
    ScreenshotTheme {
        EmptyAudioBlockContent()
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_AudioRecordingControls_Inactive() {
    ScreenshotTheme {
        AudioRecordingControls(
            recordingState = RecordingState.INACTIVE,
            audioLevels = emptyList(),
            recordingDuration = 0.seconds,
            onStartRecording = {},
            onStopRecording = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_AudioRecordingControls_Recording() {
    ScreenshotTheme {
        AudioRecordingControls(
            recordingState = RecordingState.RECORDING,
            audioLevels = ScreenshotTestData.mockAudioLevels,
            recordingDuration = 1.minutes + 23.seconds,
            onStartRecording = {},
            onStopRecording = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_AudioRecordingDisplay_Active() {
    ScreenshotTheme {
        AudioRecordingDisplay(
            audioLevels = ScreenshotTestData.mockAudioLevels,
            recordingDuration = 2.minutes + 15.seconds,
            isRecording = true,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_ActiveRecordingDisplay_Recording() {
    ScreenshotTheme {
        ActiveRecordingDisplay(
            audioLevels = ScreenshotTestData.mockAudioLevels,
            recordingDuration = 1.minutes + 45.seconds,
            onRestart = {},
            onPause = {},
            onFinish = {},
            isPaused = false,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_ActiveRecordingDisplay_Paused() {
    ScreenshotTheme {
        ActiveRecordingDisplay(
            audioLevels = ScreenshotTestData.mockAudioLevels,
            recordingDuration = 1.minutes + 45.seconds,
            onRestart = {},
            onPause = {},
            onFinish = {},
            isPaused = true,
        )
    }
}

// ─── Audio Transcription States ─────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_Transcription_NotRequested() {
    ScreenshotTheme {
        AudioTranscriptionUi(
            transcriptionState = AudioUiState.TranscriptionState.NotRequested,
            onRequestTranscription = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_Transcription_Pending() {
    ScreenshotTheme {
        AudioTranscriptionUi(
            transcriptionState = AudioUiState.TranscriptionState.Pending,
            onRequestTranscription = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_Transcription_InProgress() {
    ScreenshotTheme {
        AudioTranscriptionUi(
            transcriptionState = AudioUiState.TranscriptionState.InProgress,
            onRequestTranscription = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_Transcription_Success() {
    ScreenshotTheme {
        AudioTranscriptionUi(
            transcriptionState =
                AudioUiState.TranscriptionState.Success(
                    text = "Today was a good day. I went for a walk in the park and saw the cherry blossoms beginning to bloom.",
                ),
            onRequestTranscription = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun Editor_Transcription_Error() {
    ScreenshotTheme {
        AudioTranscriptionUi(
            transcriptionState =
                AudioUiState.TranscriptionState.Error(
                    reason = TranscriptionFailure.Unknown,
                ),
            onRequestTranscription = {},
        )
    }
}

// ─── Spatial / Immersive Audio ──────────────────────────────────────────────────

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun SpatialExpandedAudioBlock_Paused() {
    ScreenshotTheme {
        SpatialExpandedAudioBlock(
            amplitudes = ScreenshotTestData.mockAmplitudes,
            progress = 0.35f,
            isPlaying = false,
            palette = mockPalette,
            durationMs = 127_000L,
            createdAt = mockCreatedAt,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun SpatialExpandedAudioBlock_Playing() {
    ScreenshotTheme {
        SpatialExpandedAudioBlock(
            amplitudes = ScreenshotTestData.mockAmplitudes,
            progress = 0.6f,
            isPlaying = true,
            palette = mockPalette,
            durationMs = 127_000L,
            createdAt = mockCreatedAt,
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun ImmersiveAudio() {
    ScreenshotTheme {
        ImmersiveAudioScreen(
            amplitudes = ScreenshotTestData.mockAmplitudes,
            progress = 0.4f,
            isPlaying = true,
            palette = mockPalette,
            daylightPeriod = DaylightPeriod.GOLDEN_HOUR,
            durationMs = 185_000L,
            createdAt = mockCreatedAt,
        )
    }
}
