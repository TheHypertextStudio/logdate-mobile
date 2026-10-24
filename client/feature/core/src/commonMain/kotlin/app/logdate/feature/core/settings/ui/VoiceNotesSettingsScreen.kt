@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.common.MaterialCard
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.voice_notes_model_action_download
import logdate.client.feature.core.generated.resources.voice_notes_model_action_retry
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_corrupt
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_no_network
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_server
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_storage
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_unknown
import logdate.client.feature.core.generated.resources.voice_notes_model_failed_unsupported
import logdate.client.feature.core.generated.resources.voice_notes_model_status_downloading
import logdate.client.feature.core.generated.resources.voice_notes_model_status_extracting
import logdate.client.feature.core.generated.resources.voice_notes_model_status_idle
import logdate.client.feature.core.generated.resources.voice_notes_model_status_ready
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_description
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_title
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_description
import logdate.client.feature.core.generated.resources.voice_notes_model_transcription_title
import logdate.client.feature.core.generated.resources.voice_notes_settings
import logdate.client.feature.core.generated.resources.voice_notes_settings_intro
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VoiceNotesSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VoiceNotesSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    VoiceNotesSettingsContent(
        state = state,
        onDownloadTranscription = viewModel::downloadTranscriptionModel,
        onDownloadTagging = viewModel::downloadTaggingModel,
        onBack = onBack,
        onQuietSaveChanged = viewModel::setQuietSaveEnabled,
        modifier = modifier,
    )
}

@Composable
fun VoiceNotesSettingsContent(
    state: VoiceNotesSettingsViewModel.UiState,
    onDownloadTranscription: () -> Unit,
    onDownloadTagging: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onQuietSaveChanged: (Boolean) -> Unit = {},
) {
    FoldableBookLayout(
        modifier = modifier.fillMaxSize(),
        minPaneWidth = 320.dp,
        startPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                QuietSaveSettingsSection(state, onQuietSaveChanged, Modifier.padding(horizontal = Spacing.lg))
                Text(
                    text = stringResource(Res.string.voice_notes_settings_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
                ModelRow(
                    title = stringResource(Res.string.voice_notes_model_transcription_title),
                    description = stringResource(Res.string.voice_notes_model_transcription_description),
                    row = state.transcription,
                    onDownload = onDownloadTranscription,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        },
        endPane = {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                ModelRow(
                    title = stringResource(Res.string.voice_notes_model_tagging_title),
                    description = stringResource(Res.string.voice_notes_model_tagging_description),
                    row = state.tagging,
                    onDownload = onDownloadTagging,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        },
        standardContent = {
            SettingsScaffold(
                title = stringResource(Res.string.voice_notes_settings),
                onBack = onBack,
                modifier = modifier,
            ) {
                if (state.quietSaveAvailable) {
                    item {
                        QuietSaveSettingsSection(state, onQuietSaveChanged, Modifier.padding(horizontal = Spacing.lg))
                    }
                }
                item {
                    Text(
                        text = stringResource(Res.string.voice_notes_settings_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    )
                }
                item {
                    ModelRow(
                        title = stringResource(Res.string.voice_notes_model_transcription_title),
                        description = stringResource(Res.string.voice_notes_model_transcription_description),
                        row = state.transcription,
                        onDownload = onDownloadTranscription,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
                item {
                    ModelRow(
                        title = stringResource(Res.string.voice_notes_model_tagging_title),
                        description = stringResource(Res.string.voice_notes_model_tagging_description),
                        row = state.tagging,
                        onDownload = onDownloadTagging,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }
        },
    )
}

@Composable
private fun ModelRow(
    title: String,
    description: String,
    row: VoiceNotesSettingsViewModel.ModelRowState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialCard(title = title, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelStatusLine(status = row.status)
            ModelProgress(status = row.status)
            ModelAction(status = row.status, onDownload = onDownload)
        }
    }
}

@Composable
private fun ModelStatusLine(status: ModelDownloadStatus) {
    val statusText =
        when (status) {
            ModelDownloadStatus.Idle -> stringResource(Res.string.voice_notes_model_status_idle)
            is ModelDownloadStatus.Downloading -> stringResource(Res.string.voice_notes_model_status_downloading)
            ModelDownloadStatus.Extracting -> stringResource(Res.string.voice_notes_model_status_extracting)
            ModelDownloadStatus.Completed -> stringResource(Res.string.voice_notes_model_status_ready)
            is ModelDownloadStatus.Failure -> stringResource(failureMessageFor(status))
        }
    val color =
        when (status) {
            is ModelDownloadStatus.Failure -> MaterialTheme.colorScheme.error
            ModelDownloadStatus.Completed -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Text(
        text = statusText,
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

@Composable
private fun ModelProgress(status: ModelDownloadStatus) {
    when (status) {
        is ModelDownloadStatus.Downloading -> {
            val fraction = status.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        ModelDownloadStatus.Extracting -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        else -> {
            Unit
        }
    }
}

@Composable
private fun ModelAction(
    status: ModelDownloadStatus,
    onDownload: () -> Unit,
) {
    val label =
        when (status) {
            ModelDownloadStatus.Idle -> {
                stringResource(Res.string.voice_notes_model_action_download)
            }

            is ModelDownloadStatus.Failure -> {
                if (status == ModelDownloadStatus.NotSupported) {
                    return // Nothing the user can do — hide the button
                } else {
                    stringResource(Res.string.voice_notes_model_action_retry)
                }
            }

            else -> {
                return
            } // Downloading, Extracting, Completed — no button
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDownload) {
            Text(text = label)
        }
    }
}

/**
 * Maps a structured download failure to the localized message the row shows
 * inline. Centralized so the same lookup is reused if any other UI surfaces
 * the same status type later.
 */
private fun failureMessageFor(failure: ModelDownloadStatus.Failure): StringResource =
    when (failure) {
        ModelDownloadStatus.NoNetwork -> Res.string.voice_notes_model_failed_no_network
        ModelDownloadStatus.ServerUnavailable -> Res.string.voice_notes_model_failed_server
        ModelDownloadStatus.OutOfStorage -> Res.string.voice_notes_model_failed_storage
        ModelDownloadStatus.ArchiveCorrupt -> Res.string.voice_notes_model_failed_corrupt
        ModelDownloadStatus.NotSupported -> Res.string.voice_notes_model_failed_unsupported
        ModelDownloadStatus.UnknownError -> Res.string.voice_notes_model_failed_unknown
    }
