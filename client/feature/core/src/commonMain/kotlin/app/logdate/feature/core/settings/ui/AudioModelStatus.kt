@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.audio_download_waiting_update
import logdate.client.feature.core.generated.resources.voice_notes_model_bytes_downloaded
import logdate.client.feature.core.generated.resources.voice_notes_model_bytes_progress
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_check
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_corrupt
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_no_network
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_server
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_storage
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_unknown
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_unsupported
import logdate.client.feature.core.generated.resources.voice_notes_model_status_checking
import logdate.client.feature.core.generated.resources.voice_notes_model_status_downloading
import logdate.client.feature.core.generated.resources.voice_notes_model_status_extracting
import logdate.client.feature.core.generated.resources.voice_notes_model_status_idle
import logdate.client.feature.core.generated.resources.voice_notes_model_status_managed
import logdate.client.feature.core.generated.resources.voice_notes_model_status_ready
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun audioModelStatusText(status: ModelDownloadStatus): String =
    stringResource(
        when (status) {
            ModelDownloadStatus.Checking -> Res.string.voice_notes_model_status_checking
            ModelDownloadStatus.ManagedBySystem -> Res.string.voice_notes_model_status_managed
            ModelDownloadStatus.Idle -> Res.string.voice_notes_model_status_idle
            is ModelDownloadStatus.Downloading -> Res.string.voice_notes_model_status_downloading
            ModelDownloadStatus.Extracting -> Res.string.voice_notes_model_status_extracting
            ModelDownloadStatus.Completed -> Res.string.voice_notes_model_status_ready
            ModelDownloadStatus.AwaitingUpdate -> Res.string.audio_download_waiting_update
            ModelDownloadStatus.CheckFailed -> Res.string.voice_notes_model_failed_check
            ModelDownloadStatus.NoNetwork -> Res.string.voice_notes_model_failed_no_network
            ModelDownloadStatus.ServerUnavailable -> Res.string.voice_notes_model_failed_server
            ModelDownloadStatus.OutOfStorage -> Res.string.voice_notes_model_failed_storage
            ModelDownloadStatus.ArchiveCorrupt -> Res.string.voice_notes_model_failed_corrupt
            ModelDownloadStatus.UnknownError -> Res.string.voice_notes_model_failed_unknown
            ModelDownloadStatus.NotSupported -> Res.string.voice_notes_model_failed_unsupported
        },
    )

@Composable
internal fun audioModelStatusColor(status: ModelDownloadStatus) =
    when (status) {
        ModelDownloadStatus.AwaitingUpdate,
        ModelDownloadStatus.CheckFailed,
        ModelDownloadStatus.NoNetwork,
        ModelDownloadStatus.ServerUnavailable,
        ModelDownloadStatus.OutOfStorage,
        ModelDownloadStatus.NotSupported,
        -> MaterialTheme.colorScheme.onSurfaceVariant
        ModelDownloadStatus.Completed -> MaterialTheme.colorScheme.primary
        is ModelDownloadStatus.Failure -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@Composable
internal fun AudioModelProgress(
    status: ModelDownloadStatus,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val reduceMotion by rememberSystemReduceMotion()
    when (status) {
        is ModelDownloadStatus.Downloading ->
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                val fraction = status.fraction
                if (reduceMotion && fraction != null) {
                    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = color)
                } else if (!reduceMotion && fraction == null) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth(), color = color)
                } else if (!reduceMotion && fraction != null) {
                    LinearWavyProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = color)
                }
                val transferred = formatByteSize(status.bytesDownloaded.coerceAtLeast(0))
                val total = status.totalBytes?.takeIf { it > 0 }
                Text(
                    if (total == null) {
                        stringResource(Res.string.voice_notes_model_bytes_downloaded, transferred)
                    } else {
                        stringResource(Res.string.voice_notes_model_bytes_progress, transferred, formatByteSize(total))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        else -> Unit
    }
}
