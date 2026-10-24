@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.client.awareness.daylight.DaylightPeriod
import app.logdate.ui.audio.color.PaletteGenerator
import app.logdate.ui.audio.expansion.AudioExpansionController
import app.logdate.ui.audio.expansion.AudioExpansionState
import app.logdate.ui.audio.expansion.CollapsedAudioCard
import app.logdate.ui.audio.expansion.SpatialExpandedAudioBlock
import app.logdate.ui.media.MediaDeviceSelector
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.MomentAudioUiState
import app.logdate.ui.timeline.buildTranscriptExcerpt
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.media_device_audio_output
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

/**
 * A recorded moment, wherever it appears: the timeline feed, a day's detail, a rewind.
 *
 * Replaces the three separate cards the product had grown for the same content, none of which
 * were playable and all of which drew the same eight fixed bars. Presentation escalates through
 * [AudioExpansionController] rather than through per-screen components.
 */
@Composable
fun MomentAudioCard(
    audio: MomentAudioUiState,
    timeOfDay: DaylightPeriod?,
    modifier: Modifier = Modifier,
) {
    val playbackState = LocalAudioPlaybackState.current
    val transcriptionState = LocalTranscriptionState.current
    val audioContextProcessor = LocalAudioContextProcessor.current

    LaunchedEffect(audio.noteId, audio.uri, audio.durationMs) {
        audio.noteId?.let(transcriptionState.requestTranscription)
    }

    val isCurrent = playbackState.currentlyPlayingId == audio.noteId
    val isPlaying = isCurrent && playbackState.isPlaying
    val progress = if (isCurrent) playbackState.progress else 0f

    val controller = remember(audio.uri) { AudioExpansionController() }
    val outputSheetVisible = remember(audio.uri) { mutableStateOf(false) }
    val handoff = LocalPlaybackHandoff.current

    // The recording's own daylight period decides its colour, so a run of voice notes reads as
    // the times of day they were made rather than one flat accent.
    val palette =
        remember(timeOfDay) {
            PaletteGenerator().generate(timeOfDay ?: DaylightPeriod.MIDDAY)
        }

    // Amplitudes come from the processor rather than WaveformStorage directly: storage is a
    // cache with no backfill, so a recording the user has not opened elsewhere has none. The
    // processor loads or extracts and then caches, and emits the cached set first so the card
    // paints immediately.
    // Remembered: an un-remembered now() would change on every recomposition and keep
    // re-triggering waveform work and re-rendering the expanded state's timestamp.
    val recordedAt = remember(audio.uri, audio.recordedAt) { audio.recordedAt ?: Clock.System.now() }
    val audioContext by produceState<AudioContext?>(null, audio.uri, audio.durationMs, audioContextProcessor) {
        val processor = audioContextProcessor ?: return@produceState
        runCatching {
            processor
                .processProgressively(
                    audioUri = audio.uri,
                    durationMs = audio.durationMs,
                    createdAt = recordedAt,
                    latitude = null,
                    longitude = null,
                ).collect { context -> value = context }
        }
    }

    val transcript =
        audio.transcript?.trim()?.takeIf(String::isNotEmpty)
            ?: audio.noteId
                ?.let(transcriptionState.getTranscriptionText)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
    val excerpt = remember(transcript) { transcript?.let(::buildTranscriptExcerpt)?.text }
    val isTranscribing = audio.noteId?.let(transcriptionState.isTranscriptionInProgress) ?: false

    // What the mini player and lock screen show. The transcript's opening sentence identifies a
    // recording far better than the generic title this used to carry.
    val displayInfo =
        remember(excerpt, audio.durationMs) {
            AudioPlaybackDisplayInfo(
                title = excerpt,
                subtitle = formatAudioDuration(audio.durationMs),
            )
        }

    // What expandOnPlayback was always for: a recording opens itself while it plays and closes
    // again when it finishes, so the card never has to steal the tap that opens the day.
    LaunchedEffect(isPlaying) {
        if (isPlaying) controller.onPlaybackStarted() else controller.onPlaybackCompleted()
    }

    val togglePlayback = {
        when {
            isPlaying -> playbackState.pause()
            audio.noteId != null -> playbackState.play(audio.noteId, audio.uri, displayInfo)
            else -> Unit
        }
    }
    val playbackActions: @Composable () -> Unit = {
        if (isCurrent) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                MediaDeviceSelector(
                    selection = playbackState.outputSelection,
                    onDeviceSelected = playbackState.selectOutputDevice,
                    label = stringResource(Res.string.media_device_audio_output),
                    visibilityState = outputSheetVisible,
                )
                PlaybackHandoffAction(handoff)
            }
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
        val cardModifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("moment-audio-card")
        when (controller.currentState) {
            AudioExpansionState.SPATIAL_EXPANDED -> {
                SpatialExpandedAudioBlock(
                    amplitudes = audioContext?.amplitudes.orEmpty(),
                    progress = progress,
                    isPlaying = isPlaying,
                    palette = palette,
                    durationMs = audio.durationMs,
                    createdAt = recordedAt,
                    segments = audioContext?.segments.orEmpty(),
                    transcript = transcript,
                    isTranscribing = isTranscribing,
                    onPlayPause = togglePlayback,
                    onSeek = { position -> if (isCurrent) playbackState.seekTo(position) },
                    playbackActions = playbackActions,
                    modifier = cardModifier,
                )
            }

            else -> {
                CollapsedAudioCard(
                    amplitudes = audioContext?.amplitudes.orEmpty(),
                    progress = progress,
                    isPlaying = isPlaying,
                    palette = palette,
                    durationMs = audio.durationMs,
                    transcriptExcerpt = excerpt,
                    isTranscribing = isTranscribing,
                    onPlayPause = togglePlayback,
                    playbackActions = playbackActions,
                    modifier = cardModifier,
                )
            }
        }
    }
}
