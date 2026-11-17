@file:Suppress("ktlint:standard:function-naming")

package app.logdate.client.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.text.BidiFormatter
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import app.logdate.client.media.storage.androidMediaFileResolver
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@Composable
internal fun OnThisDayWidgetContent(
    state: OnThisDayWidgetState,
    configureWidgetId: Int? = null,
    photoShape: PhotoWidgetShape = PhotoWidgetShape.SYSTEM,
) {
    GlanceTheme {
        when (state) {
            is OnThisDayWidgetState.HasMemory -> MemoryContent(state)
            is OnThisDayWidgetState.FixedMemory -> FixedMemoryContent(state)
            is OnThisDayWidgetState.PhotoPrompt -> PhotoPromptContent(state, photoShape)
            is OnThisDayWidgetState.ChooseMemory -> ConfigureMemoryContent(R.string.widget_choose_memory_message, configureWidgetId)
            is OnThisDayWidgetState.MissingMemory -> ConfigureMemoryContent(R.string.widget_missing_memory_message, configureWidgetId)
            is OnThisDayWidgetState.PhotoUnavailable -> PhotoUnavailableContent()
            is OnThisDayWidgetState.NewEntryReady -> NewEntryReadyContent()
            is OnThisDayWidgetState.Loading -> LoadingContent()
            is OnThisDayWidgetState.NewUser -> NewUserContent()
            is OnThisDayWidgetState.NoMemoryToday -> NoMemoryTodayContent()
        }
    }
}

/**
 * Resolves the launcher entry point from the installed package rather than a hardcoded component,
 * so the widget keeps opening the app across an applicationId change.
 */
private fun createWidgetLaunchIntent(
    context: Context,
    dateIso: String? = null,
    noteId: String? = null,
    newEntry: Boolean = false,
    photoUri: String? = null,
    record: Boolean = false,
    camera: Boolean = false,
): Intent =
    (
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    ).apply {
        if (dateIso != null) {
            putExtra(EXTRA_NAV_SOURCE, NAV_SOURCE_ON_THIS_DAY_WIDGET)
            putExtra(EXTRA_WIDGET_TARGET_DATE, dateIso)
        }
        if (noteId != null) {
            putExtra(EXTRA_NAV_SOURCE, NAV_SOURCE_FIXED_MEMORY_WIDGET)
            putExtra(EXTRA_WIDGET_NOTE_ID, noteId)
        }
        if (newEntry) {
            action = "${context.packageName}.widget.${if (camera) {
                "CAMERA"
            } else if (record) {
                "RECORD"
            } else {
                "WRITE"
            }}"
            putExtra(EXTRA_NAV_SOURCE, NAV_SOURCE_NEW_ENTRY_WIDGET)
            if (photoUri != null) putExtra(EXTRA_WIDGET_PHOTO_URI, photoUri)
            putExtra(EXTRA_WIDGET_RECORD, record)
            putExtra(EXTRA_WIDGET_CAMERA, camera)
        }
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

