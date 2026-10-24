package app.logdate.feature.editor.ui.audio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.convert_to_text
import logdate.client.feature.editor.generated.resources.error
import logdate.client.feature.editor.generated.resources.transcribing_audio
import logdate.client.feature.editor.generated.resources.transcription_failed_audio_kept
import logdate.client.feature.editor.generated.resources.transcription_improving
import logdate.client.feature.editor.generated.resources.transcription_improving_description
import logdate.client.feature.editor.generated.resources.transcription_queued
import logdate.client.feature.editor.generated.resources.transcription_ready
import logdate.client.feature.editor.generated.resources.transcription_ready_description
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Displays transcription UI for an audio note, handling various transcription states.
 * This is a stateless UI component that receives its state and callbacks from the parent.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun AudioTranscriptionUi(
    transcriptionState: AudioUiState.TranscriptionState,
    onRequestTranscription: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Display appropriate UI based on transcription state
    AnimatedContent(
        targetState = transcriptionState,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier.fillMaxWidth(),
    ) { state ->
        when (state) {
            is AudioUiState.TranscriptionState.NotRequested -> {
                TranscriptionNotAvailableUi(
                    onRequestTranscription = onRequestTranscription,
                    modifier = Modifier,
                )
            }

            is AudioUiState.TranscriptionState.Pending -> {
                TranscriptionPendingUi()
            }

            is AudioUiState.TranscriptionState.InProgress -> {
                TranscriptionInProgressUi()
            }

            is AudioUiState.TranscriptionState.Success -> {
                TranscriptionSuccessUi(
                    text = state.text,
                    isRefining = state.isRefining,
                )
            }

            is AudioUiState.TranscriptionState.Error -> {
                TranscriptionErrorUi(
                    reason = state.reason,
                    onRetry = onRequestTranscription,
                    modifier = Modifier,
                )
            }
        }
    }
}

/**
 * UI when no transcription is available yet.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun TranscriptionNotAvailableUi(
    onRequestTranscription: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = onRequestTranscription,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(stringResource(Res.string.convert_to_text))
        }
    }
}

/**
 * UI when transcription is pending.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun TranscriptionPendingUi(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.transcription_queued),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * UI when transcription is in progress.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun TranscriptionInProgressUi(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = stringResource(Res.string.transcribing_audio),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * UI when transcription is successful.
 *
 * When [isRefining] is true, a higher-accuracy refinement pass is still
 * rewriting parts of [text] in the background. We crossfade the text on every
 * update so each replacement feels like the words are correcting themselves
 * live in front of the user, instead of a loading state.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun TranscriptionSuccessUi(
    text: String,
    isRefining: Boolean,
    modifier: Modifier = Modifier,
) {
    FoldableBookLayout(
        modifier = modifier.fillMaxWidth(),
        minPaneWidth = 320.dp,
        startPane = {
            TranscriptCard(
                text = text,
                isRefining = isRefining,
                modifier = Modifier.fillMaxSize(),
            )
        },
        endPane = {
            TranscriptionStatusCard(
                isRefining = isRefining,
                modifier = Modifier.fillMaxSize(),
            )
        },
        standardContent = {
            TranscriptCard(
                text = text,
                isRefining = isRefining,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TranscriptCard(
    text: String,
    isRefining: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .padding(Spacing.sm),
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.padding(Spacing.md),
            label = "transcript-refinement",
        ) { current ->
            Text(
                text = current,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isRefining) {
                        // Soften while refinement is still rewriting parts of the
                        // text, telegraphing upcoming change without a spinner.
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun TranscriptionStatusCard(
    isRefining: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text =
                    stringResource(
                        if (isRefining) {
                            Res.string.transcription_improving
                        } else {
                            Res.string.transcription_ready
                        },
                    ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    stringResource(
                        if (isRefining) {
                            Res.string.transcription_improving_description
                        } else {
                            Res.string.transcription_ready_description
                        },
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * UI when transcription fails.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun TranscriptionErrorUi(
    reason: TranscriptionFailure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = PlatformIcons.error(),
                contentDescription = stringResource(Res.string.error),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = stringResource(Res.string.transcription_failed_audio_kept),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = onRetry,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    painter = PlatformIcons.refresh(),
                    contentDescription = stringResource(UiRes.string.common_try_again),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
