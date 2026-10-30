package app.logdate.ui.audio

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.logdate.client.repository.transcription.TranscriptionStatus
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.audio_transcribing
import logdate.client.ui.generated.resources.audio_transcription_failed
import logdate.client.ui.generated.resources.audio_transcription_no_speech
import logdate.client.ui.generated.resources.audio_transcription_queued
import logdate.client.ui.generated.resources.audio_transcription_retry
import org.jetbrains.compose.resources.stringResource

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun AudioTranscriptStatus(
    status: TranscriptionStatus?,
    hasTranscript: Boolean,
    isTranscribing: Boolean,
    onRetry: (() -> Unit)?,
) {
    val label =
        when {
            status == TranscriptionStatus.PENDING -> Res.string.audio_transcription_queued
            status == TranscriptionStatus.IN_PROGRESS -> Res.string.audio_transcribing
            status == TranscriptionStatus.FAILED -> Res.string.audio_transcription_failed
            status == TranscriptionStatus.COMPLETED && !hasTranscript -> Res.string.audio_transcription_no_speech
            status == null && isTranscribing -> Res.string.audio_transcribing
            else -> null
        } ?: return
    Column {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (status == TranscriptionStatus.FAILED && onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.audio_transcription_retry)) }
        }
    }
}