fun createWidgetConfigurationIntent(
    context: Context,
    appWidgetId: Int,
): Intent =
    Intent(context, FixedMemoryWidgetConfigActivity::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

private fun createWidgetAudioIntent(
    context: Context,
    noteId: String,
): Intent =
    Intent(context, WidgetAudioPlaybackActivity::class.java).apply {
        putExtra(EXTRA_WIDGET_NOTE_ID, noteId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

/**
 * Shared widget shell with rounded background and adaptive padding.
 */
@Composable
private fun WidgetContainer(
    modifier: GlanceModifier = GlanceModifier,
    onClick: Intent? = null,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val size = LocalSize.current
    val verticalPadding = widgetVerticalPadding(size)
    val horizontalPadding = widgetHorizontalPadding(size)

    val surfaceModifier = modifier.fillMaxSize().background(ImageProvider(R.drawable.widget_surface))
    val baseModifier = surfaceModifier.padding(horizontal = horizontalPadding, vertical = verticalPadding)

    val finalModifier =
        if (onClick != null) {
            baseModifier.clickable(actionStartActivity(onClick))
        } else {
            baseModifier
        }

    Column(
        modifier = finalModifier,
        horizontalAlignment = horizontalAlignment,
    ) {
        content()
    }
}

private fun widgetVerticalPadding(size: DpSize): Dp =
    when {
        size.height < 120.dp || size.width < 200.dp -> 12.dp
        size.width >= 320.dp || size.height >= 260.dp -> 24.dp
        else -> 16.dp
    }

private fun widgetHorizontalPadding(size: DpSize): Dp =
    when {
        size.width < 190.dp -> 14.dp
        size.height < 120.dp || size.width < 200.dp -> 16.dp
        size.width >= 320.dp || size.height >= 260.dp -> 24.dp
        else -> 20.dp
    }

// region Memory state

@Composable
private fun MemoryContent(state: OnThisDayWidgetState.HasMemory) {
    MemoryCard(state.dateIso, state.dateFormatted, state.summary, state.thumbnailUri, state.audioNoteId, state.audioUri)
}

@Composable
private fun FixedMemoryContent(state: OnThisDayWidgetState.FixedMemory) {
    MemoryCard(null, state.dateFormatted, state.summary, state.thumbnailUri, state.noteId, state.audioUri)
}

@Composable
private fun MemoryCard(
    dateIso: String?,
    dateFormatted: String,
    summary: String,
    thumbnailUri: String?,
    noteId: String?,
    audioUri: String?,
) {
    val context = LocalContext.current
    val launchIntent = createWidgetLaunchIntent(context, dateIso = dateIso, noteId = noteId)
    val size = LocalSize.current
    val isCompact = size.height < 120.dp || size.width < 200.dp
    val bodySize =
        when {
            isCompact -> 19.sp
            size.height >= 260.dp -> 24.sp
            else -> 23.sp
        }
    val availableHeight =
        size.height - widgetVerticalPadding(size) * 2 - (if (audioUri != null) 52.dp else 0.dp) - 12.dp
    val bodyLines =
        (availableHeight.value / (bodySize.value * 1.28f))
            .toInt()
            .coerceIn(1, if (isCompact) 3 else 8)
    // Decode the thumbnail from the internal file path. Glance composition runs on a
    // background coroutine (not the main thread), so file IO here is safe. We use
    // ImageProvider(Bitmap) instead of ImageProvider(Uri) because file:// URIs cannot
    // be shared with the launcher process via RemoteViews.setImageViewUri().
    val thumbnailBitmap = thumbnailUri?.let { loadScaledThumbnail(context, it) }
    if (thumbnailBitmap != null) {
        PinnedPrintCard(thumbnailBitmap, summary, dateFormatted, launchIntent)
        return
    }

    val textWidth = size.width - widgetHorizontalPadding(size) * 2 - 34.dp
    val quotedText = quotedWidgetText(context, summary, textWidth, bodySize.value, bodyLines)
    WidgetContainer(onClick = launchIntent) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            Box(modifier = GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                Text(
                    text = quotedText,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = bodySize),
                    maxLines = bodyLines,
                    modifier = GlanceModifier.fillMaxWidth().padding(start = 22.dp),
                )
                Text(
                    text = "“",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 26.sp),
                    modifier = GlanceModifier.width(22.dp),
                )
            }
            if (audioUri != null && noteId != null) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
                    Box(
                        modifier =
                            GlanceModifier
                                .size(44.dp)
                                .background(ImageProvider(R.drawable.widget_play_button))
                                .clickable(actionStartActivity(createWidgetAudioIntent(context, noteId))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_play),
                            contentDescription = context.getString(R.string.widget_play_audio),
                            modifier = GlanceModifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun quotedWidgetText(
    context: Context,
    summary: String,
    width: Dp,
    fontSizeSp: Float,
    maxLines: Int,
): String {
    val content = summary.trim().ifBlank { "…" }
    val metrics = context.resources.displayMetrics
    val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizeSp * metrics.scaledDensity
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
    val widthPx = (width.value.coerceAtLeast(24f) * metrics.density).roundToInt()

    fun fits(value: String): Boolean =
        StaticLayout.Builder
            .obtain(value, 0, value.length, paint, widthPx)
            .setIncludePad(false)
            .build()
            .lineCount <= maxLines

    val complete = "$content”"
    if (fits(complete)) return bidiSafe(complete)
    var low = 0
    var high = content.codePointCount(0, content.length)
    while (low < high) {
        val middle = (low + high + 1) / 2
        val prefix = content.substring(0, content.offsetByCodePoints(0, middle)).trimEnd()
        if (fits("$prefix…”")) {
            low = middle
        } else {
            high = middle - 1
        }
    }
    val candidate = content.substring(0, content.offsetByCodePoints(0, low)).trimEnd()
    val prefix =
        if (content.getOrNull(candidate.length)?.isWhitespace() == true) {
            candidate
        } else {
            candidate.substringBeforeLast(' ', missingDelimiterValue = candidate).trimEnd()
        }
    return bidiSafe("$prefix…”")
}

/**
 * Glance lays widget text out in the system's direction, so an English memory on a right-to-left
 * system (or an Arabic one on a left-to-right system) would show its closing "…”" at the start of
 * its last line. Marking the memory's own direction keeps the punctuation after its last word.
 */
private fun bidiSafe(text: String): String = BidiFormatter.getInstance(Locale.getDefault()).unicodeWrap(text)

@Composable
private fun PinnedPrintCard(
    bitmap: Bitmap,
    summary: String,
    dateFormatted: String,
    launchIntent: Intent,
) {
    val size = LocalSize.current
    val isNarrow = size.width < 200.dp
    val isWide = size.width >= 320.dp && size.width > size.height * 1.5f
    val isTall = size.height >= 240.dp && size.height > size.width * 1.1f
    val angle = if (dateFormatted.hashCode() and 1 == 0) 2.5f else -2.5f
    val printWidth = if (isWide) 576 else 384
    val printHeight =
        when {
            isWide -> 320
            isTall -> (384 * size.height.value / size.width.value).roundToInt().coerceIn(420, 640)
            isNarrow -> 392
            else -> 320
        }
    val printBitmap = renderPinnedPrint(bitmap, summary, angle, printWidth, printHeight, isWide)
    Image(
        provider = ImageProvider(printBitmap),
        contentDescription = listOf(summary.trim(), dateFormatted).filter(String::isNotBlank).joinToString(" · "),
        contentScale = ContentScale.Fit,
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(launchIntent)),
    )
}

private fun renderPinnedPrint(
    source: Bitmap,
    summary: String,
    angle: Float,
    width: Int,
    height: Int,
    isWide: Boolean,
): Bitmap {
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.save()
    canvas.rotate(angle, width / 2f, height / 2f)
    paint.color = AndroidColor.argb(45, 20, 28, 31)
    canvas.drawRoundRect(RectF(20f, 15f, width - 12f, height - 8f), 8f, 8f, paint)
    paint.color = AndroidColor.WHITE
    canvas.drawRoundRect(RectF(16f, 10f, width - 16f, height - 12f), 8f, 8f, paint)
    val photo =
        if (isWide) {
            RectF(36f, 32f, 292f, height - 32f)
        } else {
            RectF(36f, 32f, width - 36f, height - 76f)
        }
    val targetRatio = photo.width() / photo.height()
    val sourceRatio = source.width.toFloat() / source.height
    val crop =
        if (sourceRatio > targetRatio) {
            val width = (source.height * targetRatio).toInt()
            Rect((source.width - width) / 2, 0, (source.width + width) / 2, source.height)
        } else {
            val height = (source.width / targetRatio).toInt()
            Rect(0, (source.height - height) / 2, source.width, (source.height + height) / 2)
        }
    canvas.drawBitmap(source, crop, photo, paint)
    val captionPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(38, 39, 36)
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    val captionX = if (isWide) 320f else 42f
    val captionWidth = if (isWide) width - 356f else width - 84f
    if (summary.isNotBlank()) {
        drawPrintText(canvas, summary, captionPaint, captionX, captionWidth, if (isWide) 168f else height - 33f, 4f)
    }
    canvas.restore()
    return output
}

private fun drawPrintText(
    canvas: Canvas,
    text: String,
    paint: Paint,
    startX: Float,
    maxWidth: Float,
    baseline: Float,
    extraWordSpacing: Float,
) {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    val fitted = fitPrintText(normalized, paint, maxWidth, extraWordSpacing)
    val words = fitted.split(' ')
    var x = startX
    words.forEachIndexed { index, word ->
        canvas.drawText(word, x, baseline, paint)
        x += paint.measureText(word)
        if (index < words.lastIndex) x += paint.measureText(" ") + extraWordSpacing
    }
}

private fun fitPrintText(
    text: String,
    paint: Paint,
    maxWidth: Float,
    extraWordSpacing: Float,
): String {
    fun width(value: String) = paint.measureText(value) + value.count { it == ' ' } * extraWordSpacing
    if (width(text) <= maxWidth) return text
    var end = text.length
    while (end > 0 && width(text.substring(0, end).trimEnd() + "…") > maxWidth) end--
    return text.substring(0, end).trimEnd() + "…"
}

@Composable
private fun PhotoPromptContent(
    state: OnThisDayWidgetState.PhotoPrompt,
    shape: PhotoWidgetShape,
) {
    val context = LocalContext.current
    val writeIntent = createWidgetLaunchIntent(context, newEntry = true, photoUri = state.photoUri)
    val bitmap = loadScaledThumbnail(context, state.photoUri)
    if (bitmap == null) {
        PhotoUnavailableContent()
        return
    }
    val size = LocalSize.current
    val isNarrow = size.width < 200.dp
    val isWide = size.width >= 320.dp && size.width > size.height * 1.5f
    val photoWidth = (size.width - 16.dp).coerceAtLeast(1.dp)
    val photoHeight = (size.height - 24.dp).coerceAtLeast(1.dp)
    val shapedPhoto = renderPromptPhoto(context, bitmap, photoWidth.value, photoHeight.value, shape, alignTop = isWide)
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = if (isWide) Alignment.BottomEnd else Alignment.BottomCenter) {
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Image(
                provider = ImageProvider(shapedPhoto),
                contentDescription = context.getString(R.string.widget_photo_prompt_description),
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.width(photoWidth).height(photoHeight).clickable(actionStartActivity(writeIntent)),
            )
        }
        PhotoPromptActions(state.photoUri, isNarrow, isWide)
    }
}

