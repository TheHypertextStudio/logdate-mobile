package app.logdate.client.capture

import android.content.Context
import android.content.Intent

/**
 * LogDate's own route for starting an entry with text the person already wrote in LogDate, such as a
 * notification reply that could not be saved. It is a non-exported alias of `MainActivity`, so no
 * other app can reach it, and `MainActivity` keeps starting text only when a launch came through it.
 */
const val INTERNAL_CAPTURE_ACTIVITY = "app.logdate.client.InternalCaptureActivity"

/** Opens a new text entry starting with [text], which the person wrote, through [INTERNAL_CAPTURE_ACTIVITY]. */
fun Context.internalCaptureIntent(text: String): Intent =
    Intent(ACTION_CAPTURE)
        .setClassName(this, INTERNAL_CAPTURE_ACTIVITY)
        .putExtra(EXTRA_CAPTURE_MODE, "text")
        .putExtra(EXTRA_CAPTURE_TEXT, text)

/** The starting text of a capture [intent], kept only when it came through LogDate's own route. */
internal fun internalCaptureText(intent: Intent): String? =
    intent.getStringExtra(EXTRA_CAPTURE_TEXT).takeIf { intent.component?.className == INTERNAL_CAPTURE_ACTIVITY }
