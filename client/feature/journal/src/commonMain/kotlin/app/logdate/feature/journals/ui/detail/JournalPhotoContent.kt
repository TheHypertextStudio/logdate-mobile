package app.logdate.feature.journals.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.logdate.shared.model.PhotoPresentation
import app.logdate.ui.notes.NoteMediaImage
import app.logdate.ui.notes.NoteMediaKind
import app.logdate.ui.notes.ProportionalNoteMedia

@Suppress("ktlint:standard:function-naming")
@Composable
internal fun JournalPhotoContent(entry: EntryDisplayData.ImageEntry) {
    if (entry.presentation == PhotoPresentation.Framed) {
        Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp)) {
            ProportionalNoteMedia(
                mediaRef = entry.mediaRef,
                kind = NoteMediaKind.Photo,
                contentDescription = entry.caption,
                maxHeight = 420.dp,
                contentScale = ContentScale.Fit,
            )
            if (entry.caption.isNotBlank()) {
                Text(
                    entry.caption,
                    color = Color.Black,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                )
            }
        }
    } else {
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
            NoteMediaImage(
                mediaRef = entry.mediaRef,
                kind = NoteMediaKind.Photo,
                contentDescription = entry.caption,
                modifier = Modifier.fillMaxSize(),
            )
            if (entry.caption.isNotBlank()) {
                Text(
                    entry.caption,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                            .padding(16.dp),
                )
            }
        }
    }
}
