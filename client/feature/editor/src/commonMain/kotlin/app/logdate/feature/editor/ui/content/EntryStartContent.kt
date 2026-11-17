@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.datastore.EntryStartMode
import app.logdate.client.repository.journals.JournalNote
import app.logdate.feature.editor.ui.LocalAnimatedVisibilityScope
import app.logdate.feature.editor.ui.LocalSharedTransitionScope
import app.logdate.feature.editor.ui.audio.AudioPlaybackComponent
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.BlockType
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.editor.HandwritingBlockUiState
import app.logdate.feature.editor.ui.editor.ImageBlockUiState
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.feature.editor.ui.layout.EditorBlockMinHeight
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.entry_start_earlier_today
import logdate.client.feature.editor.generated.resources.ink_title
import logdate.client.feature.editor.generated.resources.memory_photo
import logdate.client.feature.editor.generated.resources.memory_video
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/** The ids the start content gives the block each of its choices creates, so a choice can grow into its block. */
internal data class EmptyEditorPickerTileIds(
    val textId: Uuid? = null,
    val audioId: Uuid? = null,
    val cameraId: Uuid? = null,
    val photoId: Uuid? = null,
    val handwritingId: Uuid? = null,
)

internal fun matchingPickerTileIdsFor(block: EntryBlockUiState?): EmptyEditorPickerTileIds =
    when (block) {
        is TextBlockUiState -> EmptyEditorPickerTileIds(textId = block.id)
        is AudioBlockUiState -> EmptyEditorPickerTileIds(audioId = block.id)
        is CameraBlockUiState -> EmptyEditorPickerTileIds(cameraId = block.id)
        is ImageBlockUiState -> EmptyEditorPickerTileIds(photoId = block.id)
        is HandwritingBlockUiState -> EmptyEditorPickerTileIds(handwritingId = block.id)
        else -> EmptyEditorPickerTileIds()
    }

@Composable
internal fun EntryStartContent(
    notes: List<JournalNote>,
    onCreateBlock: (BlockType, Uuid) -> Unit,
    onOpenEntry: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
    tileIds: EmptyEditorPickerTileIds = EmptyEditorPickerTileIds(),
    handwritingEnabled: Boolean = true,
    defaultMode: EntryStartMode = EntryStartMode.TEXT,
) {
    val textId = remember(tileIds.textId) { tileIds.textId ?: Uuid.random() }
    val inkId = remember(tileIds.handwritingId) { tileIds.handwritingId ?: Uuid.random() }
    val cameraId = remember(tileIds.cameraId) { tileIds.cameraId ?: Uuid.random() }
    val audioId = remember(tileIds.audioId) { tileIds.audioId ?: Uuid.random() }
    val photoId = remember(tileIds.photoId) { tileIds.photoId ?: Uuid.random() }
    val ids =
        mapOf(
            EntryStartMode.TEXT to textId,
            EntryStartMode.HANDWRITING to inkId,
            EntryStartMode.CAMERA to cameraId,
            EntryStartMode.AUDIO to audioId,
            EntryStartMode.GALLERY to photoId,
        )
    val writing: @Composable (Boolean) -> Unit = { compact ->
        EntryStartActions(defaultMode, ids, onCreateBlock, handwritingEnabled, compact)
    }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val compact = maxHeight < 360.dp
        if (maxWidth >= 840.dp && notes.isNotEmpty()) {
            Row(
                Modifier.widthIn(max = 1120.dp).fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Box(Modifier.weight(1f)) { writing(compact) }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { EarlierTodayHeading() }
                    items(notes, key = { it.uid }) { EarlierNote(it, onOpenEntry) }
                }
            }
        } else {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { writing(compact) }
                if (notes.isNotEmpty()) {
                    item { EarlierTodayHeading() }
                    items(notes, key = { it.uid }) { EarlierNote(it, onOpenEntry) }
                }
            }
        }
    }
}

@Composable
private fun EarlierTodayHeading() {
    Text(stringResource(Res.string.entry_start_earlier_today), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun EarlierNote(
    note: JournalNote,
    onOpen: (Uuid) -> Unit,
) {
    if (note is JournalNote.Audio) {
        Surface(
            onClick = { onOpen(note.uid) },
            modifier = Modifier.fillMaxWidth().heightIn(min = EditorBlockMinHeight),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(16.dp)) {
                AudioPlaybackComponent(note.mediaRef, knownDurationMs = note.durationMs, contentPadding = 0.dp)
                if (note.caption.isNotBlank()) Text(note.caption)
            }
        }
        return
    }
    val preview =
        when (note) {
            is JournalNote.Text -> note.content
            is JournalNote.Handwriting -> stringResource(Res.string.ink_title)
            is JournalNote.Image -> note.caption.ifBlank { stringResource(Res.string.memory_photo) }
            is JournalNote.Video -> note.caption.ifBlank { stringResource(Res.string.memory_video) }
            is JournalNote.Audio -> error("Audio has its own presentation")
        }
    Surface(
        onClick = { onOpen(note.uid) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(preview, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Modifier.startBlockBounds(id: Uuid): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalAnimatedVisibilityScope.current ?: return this
    return with(shared) {
        this@startBlockBounds.sharedBounds(rememberSharedContentState("block_surface_$id"), visibility)
    }
}