@Composable
private fun PhotoPromptActions(
    photoUri: String?,
    isNarrow: Boolean,
    isWide: Boolean,
) {
    val context = LocalContext.current
    val sideDiameter =
        when {
            isNarrow -> 44
            isWide -> 56
            else -> 52
        }
    val centerDiameter =
        when {
            isNarrow -> 48
            isWide -> 64
            else -> 56
        }
    val spacing =
        when {
            isNarrow -> 6
            isWide -> 10
            else -> 8
        }
    Row(modifier = if (isWide) GlanceModifier.padding(end = 16.dp) else GlanceModifier) {
        PhotoPromptAction(
            context.getString(R.string.widget_action_write),
            R.drawable.ic_widget_write,
            R.drawable.widget_action_write,
            createWidgetLaunchIntent(context, newEntry = true, photoUri = photoUri),
            sideDiameter,
        )
        Spacer(modifier = GlanceModifier.width(spacing.dp))
        PhotoPromptAction(
            context.getString(R.string.widget_action_record),
            R.drawable.ic_widget_record,
            R.drawable.widget_action_record,
            createWidgetLaunchIntent(context, newEntry = true, photoUri = photoUri, record = true),
            centerDiameter,
        )
        Spacer(modifier = GlanceModifier.width(spacing.dp))
        PhotoPromptAction(
            context.getString(R.string.widget_action_camera),
            R.drawable.ic_widget_camera,
            R.drawable.widget_action_camera,
            createWidgetLaunchIntent(context, newEntry = true, camera = true),
            sideDiameter,
        )
    }
}

