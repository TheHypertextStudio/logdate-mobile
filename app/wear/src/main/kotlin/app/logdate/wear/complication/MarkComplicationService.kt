package app.logdate.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.logdate.wear.R
import app.logdate.wear.v2.mark.MarkMomentReceiver

/**
 * Marks the moment from a watch face, with a haptic and without opening the app, through
 * [MarkMomentReceiver]. Its manifest entry is disabled; `SurfaceComponents` offers it while the new
 * watch app is on.
 */
class MarkComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        markComplicationData(this, type, MarkMomentReceiver.pendingIntent(this))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
        markComplicationData(this, request.complicationType, MarkMomentReceiver.pendingIntent(this)) ?: NoDataComplicationData()
}

/** The Mark glyph for a slot of [type], firing [tapAction]; null for a type this complication does not fill. */
internal fun markComplicationData(
    context: Context,
    type: ComplicationType,
    tapAction: PendingIntent,
): ComplicationData? {
    val description = PlainComplicationText.Builder(context.getString(R.string.wear_surface_mark_description)).build()
    val glyph = Icon.createWithResource(context, R.drawable.ic_glyph_mark)
    return when (type) {
        ComplicationType.SHORT_TEXT -> {
            ShortTextComplicationData
                .Builder(
                    text = PlainComplicationText.Builder(context.getString(R.string.wear_surface_mark)).build(),
                    contentDescription = description,
                ).setMonochromaticImage(MonochromaticImage.Builder(glyph).build())
                .setTapAction(tapAction)
                .build()
        }

        ComplicationType.MONOCHROMATIC_IMAGE -> {
            MonochromaticImageComplicationData
                .Builder(monochromaticImage = MonochromaticImage.Builder(glyph).build(), contentDescription = description)
                .setTapAction(tapAction)
                .build()
        }

        ComplicationType.SMALL_IMAGE -> {
            SmallImageComplicationData
                .Builder(smallImage = SmallImage.Builder(glyph, SmallImageType.ICON).build(), contentDescription = description)
                .setTapAction(tapAction)
                .build()
        }

        else -> {
            null
        }
    }
}
