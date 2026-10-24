@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.timeline.ui.marks

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.moments.MarkPhotoSuggestions
import app.logdate.client.repository.moments.MarkedMoment
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.text.TextBlockContent
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.theme.Spacing
import app.logdate.ui.timeline.TimelineMediaItemUiState
import app.logdate.ui.timeline.newstuff.TimelineMediaTile
import logdate.client.feature.timeline.generated.resources.Res
import logdate.client.feature.timeline.generated.resources.mark_add_note
import logdate.client.feature.timeline.generated.resources.mark_attach
import logdate.client.feature.timeline.generated.resources.mark_delete
import logdate.client.feature.timeline.generated.resources.mark_edit_caption
import logdate.client.feature.timeline.generated.resources.mark_leave
import logdate.client.feature.timeline.generated.resources.mark_more_actions
import logdate.client.feature.timeline.generated.resources.mark_photo_attach
import logdate.client.feature.timeline.generated.resources.mark_photo_limited
import logdate.client.feature.timeline.generated.resources.mark_photo_suggestions
import logdate.client.feature.timeline.generated.resources.mark_save_caption
import logdate.client.feature.timeline.generated.resources.mark_story_hint
import logdate.client.feature.timeline.generated.resources.mark_story_prompt
import logdate.client.feature.timeline.generated.resources.mark_watch_label
import logdate.client.feature.timeline.generated.resources.mark_write_more
import org.jetbrains.compose.resources.stringResource

@Composable
fun MarkedMomentContent(
    mark: MarkedMoment,
    placeName: String?,
    caption: String,
    onCaption: (String) -> Unit,
    photos: MarkPhotoSuggestions,
    busy: Boolean,
    error: String?,
    onRequestAccess: () -> Unit,
    onAttach: (String) -> Unit,
    onSaveCaption: () -> Unit,
    onWriteMore: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
    previewPhotoPainter: Painter? = null,
) {
    var editingCaption by rememberSaveable(mark.id.toString()) { mutableStateOf(false) }
    val needsStory = caption.isBlank()
    val contentColor =
        if (needsStory) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
    Column(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Surface(
            color = if (needsStory) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 28.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            ) {
                Text(
                    text = listOfNotNull(stringResource(Res.string.mark_watch_label), placeName).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                )
                if (needsStory && !editingCaption) {
                    Text(
                        text = stringResource(Res.string.mark_story_prompt),
                        style = MaterialTheme.typography.titleLarge,
                        color = contentColor,
                    )
                    Text(
                        text = stringResource(Res.string.mark_story_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )
                }
                if (editingCaption) {
                    TextBlockContent(
                        block =
                            TextBlockUiState(
                                id = mark.id,
                                timestamp = mark.capturedAt,
                                timeZoneId = mark.timeZoneId,
                                content = caption,
                            ),
                        isExpanded = false,
                        onTextChanged = onCaption,
                        onFocused = {},
                        readOnly = busy,
                        requestEditingFocus = true,
                        minEditorHeight = 48.dp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (caption.isNotBlank()) {
                    Text(caption, style = MaterialTheme.typography.bodyLarge, color = contentColor)
                }
                MarkPhotoSuggestionsContent(photos, busy, onRequestAccess, onAttach, previewPhotoPainter)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = { if (editingCaption) onSaveCaption() else editingCaption = true },
                enabled = !busy && (!editingCaption || caption.isNotBlank()),
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 24.dp, bottomEnd = 8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            editingCaption -> Res.string.mark_save_caption
                            needsStory -> Res.string.mark_add_note
                            else -> Res.string.mark_edit_caption
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MarkActions(busy, onWriteMore, onLeave, onDelete)
        }
    }
}

@Composable
private fun MarkActions(
    busy: Boolean,
    onWriteMore: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            enabled = !busy,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 8.dp, bottomEnd = 24.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Text(stringResource(Res.string.mark_more_actions), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.mark_write_more)) },
                onClick = {
                    expanded = false
                    onWriteMore()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.mark_leave)) },
                onClick = {
                    expanded = false
                    onLeave()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.mark_delete)) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun MarkPhotoSuggestionsContent(
    state: MarkPhotoSuggestions,
    busy: Boolean,
    onRequestAccess: () -> Unit,
    onAttach: (String) -> Unit,
    previewPhotoPainter: Painter?,
) {
    when (state) {
        MarkPhotoSuggestions.Loading, MarkPhotoSuggestions.None, MarkPhotoSuggestions.NeedsAccess -> {
            Unit
        }

        is MarkPhotoSuggestions.Limited, is MarkPhotoSuggestions.Photos -> {
            val photos =
                when (state) {
                    is MarkPhotoSuggestions.Limited -> state.photos
                    is MarkPhotoSuggestions.Photos -> state.photos
                }
            if (photos.isNotEmpty()) {
                Text(stringResource(Res.string.mark_photo_suggestions), style = MaterialTheme.typography.labelLarge)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.semantics(mergeDescendants = true) { isTraversalGroup = true },
                ) {
                    items(photos, key = { it.uri }) { photo ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TimelineMediaTile(
                                TimelineMediaItemUiState(photo.uri, false),
                                aspectRatio = 1f,
                                modifier = Modifier.width(88.dp),
                                previewPainter = previewPhotoPainter,
                            )
                            val attachDescription = stringResource(Res.string.mark_photo_attach, photo.name)
                            TextButton(
                                onClick = { onAttach(photo.uri) },
                                enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = attachDescription },
                            ) {
                                Text(stringResource(Res.string.mark_attach))
                            }
                        }
                    }
                }
            }
            if (state is MarkPhotoSuggestions.Limited) {
                TextButton(onClick = onRequestAccess) { Text(stringResource(Res.string.mark_photo_limited)) }
            }
        }
    }
}
