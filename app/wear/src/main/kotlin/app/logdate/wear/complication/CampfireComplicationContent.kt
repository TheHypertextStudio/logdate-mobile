package app.logdate.wear.complication

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import app.logdate.client.domain.streak.CampfireState
import app.logdate.client.domain.streak.FirePhase
import app.logdate.wear.R

/** A ranged slot's bounds: no fire at the bottom, a burning fire at the top. */
internal const val CAMPFIRE_RANGE_MIN = 0f
internal const val CAMPFIRE_RANGE_MAX = 2f

/**
 * A ranged slot's colors, one per step from [CAMPFIRE_RANGE_MIN] to [CAMPFIRE_RANGE_MAX]: the charred
 * logs, outer flame and inner flame of the app's campfire (`app.logdate.ui.streak.Campfire`).
 */
internal val CAMPFIRE_COLOR_RAMP: IntArray = intArrayOf(0xFFA8A29C.toInt(), 0xFFFF8A3D.toInt(), 0xFFFFD166.toInt())

private const val EMBERS_VALUE = 1f
private const val NO_FIRE_TEXT = "–"

/**
 * What the streak complication shows for a campfire: an icon for the fire's phase and the day
 * count of the current fire, or no count when there is no fire.
 */
internal data class CampfireComplicationContent(
    @param:DrawableRes val iconRes: Int,
    val runDays: Int?,
    val phase: FirePhase,
) {
    /** Where the fire sits in a ranged slot; the slot's color steps with it. */
    val rangedValue: Float
        get() =
            when (phase) {
                FirePhase.BURNING -> CAMPFIRE_RANGE_MAX
                FirePhase.EMBERS -> EMBERS_VALUE
                FirePhase.UNLIT, FirePhase.OUT -> CAMPFIRE_RANGE_MIN
            }
}

internal fun CampfireState.toComplicationContent(): CampfireComplicationContent =
    CampfireComplicationContent(
        iconRes =
            when (phase) {
                FirePhase.BURNING -> R.drawable.ic_campfire_flame
                FirePhase.EMBERS -> R.drawable.ic_campfire_embers
                FirePhase.UNLIT, FirePhase.OUT -> R.drawable.ic_campfire_logs
            },
        runDays = runDays.takeIf { phase == FirePhase.BURNING || phase == FirePhase.EMBERS },
        phase = phase,
    )

/** The campfire for a slot of [type]: a ranged value, long text, or short text for any other type. */
internal fun campfireComplicationData(
    context: Context,
    content: CampfireComplicationContent,
    type: ComplicationType,
    tapAction: PendingIntent,
): ComplicationData {
    val description = PlainComplicationText.Builder(campfireDescription(context, content)).build()
    val count = PlainComplicationText.Builder(content.runDays?.toString() ?: NO_FIRE_TEXT).build()
    val icon = MonochromaticImage.Builder(Icon.createWithResource(context, content.iconRes)).build()
    return when (type) {
        ComplicationType.RANGED_VALUE -> {
            RangedValueComplicationData
                .Builder(
                    value = content.rangedValue,
                    min = CAMPFIRE_RANGE_MIN,
                    max = CAMPFIRE_RANGE_MAX,
                    contentDescription = description,
                ).setText(count)
                .setMonochromaticImage(icon)
                .setColorRamp(ColorRamp(colors = CAMPFIRE_COLOR_RAMP, interpolated = false))
                .setTapAction(tapAction)
                .build()
        }

        ComplicationType.LONG_TEXT -> {
            LongTextComplicationData
                .Builder(text = description, contentDescription = description)
                .setMonochromaticImage(icon)
                .setTapAction(tapAction)
                .build()
        }

        else -> {
            ShortTextComplicationData
                .Builder(text = count, contentDescription = description)
                .setMonochromaticImage(icon)
                .setTapAction(tapAction)
                .build()
        }
    }
}

private fun campfireDescription(
    context: Context,
    content: CampfireComplicationContent,
): String {
    val runDays = content.runDays ?: 0
    return when (content.phase) {
        FirePhase.BURNING -> context.resources.getQuantityString(R.plurals.wear_complication_campfire_burning, runDays, runDays)
        FirePhase.EMBERS -> context.getString(R.string.wear_complication_campfire_embers, runDays)
        FirePhase.OUT -> context.getString(R.string.wear_complication_campfire_out)
        FirePhase.UNLIT -> context.getString(R.string.wear_complication_campfire_unlit)
    }
}