@Composable
private fun PhotoPromptAction(
    label: String,
    icon: Int,
    background: Int,
    intent: Intent,
    diameter: Int,
) {
    Box(
        modifier =
            GlanceModifier
                .size(diameter.dp)
                .background(ImageProvider(background))
                .clickable(actionStartActivity(intent)),
        contentAlignment = Alignment.Center,
    ) {
        Image(provider = ImageProvider(icon), contentDescription = label, modifier = GlanceModifier.size(24.dp))
    }
}

private fun renderPromptPhoto(
    context: Context,
    source: Bitmap,
    widthDp: Float,
    heightDp: Float,
    shape: PhotoWidgetShape,
    alignTop: Boolean,
): Bitmap {
    val safeWidth = widthDp.coerceAtLeast(1f)
    val safeHeight = heightDp.coerceAtLeast(1f)
    val scale = MAX_WIDGET_THUMBNAIL_PX / maxOf(safeWidth, safeHeight)
    val width = (safeWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (safeHeight * scale).roundToInt().coerceAtLeast(1)
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val bounds =
        if (shape == PhotoWidgetShape.CIRCLE) {
            val diameter = minOf(width, height).toFloat()
            RectF((width - diameter) / 2f, (height - diameter) / 2f, (width + diameter) / 2f, (height + diameter) / 2f)
        } else {
            RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
    val imageScale = maxOf(bounds.width() / source.width, bounds.height() / source.height)
    val imageMatrix =
        Matrix().apply {
            setScale(imageScale, imageScale)
            postTranslate(
                bounds.left + (bounds.width() - source.width * imageScale) / 2f,
                if (alignTop && shape != PhotoWidgetShape.CIRCLE) {
                    bounds.top
                } else {
                    bounds.top + (bounds.height() - source.height * imageScale) / 2f
                },
            )
        }
    val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(imageMatrix) }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
    val systemRadiusDp = systemWidgetCornerRadiusDp(context)
    val radiusDp = if (shape == PhotoWidgetShape.SOFT) maxOf(systemRadiusDp, 48f) else systemRadiusDp
    val radiusPx = if (shape == PhotoWidgetShape.CIRCLE) bounds.width() / 2f else radiusDp * scale
    canvas.drawRoundRect(bounds, radiusPx, radiusPx, paint)
    return output
}

@Composable
private fun PhotoUnavailableContent() = NewEntryEmptyContent()

@Composable
private fun NewEntryReadyContent() = NewEntryEmptyContent()

@Composable
private fun NewEntryEmptyContent() {
    val size = LocalSize.current
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PhotoPromptActions(null, size.width < 200.dp, size.width >= 320.dp && size.width > size.height * 1.5f)
    }
}

