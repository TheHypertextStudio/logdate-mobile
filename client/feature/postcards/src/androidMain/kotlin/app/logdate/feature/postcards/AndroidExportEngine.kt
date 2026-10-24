package app.logdate.feature.postcards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import app.logdate.client.media.storage.androidMediaFileResolver
import app.logdate.client.media.storage.playableUri
import app.logdate.feature.postcards.model.CanvasBackground
import app.logdate.feature.postcards.model.CanvasElement
import app.logdate.feature.postcards.model.InkTool
import app.logdate.feature.postcards.model.PostcardDocument
import app.logdate.feature.postcards.model.ShapeKind
import app.logdate.feature.postcards.ui.ExportCaptureRegion
import app.logdate.feature.postcards.ui.ExportEngine
import app.logdate.feature.postcards.ui.ExportPreset
import app.logdate.feature.postcards.ui.ExportResult
import app.logdate.feature.postcards.ui.parseColor
import io.github.aakira.napier.Napier
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid

/**
 * Android implementation of [ExportEngine].
 *
 * Renders the postcard document to a [Bitmap] using Android's Canvas API,
 * saves it as a PNG to the app's cache directory, and returns a shareable
 * FileProvider URI.
 */
class AndroidExportEngine(
    private val context: Context,
) : ExportEngine {
    private val mediaFiles = androidMediaFileResolver(context)

    override suspend fun exportToImage(
        document: PostcardDocument,
        captureRegion: ExportCaptureRegion,
        preset: ExportPreset,
        targetWidthPx: Int,
        stickerUriMap: Map<Uuid, String>,
    ): ExportResult? =
        try {
            val aspectRatio = preset.widthRatio.toFloat() / preset.heightRatio.toFloat()
            val widthPx = targetWidthPx
            val heightPx = (widthPx / aspectRatio).toInt()

            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Scale from canvas coordinates (dp-like) to pixel coordinates
            val scaleX = widthPx / captureRegion.width
            val scaleY = heightPx / captureRegion.height
            canvas.translate(-captureRegion.x * scaleX, -captureRegion.y * scaleY)
            canvas.scale(scaleX, scaleY)

            drawBackground(canvas, document.background, captureRegion)

            val sortedElements = document.elements.sortedBy { it.zIndex }
            for (element in sortedElements) {
                drawElement(canvas, element, stickerUriMap)
            }

            // PNG is the right choice when the canvas is mostly flat colors,
            // ink, or crisp text — JPEG would add subtle ringing around the
            // letterforms. As soon as there's a photo on the postcard the
            // PNG bloats by 5–10× for no visible quality gain, so switch to
            // a high-quality JPEG so share-to-Messages stays sub-megabyte.
            val containsPhoto = document.elements.any { it is CanvasElement.Photo }
            val file = saveBitmapToCache(bitmap, asJpeg = containsPhoto)
            bitmap.recycle()

            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file,
                )

            ExportResult(
                uri = uri.toString(),
                widthPx = widthPx,
                heightPx = heightPx,
            )
        } catch (e: Exception) {
            Napier.e("Postcard export failed", e)
            null
        }

    private fun drawElement(
        canvas: Canvas,
        element: CanvasElement,
        stickerUriMap: Map<Uuid, String> = emptyMap(),
    ) {
        val transform = element.transform
        canvas.save()
        canvas.translate(transform.x, transform.y)
        canvas.rotate(transform.rotation)
        canvas.scale(transform.scaleX, transform.scaleY)

        when (element) {
            is CanvasElement.Photo -> drawPhoto(canvas, element)
            is CanvasElement.Text -> drawText(canvas, element)
            is CanvasElement.Ink -> drawInk(canvas, element)
            is CanvasElement.Shape -> drawShape(canvas, element)
            is CanvasElement.Sticker -> drawSticker(canvas, element, stickerUriMap)
        }

        canvas.restore()
    }

    private fun drawPhoto(
        canvas: Canvas,
        element: CanvasElement.Photo,
    ) {
        val dest = RectF(0f, 0f, PHOTO_EXPORT_SIZE, PHOTO_EXPORT_SIZE)
        val bitmap = loadOrientedDownsampledBitmap(element.mediaUri, PHOTO_DECODE_MAX_DIM_PX)
        if (bitmap == null) {
            drawMissingPlaceholder(canvas, dest)
            return
        }
        // Center-crop the source into the square destination so the user's
        // photo keeps its aspect ratio (no horizontal/vertical squashing).
        val src = centerCropSquare(bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, src, dest, photoPaint)
        bitmap.recycle()
    }

    private fun drawSticker(
        canvas: Canvas,
        element: CanvasElement.Sticker,
        stickerUriMap: Map<Uuid, String>,
    ) {
        val imageUri = stickerUriMap[element.stickerRef] ?: return
        val dest = RectF(0f, 0f, STICKER_EXPORT_SIZE, STICKER_EXPORT_SIZE)
        val bitmap = loadOrientedDownsampledBitmap(imageUri, STICKER_DECODE_MAX_DIM_PX) ?: return
        val src = centerCropSquare(bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, src, dest, photoPaint)
        bitmap.recycle()
    }

    /**
     * Decodes [uriString] at no larger than [maxDimPx] on its longest edge and
     * applies the EXIF orientation tag so the resulting bitmap is upright. Two
     * passes — one with `inJustDecodeBounds` to read source dimensions, one
     * with the computed `inSampleSize` — keep us from ever pulling the full
     * resolution into memory for a small render rect.
     *
     * Returns null on any failure; callers should fall back to a placeholder.
     */
    private fun loadOrientedDownsampledBitmap(
        uriString: String,
        maxDimPx: Int,
    ): Bitmap? {
        val uri =
            try {
                Uri.parse(mediaFiles.playableUri(uriString))
            } catch (e: Exception) {
                Napier.w("Could not parse URI for bitmap decode: $uriString", e)
                return null
            }

        val bounds =
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeStream(stream, null, this)
                    }
                }
            } catch (e: Exception) {
                Napier.w("Could not read bitmap bounds: $uriString", e)
                null
            }
        if (bounds == null || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decoded =
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val options =
                        BitmapFactory.Options().apply {
                            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimPx)
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (e: Exception) {
                Napier.w("Could not decode bitmap: $uriString", e)
                null
            } ?: return null

        val rotation = readExifRotationDegrees(uri)
        if (rotation == 0) return decoded

        return try {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap
                .createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { rotated ->
                    if (rotated !== decoded) decoded.recycle()
                }
        } catch (e: Exception) {
            Napier.w("Could not rotate bitmap by $rotation°: $uriString", e)
            decoded
        }
    }

    private fun computeInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxDimPx: Int,
    ): Int {
        if (maxDimPx <= 0) return 1
        var sampleSize = 1
        var w = sourceWidth
        var h = sourceHeight
        while (w / 2 >= maxDimPx && h / 2 >= maxDimPx) {
            sampleSize *= 2
            w /= 2
            h /= 2
        }
        return sampleSize
    }

    private fun readExifRotationDegrees(uri: Uri): Int =
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            Napier.w("Could not read EXIF orientation: $uri", e)
            0
        }

    private fun centerCropSquare(
        sourceWidth: Int,
        sourceHeight: Int,
    ): Rect {
        val side = minOf(sourceWidth, sourceHeight)
        val left = (sourceWidth - side) / 2
        val top = (sourceHeight - side) / 2
        return Rect(left, top, left + side, top + side)
    }

    private val photoPaint: Paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private fun drawMissingPlaceholder(
        canvas: Canvas,
        rect: RectF,
    ) {
        val bgPaint =
            Paint().apply {
                color = android.graphics.Color.LTGRAY
                style = Paint.Style.FILL
            }
        val xPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.DKGRAY
                style = Paint.Style.STROKE
                strokeWidth = PLACEHOLDER_STROKE_WIDTH
            }
        canvas.drawRect(rect, bgPaint)
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, xPaint)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, xPaint)
    }

    private fun drawText(
        canvas: Canvas,
        element: CanvasElement.Text,
    ) {
        val color = parseColor(element.color)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color =
                    android.graphics.Color.argb(
                        (color.alpha * 255).toInt(),
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt(),
                    )
                textSize = element.fontSize
                typeface = resolveTypeface(element.fontFamily)
            }
        canvas.drawText(element.content, 0f, element.fontSize, paint)
    }

    private fun drawInk(
        canvas: Canvas,
        element: CanvasElement.Ink,
    ) {
        val points = element.points
        if (points.size < 2) return

        val color = parseColor(element.color)
        val alpha =
            when (element.tool) {
                InkTool.HIGHLIGHTER -> HIGHLIGHTER_ALPHA
                else -> 1f
            }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color =
                    android.graphics.Color.argb(
                        (color.alpha * alpha * 255).toInt(),
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt(),
                    )
                style = Paint.Style.STROKE
                strokeWidth = element.strokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }

        // Offset ink points relative to the element's own origin
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val path =
            Path().apply {
                moveTo(points[0].x - minX, points[0].y - minY)
                for (i in 1 until points.size) {
                    lineTo(points[i].x - minX, points[i].y - minY)
                }
            }
        canvas.drawPath(path, paint)
    }

    private fun drawShape(
        canvas: Canvas,
        element: CanvasElement.Shape,
    ) {
        val strokeColor = parseColor(element.color)
        val strokePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color =
                    android.graphics.Color.argb(
                        (strokeColor.alpha * 255).toInt(),
                        (strokeColor.red * 255).toInt(),
                        (strokeColor.green * 255).toInt(),
                        (strokeColor.blue * 255).toInt(),
                    )
                style = Paint.Style.STROKE
                strokeWidth = element.strokeWidth
            }

        val fillPaint =
            element.fillColor?.let { fillHex ->
                val fillColor = parseColor(fillHex)
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color =
                        android.graphics.Color.argb(
                            (fillColor.alpha * 255).toInt(),
                            (fillColor.red * 255).toInt(),
                            (fillColor.green * 255).toInt(),
                            (fillColor.blue * 255).toInt(),
                        )
                    style = Paint.Style.FILL
                }
            }

        val rect = RectF(0f, 0f, element.width, element.height)

        when (element.shapeKind) {
            ShapeKind.RECTANGLE -> {
                fillPaint?.let { canvas.drawRoundRect(rect, SHAPE_CORNER_RADIUS, SHAPE_CORNER_RADIUS, it) }
                canvas.drawRoundRect(rect, SHAPE_CORNER_RADIUS, SHAPE_CORNER_RADIUS, strokePaint)
            }

            ShapeKind.CIRCLE -> {
                val cx = element.width / 2
                val cy = element.height / 2
                val radius = minOf(cx, cy)
                fillPaint?.let { canvas.drawCircle(cx, cy, radius, it) }
                canvas.drawCircle(cx, cy, radius, strokePaint)
            }

            ShapeKind.LINE -> {
                canvas.drawLine(0f, 0f, element.width, element.height, strokePaint)
            }

            ShapeKind.ARROW -> {
                canvas.drawLine(0f, 0f, element.width, element.height, strokePaint)
                val arrowSize = element.strokeWidth * ARROW_HEAD_SCALE
                val path =
                    Path().apply {
                        moveTo(element.width, element.height)
                        lineTo(element.width - arrowSize, element.height - arrowSize / 2)
                        lineTo(element.width - arrowSize / 2, element.height - arrowSize)
                        close()
                    }
                strokePaint.style = Paint.Style.FILL
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    private fun resolveTypeface(fontFamily: String): Typeface =
        when (fontFamily) {
            "caveat" -> Typeface.create("cursive", Typeface.NORMAL)
            "dancing-script" -> Typeface.create("cursive", Typeface.NORMAL)
            "patrick-hand" -> Typeface.SANS_SERIF
            else -> Typeface.DEFAULT
        }

    private fun drawBackground(
        canvas: Canvas,
        background: CanvasBackground,
        region: ExportCaptureRegion,
    ) {
        val paint = Paint()
        val rect = RectF(region.x, region.y, region.x + region.width, region.y + region.height)
        when (background) {
            is CanvasBackground.SolidColor -> {
                val color = parseColor(background.value)
                paint.color =
                    android.graphics.Color.argb(
                        (color.alpha * 255).toInt(),
                        (color.red * 255).toInt(),
                        (color.green * 255).toInt(),
                        (color.blue * 255).toInt(),
                    )
                canvas.drawRect(rect, paint)
            }

            is CanvasBackground.Gradient -> {
                val firstColor = background.stops.firstOrNull()?.let { parseColor(it.color) }
                if (firstColor != null) {
                    paint.color =
                        android.graphics.Color.argb(
                            (firstColor.alpha * 255).toInt(),
                            (firstColor.red * 255).toInt(),
                            (firstColor.green * 255).toInt(),
                            (firstColor.blue * 255).toInt(),
                        )
                    canvas.drawRect(rect, paint)
                }
            }
        }
    }

    private fun saveBitmapToCache(
        bitmap: Bitmap,
        asJpeg: Boolean,
    ): File {
        val cacheDir = File(context.externalCacheDir, CACHE_SUBDIR)
        cacheDir.mkdirs()
        val extension = if (asJpeg) "jpg" else "png"
        val file = File(cacheDir, "postcard_${System.currentTimeMillis()}.$extension")
        val format = if (asJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        val quality = if (asJpeg) JPEG_QUALITY else PNG_QUALITY
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
        return file
    }

    companion object {
        private const val PHOTO_EXPORT_SIZE = 200f
        private const val STICKER_EXPORT_SIZE = 80f

        // Decode caps in source pixels. The export destination is a small
        // square (200 dp), and even on a 1920-px-wide postcard with a 2x
        // scaled-up photo element the final pixel rect is ~1600 px — so
        // 2000 px of source on the longest edge is more than enough and
        // keeps memory predictable on mid-range devices.
        private const val PHOTO_DECODE_MAX_DIM_PX = 2000
        private const val STICKER_DECODE_MAX_DIM_PX = 512
        private const val SHAPE_CORNER_RADIUS = 4f
        private const val ARROW_HEAD_SCALE = 4
        private const val PLACEHOLDER_STROKE_WIDTH = 2f
        private const val CACHE_SUBDIR = "postcards"
        private const val PNG_QUALITY = 100

        // 92 is a sweet spot for JPEG quality on photo content — visually
        // indistinguishable from 100 at full-screen zoom but ~70 % smaller.
        private const val JPEG_QUALITY = 92
        private const val HIGHLIGHTER_ALPHA = 0.4f
    }
}
