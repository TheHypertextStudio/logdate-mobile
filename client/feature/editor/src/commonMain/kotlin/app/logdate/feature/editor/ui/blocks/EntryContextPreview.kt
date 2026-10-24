package app.logdate.feature.editor.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.editor.VideoBlockUiState
import app.logdate.feature.editor.ui.formatMediaDuration
import app.logdate.feature.editor.ui.handwriting.HandwritingCanvas
import app.logdate.feature.editor.ui.image.ImageBlockPreview
import app.logdate.feature.editor.ui.layout.EditorBlockCornerRadius
import app.logdate.ui.common.MarkdownPreviewText
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.ink_preview_description
import org.jetbrains.compose.resources.stringResource

/** Passive entry context: it has no editor callbacks and cannot change capture ownership. */
@Suppress("ktlint:standard:function-naming")
@Composable
internal fun EntryContextPreview(
    blocks: List<EntryBlockUiState>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("editor_passive_context"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(8.dp),
    ) {
        items(blocks, key = { it.id }) { block ->
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("context_block_${block.id}"),
                shape = RoundedCornerShape(EditorBlockCornerRadius),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                when (block) {
                    is HandwritingBlockUiState -> {
                        HandwritingCanvas(
                            block.page,
                            Modifier.fillMaxWidth().height(240.dp),
                            description = stringResource(Res.string.ink_preview_description),
                            paperColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            adaptInkToPaper = true,
                        )
                    }

                    is TextBlockUiState -> {
                        MarkdownPreviewText(block.content, Modifier.padding(24.dp), maxLines = 8)
                    }

                    is ImageBlockUiState -> {
                        if (block.uri != null) ImageBlockPreview(block, wrapToImage = true)
                    }

                    is CameraBlockUiState -> {
                        if (block.uri != null) {
                            if (block.mediaType == CapturedMediaType.VIDEO) {
                                MarkdownPreviewText(
                                    block.caption.ifBlank { memoryBlockLabel(block) },
                                    Modifier.padding(24.dp),
                                )
                            } else {
                                ImageBlockPreview(ImageBlockUiState(id = block.id, uri = block.uri), wrapToImage = true)
                            }
                        }
                    }

                    is AudioBlockUiState -> {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(formatMediaDuration(block.duration, true), style = MaterialTheme.typography.labelMedium)
                            if (block.transcription.isNotBlank()) {
                                Text(
                                    block.transcription,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    is VideoBlockUiState -> {
                        MarkdownPreviewText(
                            block.caption.ifBlank {
                                memoryBlockLabel(block)
                            },
                            Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}
