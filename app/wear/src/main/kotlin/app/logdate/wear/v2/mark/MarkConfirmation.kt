package app.logdate.wear.v2.mark

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ConfirmationDialog
import androidx.wear.compose.material3.ConfirmationDialogContent
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.FailureConfirmationDialogContent
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.confirmationDialogCurvedText
import app.logdate.ui.glyphs.LogDateGlyph
import app.logdate.ui.glyphs.vector
import app.logdate.wear.R

/** "Moment marked" with the mark glyph after Mark, or the failure, each for the dialog's usual few seconds. */
@Composable
fun MarkConfirmation(
    feedback: MarkFeedback,
    onDismiss: () -> Unit,
) {
    val marked = stringResource(R.string.wear_mark_saved)
    val failed = stringResource(R.string.wear_mark_failed)
    val style = ConfirmationDialogDefaults.curvedTextStyle
    ConfirmationDialog(
        visible = feedback == MarkFeedback.MARKED,
        onDismissRequest = onDismiss,
        curvedText = { confirmationDialogCurvedText(marked, style) },
    ) {
        MarkGlyph()
    }
    FailureConfirmationDialog(
        visible = feedback == MarkFeedback.FAILED,
        onDismissRequest = onDismiss,
        curvedText = { confirmationDialogCurvedText(failed, style) },
    )
}

/** What [MarkConfirmation] shows, without the dialog window, for screenshots. */
@Composable
fun MarkConfirmationContent(feedback: MarkFeedback) {
    val style = ConfirmationDialogDefaults.curvedTextStyle
    when (feedback) {
        MarkFeedback.MARKED -> {
            val marked = stringResource(R.string.wear_mark_saved)
            ConfirmationDialogContent(curvedText = { confirmationDialogCurvedText(marked, style) }) { MarkGlyph() }
        }

        MarkFeedback.FAILED -> {
            val failed = stringResource(R.string.wear_mark_failed)
            FailureConfirmationDialogContent(curvedText = { confirmationDialogCurvedText(failed, style) })
        }

        MarkFeedback.NONE -> {
            Unit
        }
    }
}

@Composable
private fun MarkGlyph() {
    Icon(imageVector = LogDateGlyph.MARK.vector(), contentDescription = null, modifier = Modifier.size(MARK_GLYPH_SIZE))
}

private val MARK_GLYPH_SIZE = 32.dp
