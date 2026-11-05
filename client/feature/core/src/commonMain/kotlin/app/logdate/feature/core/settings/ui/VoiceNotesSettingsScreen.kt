@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.audio_transcription_live_description
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_description
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_title
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_description
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_title
import logdate.client.feature.core.generated.resources.voice_notes_settings
import logdate.client.feature.core.generated.resources.voice_notes_settings_intro
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VoiceNotesSettingsScreen(
    onBack: () -> Unit,
    onOpenTranscription: () -> Unit,
    onOpenSoundRecognition: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceNotesSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AudioNotesSettingsContent(state, onOpenTranscription, onOpenSoundRecognition, onBack, modifier)
}

@Composable
fun AudioNotesSettingsContent(
    state: VoiceNotesSettingsViewModel.UiState,
    onOpenTranscription: () -> Unit,
    onOpenSoundRecognition: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AudioSettingsScaffold(stringResource(Res.string.voice_notes_settings), onBack, modifier) {
        item {
            Text(
                stringResource(Res.string.voice_notes_settings_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
        item {
            Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                CapabilityRow(
                    AudioSettingsCapability.Transcription,
                    stringResource(Res.string.voice_notes_model_transcription_title),
                    stringResource(
                        if (state.supportsLiveTranscription &&
                            state.transcription.status != ModelDownloadStatus.NotSupported
                        ) {
                            Res.string.audio_transcription_live_description
                        } else {
                            Res.string.voice_notes_model_transcription_description
                        },
                    ),
                    state.transcription.status,
                    onOpenTranscription,
                )
                CapabilityRow(
                    AudioSettingsCapability.SoundRecognition,
                    stringResource(Res.string.voice_notes_model_tagging_title),
                    stringResource(Res.string.voice_notes_model_tagging_description),
                    state.tagging.status,
                    onOpenSoundRecognition,
                )
            }
        }
    }
}

@Composable
private fun CapabilityRow(
    capability: AudioSettingsCapability,
    title: String,
    description: String,
    status: ModelDownloadStatus,
    onOpen: () -> Unit,
) {
    val transcription = capability == AudioSettingsCapability.Transcription
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
        shape =
            RoundedCornerShape(
                topStart = if (transcription) 28.dp else 4.dp,
                topEnd = if (transcription) 28.dp else 4.dp,
                bottomStart = if (transcription) 4.dp else 28.dp,
                bottomEnd = if (transcription) 4.dp else 28.dp,
            ),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                AudioCapabilityEmblem(capability)
                Surface(shape = RoundedCornerShape(50), color = audioCapabilityContainer(capability)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = audioCapabilityOnContainer(capability),
                        modifier = Modifier.padding(Spacing.sm).size(24.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AudioModelStateLabel(status)
        }
    }
}

/** Compatibility entry point for the shared screenshot catalog. */
@Deprecated("Use AudioNotesSettingsContent with navigation callbacks")
@Composable
fun VoiceNotesSettingsContent(
    state: VoiceNotesSettingsViewModel.UiState,
    onDownloadTranscription: () -> Unit,
    onDownloadTagging: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = AudioNotesSettingsContent(state, onDownloadTranscription, onDownloadTagging, onBack, modifier)
