package app.logdate.feature.location.timeline.ui.history

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.runtime.Immutable
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlin.math.roundToInt

internal enum class MarkerVisualKind {
    Confirmed,
    Approximate,
    Cluster,
    ReplayPuck,
}

@Immutable
internal data class MarkerBitmapKey(
    val kind: MarkerVisualKind,
    val ordinal: Int,
    val category: String?,
    val selected: Boolean,
    val memoryCount: Int,
    val clusterCount: Int,
    val darkTheme: Boolean,
    val primaryArgb: Int,
    val bearingBucket: Int = 0,
    val densityBucket: Int,
)

internal object HistoryMarkerBitmapCache {
    private val cache = LruCache<MarkerBitmapKey, BitmapDescriptor>(160)

    fun descriptor(
        key: MarkerBitmapKey,
        density: Float,
    ): BitmapDescriptor {
        cache.get(key)?.let { return it }
        val bitmap =
            when (key.kind) {
                MarkerVisualKind.Confirmed -> renderConfirmedBadge(key, density)
                MarkerVisualKind.Approximate -> renderApproximateMarker(key, density)
                MarkerVisualKind.Cluster -> renderClusterPill(key, density)
                MarkerVisualKind.ReplayPuck -> renderReplayPuck(key, density)
            }
        return BitmapDescriptorFactory.fromBitmap(bitmap).also { cache.put(key, it) }
    }

    private fun renderConfirmedBadge(
        key: MarkerBitmapKey,
        density: Float,
    ): Bitmap {
        val baseDp = if (key.memoryCount > 0) 34f else 28f
        val scale = if (key.selected) 1.25f else 1f
        val diameterPx = baseDp * scale * density
        val paddingPx = (if (key.selected) 6f else 4f) * density
        val extraBadgePx = if (key.memoryCount > 0) 8f * density else 0f
        val width = (diameterPx + paddingPx * 2 + extraBadgePx).roundToInt().coerceAtLeast(1)
        val height = (diameterPx + paddingPx * 2 + extraBadgePx / 2f).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = width / 2f - extraBadgePx / 3f
        val cy = height / 2f + extraBadgePx / 4f
        val radius = diameterPx / 2f
        val fillColor = categoryFillArgb(key.category, key.darkTheme)
        val rimColor = if (key.darkTheme) 0xFF1E242B.toInt() else 0xFFFFFFFF.toInt()
        drawBadgeCircles(canvas, cx, cy, radius, density, key, fillColor, rimColor)
        drawBadgeLabel(canvas, cx, cy, density, key)
        if (key.memoryCount > 0) {
            drawMemoryPip(canvas, cx, cy, radius, density, key.memoryCount, key.darkTheme, rimColor)
        }
        return bitmap
    }

