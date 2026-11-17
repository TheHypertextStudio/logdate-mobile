@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.ui.notes.NoteMediaImage
import app.logdate.ui.notes.NoteMediaKind

@Composable
internal fun HistoryMemoryThumbnail(
    uri: String,
    video: Boolean,
    size: Dp = 64.dp,
) {
    NoteMediaImage(
        mediaRef = uri,
        kind = if (video) NoteMediaKind.Video else NoteMediaKind.Photo,
        contentDescription = null,
        modifier = Modifier.size(size).clip(MaterialTheme.shapes.small),
        compact = true,
    )
}
