@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)

package app.logdate.feature.journals.ui.detail

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.audio.AudioLabelResolver
import app.logdate.feature.editor.audio.formatAudioLabel
import app.logdate.feature.editor.ui.audio.AnimatedPlayPauseButton
import app.logdate.ui.audio.AudioPlaybackDisplayInfo
import app.logdate.ui.audio.LocalAudioPlaybackState
import app.logdate.ui.audio.color.PaletteGenerator
import app.logdate.ui.common.AspectRatios
import app.logdate.ui.common.MarkdownPreviewText
import app.logdate.ui.common.MarkdownText
import app.logdate.ui.media.MediaDeviceSelector
import app.logdate.ui.theme.Spacing
import coil3.compose.AsyncImage
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.audio_output
import logdate.client.feature.journal.generated.resources.video_note
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TextEntryCard(
    entry: EntryDisplayData.TextEntry,
    onClick: () -> Unit,
    onRemoveFromJournal: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    InlineEntryCardShell(
        timestamp = entry.timestamp,
        capturedOnWatch = entry.capturedOnWatch,
        capturedDuring = entry.capturedDuring,
        onClick = {
            if (expanded) onClick() else expanded = true
        },
        onRemoveFromJournal = onRemoveFromJournal,
        modifier = modifier,
        cardModifier = cardModifier,
    ) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .animateContentSize()
                    .heightIn(min = 40.dp),
        ) {
            if (expanded) {
                MarkdownText(
                    content = entry.content,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                MarkdownPreviewText(
                    content = entry.content,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun ImageEntryCard(
    entry: EntryDisplayData.ImageEntry,
    onClick: () -> Unit,
    onRemoveFromJournal: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
) {
    VerticalEntryCardShell(
        timestamp = entry.timestamp,
        capturedOnWatch = entry.capturedOnWatch,
        capturedDuring = entry.capturedDuring,
        onClick = onClick,
        onRemoveFromJournal = onRemoveFromJournal,
        modifier = modifier,
        cardModifier = cardModifier,
        contentPadding = 0.dp,
    ) {
        JournalPhotoContent(entry)
    }
}

@Composable
internal fun VideoEntryCard(
    entry: EntryDisplayData.VideoEntry,
    onClick: () -> Unit,
    onRemoveFromJournal: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
) {
    VerticalEntryCardShell(
        timestamp = entry.timestamp,
        capturedOnWatch = entry.capturedOnWatch,
        capturedDuring = entry.capturedDuring,
        onClick = onClick,
        onRemoveFromJournal = onRemoveFromJournal,
        modifier = modifier,
        cardModifier = cardModifier,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(AspectRatios.RATIO_16_9)
                    .clip(RoundedCornerShape(Spacing.sm)),
        ) {
            AsyncImage(
                model = entry.mediaRef,
                contentDescription = stringResource(Res.string.video_note),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Play indicator overlay
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            Icon(
                Icons.Rounded.Videocam,
                contentDescription = null,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.sm)
                        .size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        if (entry.caption.isNotBlank()) {
            Text(
                text = entry.caption,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

@Composable
internal fun AudioEntryCard(
    entry: EntryDisplayData.AudioEntry,
    onClick: () -> Unit,
    onRemoveFromJournal: () -> Unit,
    modifier: Modifier = Modifier,
    cardModifier: Modifier = Modifier,
) {
    val audioPlaybackState = LocalAudioPlaybackState.current
    val isCurrentEntry = audioPlaybackState.currentlyPlayingId == entry.id
    val isEntryPlaying = isCurrentEntry && audioPlaybackState.isPlaying

    val labelResolver = remember { AudioLabelResolver() }
    val labelResult =
        remember(entry.timestamp, entry.locationName) {
            labelResolver.resolve(
                createdAt = entry.timestamp,
                locationName = entry.locationName,
            )
        }
    val resolvedTitle = formatAudioLabel(labelResult)

    // Derive palette from daylight period for the progress indicator and mini-player
    val paletteGenerator = remember { PaletteGenerator() }
    val palette =
        remember(labelResult) {
            when (labelResult) {
                is app.logdate.feature.editor.audio.AudioLabelResult.Contextual -> {
                    paletteGenerator.generate(labelResult.period)
                }

                else -> {
                    null
                }
            }
        }
    val accentColor = palette?.let { Color(it.accentColor) }

    // Smooth progress animation
    val animatedProgress by animateFloatAsState(
        targetValue = if (isCurrentEntry) audioPlaybackState.progress else 0f,
        animationSpec = tween(durationMillis = 100),
        label = "AudioCardProgress",
    )

    InlineEntryCardShell(
        timestamp = entry.timestamp,
        capturedOnWatch = entry.capturedOnWatch,
        capturedDuring = entry.capturedDuring,
        onClick = onClick,
        onRemoveFromJournal = onRemoveFromJournal,
        modifier = modifier,
        cardModifier = cardModifier,
    ) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val stackRouteControls = maxWidth < 360.dp && isCurrentEntry
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedPlayPauseButton(
                        isPlaying = isEntryPlaying,
                        onClick = {
                            if (isEntryPlaying) {
                                audioPlaybackState.pause()
                            } else {
                                audioPlaybackState.play(
                                    entry.id,
                                    entry.mediaRef,
                                    AudioPlaybackDisplayInfo(
                                        title = resolvedTitle,
                                        subtitle = if (entry.durationMs > 0) formatAudioDuration(entry.durationMs) else null,
                                        accentColor = palette?.accentColor,
                                    ),
                                )
                            }
                        },
                        modifier =
                            Modifier
                                .size(40.dp)
                                .testTag("journal-audio-playback-button"),
                        iconSize = 20.dp,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = resolvedTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                            color = accentColor ?: MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.height(2.dp))
                        if (entry.durationMs > 0) {
                            Text(
                                text = formatAudioDuration(entry.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (isCurrentEntry && !stackRouteControls) {
                        Spacer(Modifier.width(Spacing.sm))
                        MediaDeviceSelector(
                            selection = audioPlaybackState.outputSelection,
                            onDeviceSelected = audioPlaybackState.selectOutputDevice,
                            label = stringResource(Res.string.audio_output),
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
                }
                if (stackRouteControls) {
                    MediaDeviceSelector(
                        selection = audioPlaybackState.outputSelection,
                        onDeviceSelected = audioPlaybackState.selectOutputDevice,
                        label = stringResource(Res.string.audio_output),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Formats milliseconds into a human-readable duration string (e.g. "1:23" or "0:05").
 */
private fun formatAudioDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

// endregion

// region Entry card shell

/*
 * Card wrapper for entry types that display media content filling the width.
 * Renders a timestamp label above the card, media content, and an overflow menu.
 */
