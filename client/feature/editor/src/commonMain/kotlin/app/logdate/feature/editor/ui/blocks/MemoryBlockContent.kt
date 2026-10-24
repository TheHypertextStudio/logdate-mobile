package app.logdate.feature.editor.ui.blocks

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.audio.AudioBlockEditor
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.MediaBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioResolver
import app.logdate.feature.editor.ui.handwriting.HandwritingCanvas
import app.logdate.feature.editor.ui.image.ImageBlockPreview
import app.logdate.feature.editor.ui.image.ImagePickerContent
import app.logdate.feature.editor.ui.layout.clipEntryContentToSafeWindow
import app.logdate.feature.editor.ui.state.EditorBlockPresentation
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.feature.editor.ui.text.rememberInlineMarkdownVisualTransformation
import app.logdate.feature.editor.ui.video.VideoPickerContent
import app.logdate.feature.editor.ui.video.VideoPlayerContent
import app.logdate.shared.model.PhotoPresentation
import app.logdate.ui.common.MarkdownText
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.add_a_caption
import logdate.client.feature.editor.generated.resources.ink_preview_description
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MemoryBlockContent(
    block: EntryBlockUiState,
    requestTextFocus: Boolean,
    isSelected: Boolean,
    editRequest: Int,
    onSelect: () -> Unit,
    onUpdate: (EntryBlockUiState) -> Unit,
    onRemove: () -> Unit,
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit,
    onPersistAudioRecordingTarget: suspend (Uuid, String, String?) -> Boolean = { _, _, _ -> true },
    onPhotoAspectRatioLoaded: (Uuid, Float) -> Unit = { _, _ -> },
    focusedTextHeight: Dp = 0.dp,
    unfinishedRecordingHeight: Dp = 420.dp,
    availableAudioHeight: Dp = Dp.Infinity,
    presentation: EditorBlockPresentation? = null,
) {
    if (presentation != null) {
        presentation.inline(onSelect)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        when (block) {
            is HandwritingBlockUiState -> {
                HandwritingCanvas(
                    block.page,
                    Modifier.fillMaxWidth().height(240.dp).clickable(onClick = onSelect),
                    description = stringResource(Res.string.ink_preview_description),
                )
            }

            is TextBlockUiState -> {
                TextBlockContent(
                    block,
                    isExpanded = false,
                    requestEditingFocus = requestTextFocus || editRequest > 0,
                    focusRequestKey = editRequest,
                    minEditorHeight = (if (isSelected) focusedTextHeight else 0.dp).coerceAtLeast(240.dp),
                    onTextChanged = { onUpdate(block.copy(content = it)) },
                    onFocused = onSelect,
                )
            }

            is ImageBlockUiState -> {
                if (block.uri == null) {
                    ImagePickerContent({ onUpdate(block.copy(uri = it)) }, Modifier.fillMaxWidth().height(220.dp))
                } else {
                    val framed = block.presentation == PhotoPresentation.Framed
                    val inset by animateDpAsState(if (framed) 12.dp else 0.dp, label = "photoFrameInset")
                    Box(Modifier.fillMaxWidth()) {
                        ImageBlockPreview(
                            block,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = inset),
                            wrapToImage = true,
                            onAspectRatioLoaded = { onPhotoAspectRatioLoaded(block.id, it) },
                        )
                        if (!framed && (block.caption.isNotBlank() || isSelected || editRequest > 0)) {
                            MemoryCaptionField(
                                block,
                                onSelect,
                                onUpdate,
                                editRequest,
                                modifier = Modifier.align(Alignment.BottomStart),
                                overlay = true,
                            )
                        }
                    }
                }
            }

            is VideoBlockUiState -> {
                if (block.uri == null) {
                    VideoPickerContent(
                        { uri, duration -> onUpdate(block.copy(uri = uri, durationMs = duration)) },
                        Modifier.fillMaxWidth().height(220.dp),
                    )
                } else {
                    VideoPlayerContent(block.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                }
            }

            is AudioBlockUiState -> {
                AudioBlockEditor(
                    block,
                    onUpdate,
                    onRemove,
                    onAudioResolverReady,
                    onPersistRecordingTarget = onPersistAudioRecordingTarget,
                    modifier =
                        Modifier.fillMaxWidth().then(
                            if (block.uri == null) {
                                Modifier.height(unfinishedRecordingHeight)
                            } else {
                                Modifier
                            },
                        ),
                    inline = true,
                    selected = isSelected,
                    availableHeight = availableAudioHeight,
                )
            }

            is CameraBlockUiState -> {
                if (block.mediaType == CapturedMediaType.VIDEO && block.uri != null) {
                    VideoPlayerContent(block.uri, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                } else {
                    Box(Modifier.fillMaxWidth()) {
                        ImageBlockPreview(
                            ImageBlockUiState(id = block.id, uri = block.uri, caption = block.caption),
                            modifier = Modifier.fillMaxWidth(),
                            wrapToImage = true,
                            onAspectRatioLoaded = { onPhotoAspectRatioLoaded(block.id, it) },
                        )
                        if (block.caption.isNotBlank() || isSelected || editRequest > 0) {
                            MemoryCaptionField(
                                block,
                                onSelect,
                                onUpdate,
                                editRequest,
                                modifier = Modifier.align(Alignment.BottomStart),
                                overlay = true,
                            )
                        }
                    }
                }
            }
        }
        if (block is MediaBlockUiState &&
            (block !is ImageBlockUiState || block.uri == null || block.presentation == PhotoPresentation.Framed) &&
            (block !is CameraBlockUiState || block.mediaType == CapturedMediaType.VIDEO)
        ) {
            MemoryCaptionField(block, onSelect, onUpdate, editRequest)
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MemoryCaptionField(
    block: MediaBlockUiState,
    onSelect: () -> Unit,
    onUpdate: (EntryBlockUiState) -> Unit,
    editRequest: Int,
    modifier: Modifier = Modifier,
    overlay: Boolean = false,
) {
    if (block is AudioBlockUiState) {
        if (block.uri != null && block.caption.isNotBlank()) {
            MarkdownText(
                content = block.caption,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = modifier.clipEntryContentToSafeWindow().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        return
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(editRequest) { if (editRequest > 0) focusRequester.requestFocus() }
    val color =
        if (overlay) {
            Color.White
        } else if (block is ImageBlockUiState &&
            block.presentation == PhotoPresentation.Framed
        ) {
            Color.Black
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    BasicTextField(
        value = block.caption,
        visualTransformation = rememberInlineMarkdownVisualTransformation(),
        onValueChange = { caption ->
            onUpdate(
                when (block) {
                    is ImageBlockUiState -> block.copy(caption = caption)
                    is AudioBlockUiState -> block.copy(caption = caption)
                    is VideoBlockUiState -> block.copy(caption = caption)
                    is CameraBlockUiState -> block.copy(caption = caption)
                },
            )
        },
        modifier =
            modifier
                .clipEntryContentToSafeWindow()
                .fillMaxWidth()
                .testTag("memory_caption_${block.id}")
                .focusRequester(focusRequester)
                .onFocusChanged { if (it.isFocused) onSelect() }
                .then(
                    if (overlay) {
                        Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    } else {
                        Modifier
                    },
                ).padding(16.dp)
                .heightIn(min = 32.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = color),
        cursorBrush = SolidColor(if (overlay) Color.White else MaterialTheme.colorScheme.primary),
        decorationBox = { input ->
            Box {
                if (block.caption.isEmpty()) Text(stringResource(Res.string.add_a_caption), color = color.copy(alpha = 0.6f))
                input()
            }
        },
    )
}
