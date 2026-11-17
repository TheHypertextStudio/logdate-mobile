package app.logdate.feature.editor.ui.image

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.common.DeleteMediaButton
import app.logdate.feature.editor.ui.common.MediaOverlayCaptionArea
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.shared.model.PhotoPresentation
import app.logdate.ui.common.MarkdownText
import app.logdate.ui.common.parseMarkdownDocument
import app.logdate.ui.notes.NoteMediaImage
import app.logdate.ui.notes.NoteMediaKind
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.delete_image
import logdate.client.ui.generated.resources.note_block_photo
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * A component that handles image display and editing within the editor.
 *
 * This component renders an image with optional caption and provides controls
 * for editing or deleting the image.
 *
 * @param block The image block state
 * @param onBlockUpdated Callback when the block is updated
 * @param onDeleteRequested Callback when the block should be deleted
 * @param modifier Modifier for layout customization
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun ImageBlockEditor(
    block: ImageBlockUiState,
    onBlockUpdated: (ImageBlockUiState) -> Unit,
    onDeleteRequested: () -> Unit,
    previewImagePainter: Painter? = null,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
) {
    val hasExistingImage = block.uri != null
    var photoRatio by remember(block.uri) { mutableFloatStateOf(4f / 3f) }
    val intrinsic = previewImagePainter?.intrinsicSize
    val painterRatio = intrinsic?.let { it.width / it.height }
    val aspectRatio = painterRatio?.takeIf { it.isFinite() && it > 0f } ?: photoRatio

    if (hasExistingImage) {
        if (isExpanded) {
            PhotoEditorLayout(
                aspectRatio = aspectRatio,
                framed = block.presentation == PhotoPresentation.Framed,
                modifier = modifier,
                preview = {
                    Box(Modifier.fillMaxSize()) {
                        ImageBlockPreview(
                            block,
                            previewImagePainter,
                            ContentScale.Fit,
                            Modifier.fillMaxSize(),
                            onAspectRatioLoaded = { photoRatio = it },
                        )
                        PhotoActionsMenu(block, onBlockUpdated, onDeleteRequested, Modifier.align(Alignment.TopEnd))
                    }
                },
                editor = { PhotoEditingControls(block, onBlockUpdated) },
            )
        } else {
            Box(modifier = modifier.fillMaxWidth()) {
                if (block.presentation == PhotoPresentation.Framed) {
                    Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp)) {
                        ImageBlockPreview(
                            block,
                            previewImagePainter,
                            ContentScale.Fit,
                            Modifier.fillMaxWidth(),
                            wrapToImage = true,
                        )
                        if (block.caption.isNotBlank()) {
                            MarkdownText(
                                block.caption,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Black),
                                modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                            )
                        }
                    }
                } else {
                    ImageBlockPreview(
                        block,
                        previewImagePainter,
                        ContentScale.Fit,
                        Modifier.fillMaxWidth(),
                        wrapToImage = true,
                    )
                    MediaOverlayCaptionArea(
                        caption = block.caption,
                        onCaptionChanged = { onBlockUpdated(block.copy(caption = it)) },
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
                DeleteMediaButton(
                    onClick = onDeleteRequested,
                    contentDescription = stringResource(Res.string.delete_image),
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    } else {
        ImagePickerContent(
            onImageSelected = { uri ->
                onBlockUpdated(block.copy(uri = uri))
            },
            modifier = modifier.fillMaxSize(),
        )
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun ImageBlockPreview(
    block: ImageBlockUiState,
    painter: Painter? = null,
    contentScale: ContentScale = ContentScale.Fit,
    modifier: Modifier = Modifier,
    wrapToImage: Boolean = false,
    onAspectRatioLoaded: (Float) -> Unit = {},
) {
    var loadedRatio by remember(block.uri) { mutableFloatStateOf(0f) }
    val fallbackDescription = stringResource(UiRes.string.note_block_photo)
    val description =
        remember(block.caption, fallbackDescription) {
            parseMarkdownDocument(block.caption).plainText.ifBlank { fallbackDescription }
        }
    val intrinsic = painter?.intrinsicSize
    val ratio = if (intrinsic != null) intrinsic.width / intrinsic.height else loadedRatio
    // Until the photo has loaded, a wrapped preview holds a 4:3 loading surface instead of
    // collapsing to nothing.
    val wrapRatio = if (ratio.isFinite() && ratio > 0f) ratio else LOADING_RATIO
    val imageModifier = if (wrapToImage) modifier.aspectRatio(wrapRatio) else modifier
    if (painter != null) {
        Image(painter, description, imageModifier, contentScale = contentScale)
    } else if (block.uri != null) {
        // A block with no photo yet (an uncaptured camera block) has nothing to preview; only a
        // chosen photo that can't be read is a failure.
        NoteMediaImage(
            mediaRef = block.uri,
            kind = NoteMediaKind.Photo,
            contentDescription = description,
            modifier = imageModifier,
            contentScale = contentScale,
            onLoaded = { loaded ->
                val size = loaded.intrinsicSize
                val loadedValue = size.width / size.height
                if (loadedValue.isFinite() && loadedValue > 0f && loadedRatio == 0f) {
                    loadedRatio = loadedValue
                    onAspectRatioLoaded(loadedValue)
                }
            },
        )
    }
}

private const val LOADING_RATIO = 4f / 3f

@Suppress("ktlint:standard:function-naming")
@Composable
expect fun ImagePickerContent(
    onImageSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
)
