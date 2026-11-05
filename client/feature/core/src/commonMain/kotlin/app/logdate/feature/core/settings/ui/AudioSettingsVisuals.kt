@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Phonelink
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.media.audio.download.ModelDownloadStatus
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing

@Composable
internal fun AudioCapabilityEmblem(
    capability: AudioSettingsCapability,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    filled: Boolean = false,
) {
    val transcription = capability == AudioSettingsCapability.Transcription
    Surface(
        modifier = modifier.size(size),
        shape = if (transcription) MaterialShapes.Square.toShape() else MaterialShapes.Cookie6Sided.toShape(),
        color = if (filled) audioCapabilityColor(capability) else audioCapabilityContainer(capability),
        contentColor =
            if (filled) {
                if (transcription) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary
            } else {
                audioCapabilityOnContainer(capability)
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                if (transcription) Icons.Rounded.Subtitles else Icons.Rounded.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(size * 0.48f),
            )
        }
    }
}

@Composable
internal fun audioCapabilityContainer(capability: AudioSettingsCapability): Color =
    if (capability ==
        AudioSettingsCapability.Transcription
    ) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }

@Composable
internal fun audioCapabilityOnContainer(capability: AudioSettingsCapability): Color =
    if (capability ==
        AudioSettingsCapability.Transcription
    ) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }

@Composable
internal fun audioCapabilityColor(capability: AudioSettingsCapability): Color =
    if (capability == AudioSettingsCapability.Transcription) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

@Composable
internal fun AudioModelStateLabel(
    status: ModelDownloadStatus,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
) {
    val reduceMotion by rememberSystemReduceMotion()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        val color = audioModelStatusColor(status)
        if (!reduceMotion && (status == ModelDownloadStatus.Checking || status == ModelDownloadStatus.Extracting)) {
            LoadingIndicator(modifier = Modifier.size(if (prominent) 40.dp else 24.dp), color = color)
        } else if (status != ModelDownloadStatus.Idle) {
            Icon(
                when (status) {
                    ModelDownloadStatus.Checking, ModelDownloadStatus.Extracting -> Icons.Rounded.HourglassEmpty
                    ModelDownloadStatus.AwaitingUpdate,
                    ModelDownloadStatus.CheckFailed,
                    ModelDownloadStatus.NoNetwork,
                    ModelDownloadStatus.ServerUnavailable,
                    ModelDownloadStatus.OutOfStorage,
                    -> Icons.Rounded.HourglassEmpty
                    ModelDownloadStatus.Completed -> Icons.Rounded.CheckCircle
                    ModelDownloadStatus.ManagedBySystem -> Icons.Rounded.Phonelink
                    ModelDownloadStatus.NotSupported -> Icons.Rounded.Info
                    is ModelDownloadStatus.Failure -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.Download
                },
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            audioModelStatusText(status),
            style =
                when {
                    prominent && status == ModelDownloadStatus.Idle -> MaterialTheme.typography.bodyLarge
                    prominent -> MaterialTheme.typography.titleMedium
                    else -> MaterialTheme.typography.labelLarge
                },
            color = color,
            modifier = Modifier.weight(1f).then(if (prominent) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        )
    }
}