@Composable
private fun ConfigureMemoryContent(
    messageRes: Int,
    configureWidgetId: Int?,
) {
    val context = LocalContext.current
    WidgetContainer(onClick = configureWidgetId?.let { createWidgetConfigurationIntent(context, it) }) {
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = context.getString(messageRes),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 22.sp),
        )
        Spacer(GlanceModifier.defaultWeight())
    }
}

// endregion

// region Empty states — centered layout with icon anchor

@Composable
private fun LoadingContent() {
    val context = LocalContext.current

    WidgetContainer(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = GlanceModifier.defaultWeight())

        Image(
            provider = ImageProvider(R.drawable.ic_widget_on_this_day),
            contentDescription = null,
            modifier = GlanceModifier.size(32.dp),
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        Text(
            text = context.getString(R.string.widget_loading_message),
            style =
                TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                ),
        )

        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun NewUserContent() {
    val context = LocalContext.current
    val launchIntent = createWidgetLaunchIntent(context)

    WidgetContainer(
        onClick = launchIntent,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = GlanceModifier.defaultWeight())

        Image(
            provider = ImageProvider(R.drawable.ic_widget_on_this_day),
            contentDescription = null,
            modifier = GlanceModifier.size(32.dp),
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        EmptyStateBody(message = context.getString(R.string.widget_new_user_message))

        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun NoMemoryTodayContent() {
    val context = LocalContext.current
    val launchIntent = createWidgetLaunchIntent(context)

    WidgetContainer(
        onClick = launchIntent,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = GlanceModifier.defaultWeight())

        Image(
            provider = ImageProvider(R.drawable.ic_widget_on_this_day),
            contentDescription = null,
            modifier = GlanceModifier.size(32.dp),
        )

        Spacer(modifier = GlanceModifier.height(8.dp))

        EmptyStateBody(message = context.getString(R.string.widget_empty_message))

        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

@Composable
private fun EmptyStateBody(message: String) {
    val size = LocalSize.current
    val isCompact = size.height < 120.dp || size.width < 200.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = GlanceModifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style =
                TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = if (isCompact) 16.sp else 20.sp,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}

/**
 * Decodes the image at [uriString] into a downsampled [Bitmap] suitable for
 * embedding in a widget's [RemoteViews].
 *
 * Bounds the embedded bitmap to 384 pixels per side so even a high-resolution
 * device photo remains small enough for a RemoteViews update.
 *
 * The decoded bitmap is cached in a single-entry process-singleton keyed by
 * file path + last-modified time, so the home screen doesn't pay the decode
 * cost again on every Glance recomposition for the same thumbnail. A new
 * source — or a touched source file — invalidates the cache automatically.
 *
 * Returns null if the file cannot be decoded (missing file, unsupported format, etc.).
 */
fun loadScaledThumbnail(
    context: Context,
    uriString: String,
): Bitmap? {
    if (uriString.startsWith("content://") || uriString.startsWith("android.resource://")) {
        return try {
            val uri = Uri.parse(uriString)
            decodeBoundedThumbnail { context.contentResolver.openInputStream(uri) }
                ?: decodeVideoFrame { setDataSource(context, uri) }
        } catch (_: Exception) {
            null
        }
    }
    val path = androidMediaFileResolver(context).filePath(uriString) ?: return null
    val key = thumbnailCacheKey(path)
    if (key != null) {
        synchronized(thumbnailCacheLock) {
            cachedThumbnail?.let { cached ->
                if (cached.key == key) return cached.bitmap
            }
        }
    }
    val decoded =
        try {
            // A video's file isn't an image; its frame stands in for it, as everywhere else.
            decodeBoundedThumbnail { FileInputStream(path) } ?: decodeVideoFrame { setDataSource(path) }
        } catch (_: Exception) {
            null
        } ?: return null
    if (key != null) {
        synchronized(thumbnailCacheLock) {
            cachedThumbnail = CachedWidgetThumbnail(key, decoded)
        }
    }
    return decoded
}

private fun decodeBoundedThumbnail(open: () -> InputStream?): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = open() ?: return null
    boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_WIDGET_THUMBNAIL_PX) sample *= 2
    val decoded =
        open()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    if (longest <= MAX_WIDGET_THUMBNAIL_PX) return decoded
    val width = (decoded.width * MAX_WIDGET_THUMBNAIL_PX / longest).coerceAtLeast(1)
    val height = (decoded.height * MAX_WIDGET_THUMBNAIL_PX / longest).coerceAtLeast(1)
    return Bitmap.createScaledBitmap(decoded, width, height, true).also { decoded.recycle() }
}

private const val MAX_WIDGET_THUMBNAIL_PX = 384

/** A frame from early in the video [setSource] opens, bounded like any other widget thumbnail. */
private fun decodeVideoFrame(setSource: MediaMetadataRetriever.() -> Unit): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setSource()
        // A second in skips the black lead-in most clips start with; a shorter clip uses its first frame.
        listOf(VIDEO_FRAME_TIME_US, 0L).firstNotNullOfOrNull { timeUs ->
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                MAX_WIDGET_THUMBNAIL_PX,
                MAX_WIDGET_THUMBNAIL_PX,
            )
        }
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private const val VIDEO_FRAME_TIME_US = 1_000_000L

private fun thumbnailCacheKey(path: String): String? =
    try {
        val mtime = java.io.File(path).lastModified()
        if (mtime > 0) "$path:$mtime" else null
    } catch (_: Exception) {
        null
    }

private data class CachedWidgetThumbnail(
    val key: String,
    val bitmap: Bitmap,
)

private val thumbnailCacheLock = Any()

@Volatile
private var cachedThumbnail: CachedWidgetThumbnail? = null

// endregion
