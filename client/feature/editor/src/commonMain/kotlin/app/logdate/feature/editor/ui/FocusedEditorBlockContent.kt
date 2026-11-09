@file:OptIn(ExperimentalSharedTransitionApi::class, ExperimentalAnimationApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.audio.AudioBlockEditor
import app.logdate.feature.editor.ui.blocks.EntryContextPreview
import app.logdate.feature.editor.ui.camera.CameraBlockEditor
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.editor.delegate.PendingAudioResolver
import app.logdate.feature.editor.ui.handwriting.HandwritingCanvas
import app.logdate.feature.editor.ui.image.ImageBlockPreview
import app.logdate.feature.editor.ui.layout.EditorBlockCornerRadius
import app.logdate.feature.editor.ui.layout.FocusedBlockChromePolicy
import app.logdate.feature.editor.ui.layout.FocusedBlockLayout
import app.logdate.feature.editor.ui.layout.LocalEntryChromeHeight
import app.logdate.feature.editor.ui.layout.LocalEntryCreationReserve
import app.logdate.feature.editor.ui.layout.entryContentPadding
import app.logdate.feature.editor.ui.state.BlocksUiState
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.feature.editor.ui.video.VideoBlockEditor
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.ink_preview_description
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
internal fun FocusedEditorBlockContent(
    liveBlock: EntryBlockUiState,
    uiState: BlocksUiState,
    focusProgress: Float,
    floatingChrome: Boolean,
    sts: SharedTransitionScope,
    avs: AnimatedVisibilityScope,
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit,
) {
    with(sts) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("focused_block_${liveBlock.id}")
                    .then(
                        if (liveBlock.id in uiState.blockPresentations) {
                            Modifier
                        } else if (floatingChrome) {
                            Modifier.editorBlockFocusElement(
                                sts,
                                avs,
                                liveBlock.id,
                                EditorBlockCornerRadius,
                                focusProgress,
                            )
                        } else {
                            Modifier.sharedBounds(
                                rememberSharedContentState("block_surface_${liveBlock.id}"),
                                animatedVisibilityScope = avs,
                            )
                        },
                    ).then(
                        if ((floatingChrome && liveBlock.id !in uiState.blockPresentations) ||
                            liveBlock is TextBlockUiState
                        ) {
                            Modifier
                                .clip(
                                    RoundedCornerShape(
                                        if (floatingChrome) {
                                            EditorBlockCornerRadius *
                                                (1f - focusProgress)
                                        } else {
                                            0.dp
                                        },
                                    ),
                                ).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        } else {
                            Modifier
                        },
                    ),
        ) {
            val editBlock: @Composable () -> Unit = {
                val custom = uiState.blockPresentations[liveBlock.id]
                if (custom != null && floatingChrome) {
                    FocusedBlockLayout(
                        textEditor = true,
                        contentPadding = 0.dp,
                        preview =
                            if (rememberFoldableLayoutInfo().hinge?.isSeparating == true) {
                                (
                                    {
                                        EntryContextPreview(
                                            uiState.blocks.filter { it.id != liveBlock.id },
                                            Modifier.padding(top = 80.dp).graphicsLayer {
                                                alpha =
                                                    focusProgress
                                            },
                                        )
                                    }
                                )
                            } else {
                                null
                            },
                        editor = custom.focused,
                    )
                } else if (custom != null) {
                    custom.focused()
                } else {
                    BlockContentInner(
                        block = liveBlock,
                        isExpanded = true,
                        onBlockFocused = uiState.onBlockFocused,
                        onBlockUpdated = uiState.onUpdateBlock,
                        onBlockDeleted = uiState.onDeleteBlock,
                        onAudioResolverReady = onAudioResolverReady,
                        onPersistAudioRecordingTarget = uiState.onPersistAudioRecordingTarget,
                        onPersistCameraCaptureTarget = uiState.onPersistCameraCaptureTarget,
                        modifier =
                            Modifier.fillMaxSize().then(
                                if (floatingChrome && !(liveBlock is CameraBlockUiState && liveBlock.uri == null)) {
                                    val clearance =
                                        FocusedBlockChromePolicy.clearanceFor(
                                            block = liveBlock,
                                            chromeHeight = LocalEntryChromeHeight.current,
                                            dockReserve = LocalEntryCreationReserve.current,
                                        )
                                    Modifier.entryContentPadding(
                                        top = clearance.top,
                                        bottom = clearance.bottom,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
            if (liveBlock is TextBlockUiState && liveBlock.id !in uiState.blockPresentations) {
                val photo = uiState.blocks.filterIsInstance<ImageBlockUiState>().firstOrNull { it.uri != null }
                val showEntryContext = floatingChrome && rememberFoldableLayoutInfo().hinge?.isSeparating == true
                FocusedBlockLayout(
                    textEditor = true,
                    contentPadding = if (floatingChrome) 0.dp else 16.dp,
                    preview =
                        if (photo == null) {
                            if (showEntryContext) {
                                {
                                    EntryContextPreview(
                                        uiState.blocks.filter { it.id != liveBlock.id },
                                        Modifier.padding(top = 80.dp),
                                    )
                                }
                            } else {
                                null
                            }
                        } else {
                            { ImageBlockPreview(photo, modifier = Modifier.fillMaxSize()) }
                        },
                    editor = editBlock,
                )
            } else {
                editBlock()
            }
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun BlockContentInner(
    block: EntryBlockUiState,
    isExpanded: Boolean,
    onBlockFocused: (Uuid) -> Unit,
    onBlockUpdated: (EntryBlockUiState) -> Unit,
    onBlockDeleted: (Uuid) -> Unit,
    onAudioResolverReady: (Uuid, PendingAudioResolver) -> Unit = { _, _ -> },
    onPersistAudioRecordingTarget: suspend (Uuid, String, String?) -> Boolean = { _, _, _ -> true },
    onPersistCameraCaptureTarget: suspend (Uuid, String, CapturedMediaType) -> Boolean = { _, _, _ -> false },
    modifier: Modifier = Modifier,
) {
    when (block) {
        is HandwritingBlockUiState -> {
            HandwritingCanvas(
                block.page,
                Modifier.fillMaxWidth().height(240.dp),
                description = stringResource(Res.string.ink_preview_description),
            )
        }

        is TextBlockUiState -> {
            TextBlockContent(
                block = block,
                isExpanded = isExpanded,
                onTextChanged = { newText -> onBlockUpdated(block.copy(content = newText)) },
                onFocused = { onBlockFocused(block.id) },
                modifier = modifier,
            )
        }

        is ImageBlockUiState -> {
            app.logdate.feature.editor.ui.image.ImageBlockEditor(
                block = block,
                onBlockUpdated = onBlockUpdated,
                onDeleteRequested = { onBlockDeleted(block.id) },
                isExpanded = isExpanded,
                modifier = modifier,
            )
        }

        is AudioBlockUiState -> {
            AudioBlockEditor(
                block = block,
                onBlockUpdated = onBlockUpdated,
                onDeleteRequested = { onBlockDeleted(block.id) },
                onResolverReady = onAudioResolverReady,
                onPersistRecordingTarget = onPersistAudioRecordingTarget,
                modifier = modifier,
            )
        }

        is CameraBlockUiState -> {
            CameraBlockEditor(
                block = block,
                onBlockUpdated = onBlockUpdated,
                onDeleteRequested = { onBlockDeleted(block.id) },
                onPersistCaptureTarget = onPersistCameraCaptureTarget,
                modifier = modifier,
            )
        }

        is VideoBlockUiState -> {
            VideoBlockEditor(
                block = block,
                onBlockUpdated = onBlockUpdated,
                onDeleteRequested = { onBlockDeleted(block.id) },
                modifier = modifier,
            )
        }
    }
}
