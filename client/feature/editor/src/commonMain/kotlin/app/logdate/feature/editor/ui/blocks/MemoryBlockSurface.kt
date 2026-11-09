package app.logdate.feature.editor.ui.blocks

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.EditorFocusInsets
import app.logdate.feature.editor.ui.LocalBlockFocusId
import app.logdate.feature.editor.ui.LocalBlockFocusProgress
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.editorFocusPadding
import app.logdate.feature.editor.ui.handwriting.handwritingSharedElement
import app.logdate.feature.editor.ui.layout.EditorBlockCornerRadius
import app.logdate.feature.editor.ui.layout.EditorBlockMinHeight
import app.logdate.feature.editor.ui.layout.LocalEditorRecordingActive
import app.logdate.feature.editor.ui.layout.clipEntryContentToSafeWindow
import app.logdate.shared.model.PhotoPresentation
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.platform.PlatformIcons
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_page_focus_block
import logdate.client.feature.editor.generated.resources.ink_add
import logdate.client.feature.editor.generated.resources.memory_audio
import logdate.client.feature.editor.generated.resources.memory_camera
import logdate.client.feature.editor.generated.resources.memory_edit
import logdate.client.feature.editor.generated.resources.memory_photo
import logdate.client.feature.editor.generated.resources.memory_remove
import logdate.client.feature.editor.generated.resources.memory_text
import logdate.client.feature.editor.generated.resources.memory_video
import logdate.client.feature.editor.generated.resources.more_options
import logdate.client.feature.editor.generated.resources.photo_edge_to_edge
import logdate.client.feature.editor.generated.resources.photo_framed
import org.jetbrains.compose.resources.stringResource

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun MemoryBlockSurface(
    block: EntryBlockUiState,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUpdate: (EntryBlockUiState) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = onSelect,
    customPresentation: Boolean = false,
    showFocusAction: Boolean = false,
    focusTransitionProgress: Float = 0f,
    morphSurface: Boolean = false,
    expanded: Boolean = false,
    retainedSurface: Boolean = false,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val recordingActive = LocalEditorRecordingActive.current
    LaunchedEffect(recordingActive) {
        if (recordingActive) menuOpen = false
    }
    val framed = block is ImageBlockUiState && block.presentation == PhotoPresentation.Framed && !expanded
    val progress = if (LocalBlockFocusId.current == block.id) LocalBlockFocusProgress.current ?: focusTransitionProgress else 0f
    val sharedPaper = customPresentation && block is HandwritingBlockUiState && !retainedSurface
    val surfaceColor by animateColorAsState(
        if (framed) {
            Color.White
        } else if (isSelected && !expanded) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        label = "memorySurfaceColor",
    )
    val photoTopInset by animateDpAsState(if (framed) 12.dp else 0.dp, label = "photoTopInset")
    val surfaceShape = RoundedCornerShape(EditorBlockCornerRadius * (if (sharedPaper || morphSurface) 1f - progress else 1f))
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = EditorBlockMinHeight)
                .then(if (sharedPaper) Modifier.handwritingSharedElement(block.id, progress) else Modifier)
                .testTag(
                    "memory_block_${block.id}",
                ).semantics { selected = isSelected }
                .clip(surfaceShape)
                .then(if (expanded) Modifier else Modifier.clickable(onClick = onSelect)),
        shape = surfaceShape,
        color =
            if (sharedPaper) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                surfaceColor
            },
        contentColor = if (framed) Color.Black else MaterialTheme.colorScheme.onSurface,
    ) {
        Box {
            val contentInsets =
                if (customPresentation) {
                    Modifier
                } else {
                    when (block) {
                        is TextBlockUiState -> {
                            if (recordingActive) {
                                Modifier
                            } else if (retainedSurface) {
                                val reserve = with(LocalDensity.current) { (if (showFocusAction) 96.dp else 48.dp).roundToPx() }
                                Modifier.editorFocusPadding(
                                    inline =
                                        if (LocalLayoutDirection.current ==
                                            LayoutDirection.Ltr
                                        ) {
                                            EditorFocusInsets(right = reserve)
                                        } else {
                                            EditorFocusInsets(left = reserve)
                                        },
                                    expanded = expanded,
                                    progress = progress,
                                )
                            } else if (expanded) {
                                Modifier
                            } else {
                                Modifier.padding(end = if (showFocusAction) 96.dp else 48.dp)
                            }
                        }

                        is ImageBlockUiState -> {
                            Modifier.padding(top = photoTopInset)
                        }

                        else -> {
                            Modifier
                        }
                    }
                }
            Column(
                contentInsets.then(
                    if (block is TextBlockUiState || block is AudioBlockUiState) Modifier.clipEntryContentToSafeWindow() else Modifier,
                ),
            ) { content() }
            if (!recordingActive && !expanded) {
                val menuTopInset =
                    if (block is AudioBlockUiState && block.uri != null) {
                        if (isSelected) 8.dp else 4.dp
                    } else {
                        4.dp
                    }
                Row(Modifier.align(Alignment.TopEnd).padding(top = menuTopInset, end = 4.dp).clipEntryContentToSafeWindow()) {
                    if (showFocusAction) {
                        IconButton(
                            onClick = onSelect,
                            modifier = Modifier.testTag("focus_block_${block.id}"),
                        ) { Icon(Icons.Outlined.Fullscreen, stringResource(Res.string.entry_page_focus_block, memoryBlockLabel(block))) }
                    }
                    Box {
                        val overMedia = block is ImageBlockUiState || block is VideoBlockUiState || block is CameraBlockUiState
                        IconButton(
                            onClick = {
                                if (!showFocusAction) onSelect()
                                menuOpen = true
                            },
                            modifier = Modifier.testTag("block_menu_${block.id}"),
                            colors =
                                IconButtonDefaults.iconButtonColors(
                                    containerColor = if (overMedia) Color.Black.copy(alpha = 0.45f) else Color.Transparent,
                                    contentColor = if (overMedia) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                        ) {
                            Icon(PlatformIcons.more(), stringResource(Res.string.more_options))
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(Res.string.memory_edit)) }, onClick = {
                                menuOpen = false
                                onEdit()
                            })
                            if (block is ImageBlockUiState) {
                                PhotoPresentation.entries.forEach { presentation ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    if (presentation ==
                                                        PhotoPresentation.Framed
                                                    ) {
                                                        Res.string.photo_framed
                                                    } else {
                                                        Res.string.photo_edge_to_edge
                                                    },
                                                ),
                                            )
                                        },
                                        trailingIcon = { if (block.presentation == presentation) Icon(PlatformIcons.check(), null) },
                                        onClick = {
                                            onUpdate(block.copy(presentation = presentation))
                                            menuOpen = false
                                        },
                                    )
                                }
                            }
                            DropdownMenuItem(text = { Text(stringResource(Res.string.memory_remove)) }, onClick = {
                                menuOpen = false
                                onRemove()
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun memoryBlockLabel(block: EntryBlockUiState): String =
    stringResource(
        when (block) {
            is HandwritingBlockUiState -> Res.string.ink_add
            is TextBlockUiState -> Res.string.memory_text
            is ImageBlockUiState -> Res.string.memory_photo
            is AudioBlockUiState -> Res.string.memory_audio
            is VideoBlockUiState -> Res.string.memory_video
            is CameraBlockUiState -> Res.string.memory_camera
        },
    )
