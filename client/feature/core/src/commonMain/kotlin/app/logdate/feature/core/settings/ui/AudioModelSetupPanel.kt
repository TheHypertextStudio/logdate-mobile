@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.audio_recognition_heading
import logdate.client.feature.core.generated.resources.audio_transcription_heading
import logdate.client.feature.core.generated.resources.voice_notes_model_action_download
import logdate.client.feature.core.generated.resources.voice_notes_model_tagging_non_speech
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AudioModelSetupPanel(
    capability: AudioSettingsCapability,
    status: ModelDownloadStatus,
    description: String,
    onDownload: () -> Unit,
) {
    val transcription = capability == AudioSettingsCapability.Transcription
    Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = audioCapabilityContainer(capability),
            contentColor = audioCapabilityOnContainer(capability),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AudioCapabilityEmblem(capability, size = 88.dp, filled = true)
                Text(
                    stringResource(if (transcription) Res.string.audio_transcription_heading else Res.string.audio_recognition_heading),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(description, style = MaterialTheme.typography.bodyLarge)
                if (!transcription && status != ModelDownloadStatus.NotSupported) {
                    Text(stringResource(Res.string.voice_notes_model_tagging_non_speech), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AudioModelStateLabel(status, prominent = true)
                AudioModelProgress(status, audioCapabilityColor(capability))
                if (status == ModelDownloadStatus.Idle) {
                    ModelSetupAction(capability, stringResource(Res.string.voice_notes_model_action_download), onDownload)
                }
            }
        }
    }
}

@Composable
private fun ModelSetupAction(
    capability: AudioSettingsCapability,
    label: String,
    onClick: () -> Unit,
) {
    val reduceMotion by rememberSystemReduceMotion()
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
        shapes =
            if (reduceMotion) {
                ButtonDefaults.shapes(
                    shape = RoundedCornerShape(50),
                    pressedShape = RoundedCornerShape(50),
                )
            } else {
                ButtonDefaults.shapes()
            },
        contentPadding = ButtonDefaults.MediumContentPadding,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = audioCapabilityColor(capability),
                contentColor =
                    if (capability ==
                        AudioSettingsCapability.Transcription
                    ) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onTertiary
                    },
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.MediumIconSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.MediumIconSize))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
