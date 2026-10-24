@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.journals.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import coil3.compose.AsyncImage
import logdate.client.feature.journal.generated.resources.Res
import logdate.client.feature.journal.generated.resources.image_note
import logdate.client.feature.journal.generated.resources.video_note
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

/**
 * A visual grid of all media entries in a journal.
 * Shows photos and video thumbnails in a tight album-style layout.
 */
@Composable
fun JournalGalleryGrid(
    mediaEntries: List<EntryDisplayData>,
    onOpenEntry: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 120.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.xs),
        horizontalArrangement =
            Arrangement.spacedBy(2.dp),
        verticalArrangement =
            Arrangement.spacedBy(2.dp),
    ) {
        items(
            items = mediaEntries,
            key = { it.id },
        ) { entry ->
            when (entry) {
                is EntryDisplayData.ImageEntry -> {
                    ImageThumbnail(
                        mediaRef = entry.mediaRef,
                        onClick = { onOpenEntry(entry.id) },
                    )
                }

                is EntryDisplayData.VideoEntry -> {
                    VideoThumbnail(
                        mediaRef = entry.mediaRef,
                        onClick = { onOpenEntry(entry.id) },
                    )
                }

                else -> {
                    // Non-media entries don't appear in the gallery
                }
            }
        }
    }
}

@Composable
private fun ImageThumbnail(
    mediaRef: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = mediaRef,
            contentDescription = stringResource(Res.string.image_note),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun VideoThumbnail(
    mediaRef: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = mediaRef,
            contentDescription = stringResource(Res.string.video_note),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(32.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        )
    }
}
