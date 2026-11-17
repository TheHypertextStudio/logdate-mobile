@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.rewind.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.logdate.ui.audio.AudioWaveformComponent
import app.logdate.ui.audio.rememberAudioContext
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.timeline.newstuff.TimelineTranscriptPreview
import logdate.client.feature.rewind.generated.resources.Res
import logdate.client.feature.rewind.generated.resources.rewind_pause_audio
import logdate.client.feature.rewind.generated.resources.rewind_play_audio
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val AUDIO_PANEL_BACKGROUND = Color(0xFF1A1A1A)

/**
 * A panel displaying an audio journal entry in story format.
 *
 * Mirrors [TextNotePanel]'s flat reading surface
 * but swaps the quote body for a waveform, a play/pause control, and — when available —
 * the transcript. [transcriptionText] is frequently null: transcription is eventual, not
 * guaranteed by the time a rewind is generated, so the panel must read fine either way.
 *
 * @param uri Playable URI of the recording
 * @param durationMs Recording length in milliseconds
 * @param transcriptionText The transcript, or null if not yet available
 * @param dateFormatted Formatted date string showing when the recording was made
 * @param recordedAt When the recording was made
 * @param cachedAmplitudes Cached waveform amplitudes, or null when uncached. An uncached
 *   recording's waveform is read from the file while the panel shows, rather than drawn flat
 * @param isPlaying Whether this panel's recording is the one currently playing
 * @param playbackProgress Playback progress (0.0–1.0) while [isPlaying] is true
 * @param onTogglePlayback Invoked when the user taps the play/pause control
 * @param modifier Modifier for customizing the panel container
 */
@Composable
fun AudioNotePanel(
    sourceId: Uuid,
    uri: String,
    durationMs: Long,
    transcriptionText: String?,
    dateFormatted: String,
    recordedAt: Instant,
    cachedAmplitudes: List<Float>?,
    isPlaying: Boolean,
    playbackProgress: Float,
    onTogglePlayback: (Uuid, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasTranscript = !transcriptionText.isNullOrBlank()
    // Read the waveform from the recording only when the rewind didn't carry one.
    val extracted = if (cachedAmplitudes.isNullOrEmpty()) rememberAudioContext(uri, durationMs, recordedAt)?.amplitudes else null
    val amplitudes = cachedAmplitudes?.takeIf { it.isNotEmpty() } ?: extracted.orEmpty()
    RewindMemoryStage(modifier) { availableHeight ->
        RewindMemoryFrame(
            sourceId.toString(),
            RewindMemoryLoadState.Ready,
            Modifier
                .widthIn(max = RewindReadingMaxWidth)
                .fillMaxWidth()
                .heightIn(min = minOf(if (hasTranscript) 220.dp else 132.dp, availableHeight), max = availableHeight),
            backgroundColor = AUDIO_PANEL_BACKGROUND,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                if (hasTranscript) {
                    MaterialTheme(
                        colorScheme =
                            MaterialTheme.colorScheme.copy(
                                primary = Color.White,
                                onSurface = Color.White,
                                surfaceContainerHighest = AUDIO_PANEL_BACKGROUND,
                            ),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides Color.White) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                TimelineTranscriptPreview(
                                    sourceId,
                                    fallbackTranscript = transcriptionText,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                Text(
                    formatAudioNoteDuration(durationMs),
                    Modifier.align(Alignment.End),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.15f),
                        modifier =
                            Modifier.size(56.dp).clip(CircleShape).clickable(
                                role = Role.Button,
                                onClickLabel =
                                    stringResource(
                                        if (isPlaying) Res.string.rewind_pause_audio else Res.string.rewind_play_audio,
                                    ),
                            ) { onTogglePlayback(sourceId, uri) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (isPlaying) PlatformIcons.pause() else PlatformIcons.play(),
                                stringResource(if (isPlaying) Res.string.rewind_pause_audio else Res.string.rewind_play_audio),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    AudioWaveformComponent(
                        audioLevels = amplitudes,
                        waveformColor = if (isPlaying) Color.White else Color.White.copy(alpha = 0.6f),
                        minHeight = 56.dp,
                        modifier = Modifier.weight(1f).height(56.dp),
                    )
                }
                if (isPlaying) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            playbackProgress
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(
                                    3.dp,
                                ).clip(
                                    RoundedCornerShape(1.5.dp),
                                ),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                        strokeCap = StrokeCap.Round,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    dateFormatted,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

private fun formatAudioNoteDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
