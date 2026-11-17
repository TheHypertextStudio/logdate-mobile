package app.logdate.wear.v2.moments

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.handwriting.HandwritingPreview

/** Test tags for [MomentGlance]. */
object MomentGlanceTags {
    const val INK = "moment_glance_ink"
}

/**
 * A moment at a glance on the watch: a handwritten moment shows its ink, which the watch holds;
 * any other moment shows its words, or its kind and length when it has none (the watch doesn't
 * hold photos or videos, only their notes).
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun MomentGlance(
    note: JournalNote,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    inkHeight: Dp = 48.dp,
) {
    if (note is JournalNote.Handwriting && note.unreadableContent == null) {
        HandwritingPreview(
            note.document,
            modifier.fillMaxWidth().height(inkHeight).clip(MaterialTheme.shapes.small).testTag(MomentGlanceTags.INK),
        )
        return
    }
    Text(
        text = momentLabel(note),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}
