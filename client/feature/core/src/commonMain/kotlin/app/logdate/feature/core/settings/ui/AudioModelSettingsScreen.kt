@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.voice_notes_model_recording_independent
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_detail
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_system_detail
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_title
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_file_detail
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_live_detail
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_system_detail
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_title
import logdate.client.feature.core.generated.resources.voice_notes_model_unavailable_detail
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

enum class AudioSettingsCapability { Transcription, SoundRecognition }

@Composable
fun AudioModelSettingsScreen(
    capability: AudioSettingsCapability,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceNotesSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val transcription = capability == AudioSettingsCapability.Transcription
    AudioModelSettingsContent(
        capability,
        state,
        onDownload = if (transcription) viewModel::downloadTranscriptionModel else viewModel::downloadTaggingModel,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun AudioModelSettingsContent(
    capability: AudioSettingsCapability,
    state: VoiceNotesSettingsViewModel.UiState,
    onDownload: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transcription = capability == AudioSettingsCapability.Transcription
    val status = if (transcription) state.transcription.status else state.tagging.status
    val title = if (transcription) Res.string.voice_notes_model_transcription_title else Res.string.voice_notes_model_tagging_title
    val description =
        when {
            status == ModelDownloadStatus.NotSupported -> Res.string.voice_notes_model_unavailable_detail
            !transcription && status == ModelDownloadStatus.ManagedBySystem -> Res.string.voice_notes_model_tagging_system_detail
            !transcription -> Res.string.voice_notes_model_tagging_detail
            status == ModelDownloadStatus.ManagedBySystem -> Res.string.voice_notes_model_transcription_system_detail
            state.supportsLiveTranscription -> Res.string.voice_notes_model_transcription_live_detail
            else -> Res.string.voice_notes_model_transcription_file_detail
        }
    AudioSettingsScaffold(stringResource(title), onBack, modifier) {
        item {
            AudioModelSetupPanel(capability, status, stringResource(description), onDownload)
        }
        item {
            Text(
                stringResource(Res.string.voice_notes_model_recording_independent),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }
    }
}
