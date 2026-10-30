package app.logdate.feature.editor.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.common.DeleteMediaButton
import app.logdate.feature.editor.ui.common.MediaOverlayCaptionArea
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.formatMediaDuration
import app.logdate.ui.platform.PlatformIcons
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.captured_photo
import logdate.client.feature.editor.generated.resources.delete_media
import logdate.client.feature.editor.generated.resources.play_video
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * Editor component for camera-captured media blocks.
 * Displays either the captured media with caption editing, or the camera capture UI.
 *
 * @param block The camera block state
 * @param onBlockUpdated Callback when the block is updated
 * @param onDeleteRequested Callback when the block should be deleted
 * @param modifier Modifier for layout customization
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun CameraBlockEditor(
    block: CameraBlockUiState,
    onBlockUpdated: (CameraBlockUiState) -> Unit,
    onDeleteRequested: () -> Unit,
    onPersistCaptureTarget: suspend (Uuid, String, CapturedMediaType) -> Boolean = { _, _, _ -> false },
    modifier: Modifier = Modifier,
) {
    val hasExistingMedia = block.uri != null

    if (hasExistingMedia) {
        CapturedMediaContent(
            block = block,
            onBlockUpdated = onBlockUpdated,
            onDiscardMedia = {
                onBlockUpdated(
                    block.copy(
                        uri = null,
                        caption = "",
                        durationMs = 0,
                    ),
                )
            },
            modifier = modifier,
        )
    } else {
        CameraCaptureContent(
            pendingFilePath = block.pendingFilePath,
            onPrepareCaptureTarget = { path, type -> onPersistCaptureTarget(block.id, path, type) },
            onMediaCaptured = { uri, mediaType, durationMs ->
                onBlockUpdated(
                    block.copy(
                        uri = uri,
                        pendingFilePath = null,
                        mediaType = mediaType,
                        durationMs = durationMs,
                    ),
                )
            },
            onClose = onDeleteRequested,
            modifier = modifier,
            initialCaptureMode = block.initialCaptureMode,
        )
    }
}

/**
 * Displays captured media (photo or video) with caption editing and delete option.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
private fun CapturedMediaContent(
    block: CameraBlockUiState,
    onBlockUpdated: (CameraBlockUiState) -> Unit,
    onDiscardMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(8.dp)
                .clip(RoundedCornerShape(16.dp)),
    ) {
        when (block.mediaType) {
            CapturedMediaType.PHOTO -> {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalPlatformContext.current)
                            .data(block.uri)
                            .build(),
                    contentDescription = block.caption.ifBlank { stringResource(Res.string.captured_photo) },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            CapturedMediaType.VIDEO -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = PlatformIcons.play(),
                            contentDescription = stringResource(Res.string.play_video),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatDuration(block.durationMs),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DeleteMediaButton(
            onClick = onDiscardMedia,
            contentDescription = stringResource(Res.string.delete_media),
            modifier = Modifier.align(Alignment.TopEnd),
        )

        MediaOverlayCaptionArea(
            caption = block.caption,
            onCaptionChanged = { onBlockUpdated(block.copy(caption = it)) },
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/**
 * Formats a duration in milliseconds to a MM:SS string.
 */
private fun formatDuration(durationMs: Long): String = formatMediaDuration(durationMs, true)

/**
 * Platform-specific camera capture content.
 * Shows an inline preview with camera controls.
 *
 * @param onMediaCaptured Callback when media is captured, providing URI, type, and duration
 * @param onClose Callback when the user closes the camera without capturing
 * @param modifier Modifier for layout customization
 * @param initialCaptureMode Whether the camera opens ready for photos or video
 */
@Suppress("ktlint:standard:function-naming")
@Composable
expect fun CameraCaptureContent(
    onMediaCaptured: (uri: String, mediaType: CapturedMediaType, durationMs: Long) -> Unit,
    onClose: () -> Unit,
    pendingFilePath: String? = null,
    onPrepareCaptureTarget: suspend (String, CapturedMediaType) -> Boolean = { _, _ -> false },
    modifier: Modifier = Modifier,
    remoteControl: CameraRemoteControl = CameraRemoteControl.None,
    initialCaptureMode: CaptureMode = CaptureMode.PHOTO,
)