    private fun drawBadgeCircles(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float,
        key: MarkerBitmapKey,
        fillColor: Int,
        rimColor: Int,
    ) {
        if (key.selected) {
            val haloPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (0x33 shl 24) or (fillColor and 0x00FFFFFF)
                    style = Paint.Style.FILL
                }
            canvas.drawCircle(cx, cy, radius + 4f * density, haloPaint)
        }
        if (key.memoryCount > 0) {
            val outerRingPaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = key.primaryArgb
                    style = Paint.Style.STROKE
                    strokeWidth = 2f * density
                }
            canvas.drawCircle(cx, cy, radius + 1.5f * density, outerRingPaint)
        }
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rimColor })
        canvas.drawCircle(cx, cy, radius - 2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor })
    }

    private fun drawBadgeLabel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        density: Float,
        key: MarkerBitmapKey,
    ) {
        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textAlign = Paint.Align.CENTER
                textSize = (if (key.selected) 13f else 11.5f) * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        val label = if (key.ordinal > 0) key.ordinal.toString() else "•"
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)
    }

    private fun drawMemoryPip(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        density: Float,
        memoryCount: Int,
        darkTheme: Boolean,
        rimColor: Int,
    ) {
        val pipRadius = 7.5f * density
        val pipX = cx + radius * 0.78f
        val pipY = cy - radius * 0.78f
        canvas.drawCircle(pipX, pipY, pipRadius + 1.2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = rimColor })
        canvas.drawCircle(
            pipX,
            pipY,
            pipRadius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (darkTheme) 0xFF938EFF.toInt() else 0xFF4F46E5.toInt() },
        )
        val pipTextPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                textAlign = Paint.Align.CENTER
                textSize = 7.5f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        canvas.drawText("+${memoryCount.coerceAtMost(9)}", pipX, pipY - (pipTextPaint.descent() + pipTextPaint.ascent()) / 2f, pipTextPaint)
    }

    private fun renderApproximateMarker(
        key: MarkerBitmapKey,
        density: Float,
    ): Bitmap {
        val diameter = (if (key.selected) 25f else 20f) * density
        val pad = 4f * density
        val size = (diameter + pad * 2).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = size / 2f
        val r = diameter / 2f
        val accent = if (key.darkTheme) 0xFF9AA5B1.toInt() else 0xFF687381.toInt()

        val fill =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (0x59 shl 24) or (accent and 0x00FFFFFF)
                style = Paint.Style.FILL
            }
        canvas.drawCircle(c, c, r, fill)

        val dash =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.STROKE
                strokeWidth = 1.8f * density
                pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
            }
        canvas.drawCircle(c, c, r - density, dash)

        val dot =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accent
                style = Paint.Style.FILL
            }
        canvas.drawCircle(c, c, 3f * density, dot)
        return bitmap
    }

    private fun renderClusterPill(
        key: MarkerBitmapKey,
        density: Float,
    ): Bitmap {
        val label = key.clusterCount.toString()
        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (key.darkTheme) 0xFFF2F4F8.toInt() else 0xFF1F2329.toInt()
                textSize = 12f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        val textWidth = textPaint.measureText(label)
        val dotDiameter = 8f * density
        val horizontalPad = 10f * density
        val width = (horizontalPad * 2 + dotDiameter + 6f * density + textWidth + 6f * density).roundToInt()
        val height = (28f * density + 4f * density).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val rect = RectF(2f * density, 2f * density, width - 2f * density, height - 2f * density)
        val bg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (key.darkTheme) 0xFF2B333D.toInt() else 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            }
        val border =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (key.darkTheme) 0xFF475569.toInt() else 0xFFD0D7DE.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
            }
        val corner = rect.height() / 2f
        canvas.drawRoundRect(rect, corner, corner, bg)
        canvas.drawRoundRect(rect, corner, corner, border)

        val dotPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = categoryFillArgb(key.category, key.darkTheme)
                style = Paint.Style.FILL
            }
        val dotX = rect.left + horizontalPad + dotDiameter / 2f
        val cy = rect.centerY()
        canvas.drawCircle(dotX, cy, dotDiameter / 2f, dotPaint)

        val textX = dotX + dotDiameter / 2f + 6f * density
        canvas.drawText(label, textX, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        return bitmap
    }

    private fun renderReplayPuck(
        key: MarkerBitmapKey,
        density: Float,
    ): Bitmap {
        val diameter = 22f * density
        val pad = 5f * density
        val size = (diameter + pad * 2).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = size / 2f
        val cy = size / 2f
        val r = diameter / 2f
        val glow =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (key.darkTheme) 0x4464DFDF else 0x444F46E5
                style = Paint.Style.FILL
            }
        canvas.drawCircle(cx, cy, r + 3f * density, glow)
        val rim =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            }
        canvas.drawCircle(cx, cy, r, rim)
        val core =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (key.darkTheme) 0xFF64DFDF.toInt() else 0xFF4F46E5.toInt()
                style = Paint.Style.FILL
            }
        canvas.drawCircle(cx, cy, r - 2.2f * density, core)

        // Draw a forward chevron rotated by the quantized segment bearing.
        canvas.save()
        canvas.rotate(key.bearingBucket * 15f, cx, cy)
        val chevron =
            Path().apply {
                moveTo(cx, cy - 4.5f * density)
                lineTo(cx - 3.2f * density, cy + 2.8f * density)
                lineTo(cx, cy + 1.0f * density)
                lineTo(cx + 3.2f * density, cy + 2.8f * density)
                close()
            }
        val arrowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (key.darkTheme) 0xFF0E1722.toInt() else 0xFFFFFFFF.toInt()
                style = Paint.Style.FILL
            }
        canvas.drawPath(chevron, arrowPaint)
        canvas.restore()
        return bitmap
    }

    private fun categoryFillArgb(
        category: String?,
        darkTheme: Boolean,
    ): Int =
        when (category?.uppercase()) {
            "HOME" -> if (darkTheme) 0xFF64DFDF.toInt() else 0xFF2B7A78.toInt()
            "WORK" -> if (darkTheme) 0xFF938EFF.toInt() else 0xFF4F46E5.toInt()
            "COFFEE", "FOOD" -> if (darkTheme) 0xFFFF8C66.toInt() else 0xFFD95D39.toInt()
            "OUTDOORS", "FITNESS" -> if (darkTheme) 0xFF52C48A.toInt() else 0xFF2D8A5B.toInt()
            "TRANSIT" -> if (darkTheme) 0xFFB794F4.toInt() else 0xFF7C3AED.toInt()
            else -> if (darkTheme) 0xFF72B0FF.toInt() else 0xFF3A86FF.toInt()
        }
}
