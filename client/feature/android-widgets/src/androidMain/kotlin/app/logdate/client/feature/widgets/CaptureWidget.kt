package app.logdate.client.feature.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.layout.width

/**
 * The public capture action, the same one launcher shortcuts and Quick Settings tiles send. This
 * module cannot depend on the app, so the values are repeated here; the app's tests check they match
 * `app.logdate.client.capture.ACTION_CAPTURE` and `EXTRA_CAPTURE_MODE`.
 */
const val CAPTURE_WIDGET_ACTION = "app.logdate.action.CAPTURE"
const val CAPTURE_WIDGET_EXTRA_MODE = "mode"

/** One button on the capture widget, and the capture mode it opens. */
enum class CaptureWidgetButton(
    val mode: String,
) {
    Write("text"),
    Record("voice"),
    Photo("camera"),
}

private val CaptureWidgetButton.label: Int
    get() =
        when (this) {
            CaptureWidgetButton.Write -> R.string.widget_action_write
            CaptureWidgetButton.Record -> R.string.widget_action_record
            CaptureWidgetButton.Photo -> R.string.widget_action_camera
        }

/** The same icons and colored circles as the New entry widget's buttons. */
private val CaptureWidgetButton.icon: Int
    get() =
        when (this) {
            CaptureWidgetButton.Write -> R.drawable.ic_widget_write
            CaptureWidgetButton.Record -> R.drawable.ic_widget_record
            CaptureWidgetButton.Photo -> R.drawable.ic_widget_camera
        }

private val CaptureWidgetButton.circle: Int
    get() =
        when (this) {
            CaptureWidgetButton.Write -> R.drawable.widget_action_write
            CaptureWidgetButton.Record -> R.drawable.widget_action_record
            CaptureWidgetButton.Photo -> R.drawable.widget_action_camera
        }

/**
 * The intent a capture widget button sends: the capture action, addressed to LogDate's main window.
 *
 * Glance gives each clickable intent a `glance-action:` data URI so their PendingIntents stay
 * distinct. An intent resolved by its action alone would then have to match an intent filter that
 * declares that data, and the capture filter declares none, so Android would find no activity. Naming
 * the main window directly skips filter matching, as the other widgets' launches already do.
 *
 * CaptureWidgetContent calls it inside nested Glance click lambdas, which SearchDeadCode does not follow.
 */
@Suppress(names = ["unused"])
fun captureWidgetIntent(
    context: Context,
    button: CaptureWidgetButton,
): Intent {
    val mainWindow = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
    return Intent(CAPTURE_WIDGET_ACTION)
        .setPackage(context.packageName)
        .setComponent(mainWindow)
        .putExtra(CAPTURE_WIDGET_EXTRA_MODE, button.mode)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
}

/**
 * Starts an entry from the home screen, the lock screen, or the hub: Write, Record, or Photo.
 *
 * It never shows anything from the journal, which is what makes it safe where anyone holding the
 * device can see it. Opening the editor from the lock screen still asks Android to unlock first.
 */
class CaptureWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, WIDE))
    override val previewSizeMode = SizeMode.Responsive(setOf(WIDE))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent { CaptureWidgetContent() }
    }

    override suspend fun providePreview(
        context: Context,
        widgetCategory: Int,
    ) {
        provideContent { CaptureWidgetContent() }
    }

    private companion object {
        /** A small lock screen or 2×1 slot. */
        val COMPACT = DpSize(160.dp, 60.dp)

        /** A 3×1 home screen slot. */
        val WIDE = DpSize(250.dp, 80.dp)
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun CaptureWidgetContent() {
    val context = LocalContext.current
    val size = LocalSize.current
    // Record sits in the middle and a little larger, as on the New entry widget.
    val side = if (size.height >= 72.dp && size.width >= 220.dp) 56.dp else 44.dp
    val center = side + 8.dp
    Row(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CaptureWidgetButton.entries.forEachIndexed { index, button ->
            if (index > 0) Spacer(GlanceModifier.width(10.dp))
            val diameter = if (button == CaptureWidgetButton.Record) center else side
            Box(
                modifier =
                    GlanceModifier
                        .size(diameter)
                        .background(ImageProvider(button.circle))
                        .clickable(actionStartActivity(captureWidgetIntent(context, button))),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(button.icon),
                    contentDescription = context.getString(button.label),
                    modifier = GlanceModifier.size(24.dp),
                )
            }
        }
    }
}

/**
 * Hosts [CaptureWidget]. Declared disabled in the manifest; the app enables it while the
 * LOCK_SCREEN_CAPTURE_WIDGET flag is on.
 */
class CaptureWidgetReceiver : DurableWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaptureWidget()
}
