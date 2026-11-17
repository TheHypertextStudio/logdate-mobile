@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.ui.opening

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.logdate.util.asTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_beach
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_concert
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_evening
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_hike
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_note_long_way
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_note_nana
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_photo
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_pizza
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_rain
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_record
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_roadtrip
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_thought
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_voice_wish
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The outline a piece is cut to. */
internal fun OpeningPiece.outline(): Shape =
    when (val content = content) {
        is StampContent -> {
            RoundedCornerShape(22)
        }

        is PrintContent -> {
            when (content.frame) {
                PrintFrame.Instant -> RoundedCornerShape(8.dp)
                PrintFrame.Border -> RoundedCornerShape(6.dp)
                PrintFrame.Bare -> RoundedCornerShape(16.dp)
            }
        }

        is NoteContent -> {
            if (height <=
                PILL_TALLEST
            ) {
                CircleShape
            } else {
                RoundedCornerShape(if (content.tone == PieceTone.Peach) 6.dp else 18.dp)
            }
        }

        is VoiceContent -> {
            if (height <= PILL_TALLEST) CircleShape else RoundedCornerShape(24.dp)
        }

        is SketchContent -> {
            RoundedCornerShape(10.dp)
        }
    }

/** The colour a piece is made in, and the colour of what is written on it. */
@Composable
internal fun PieceTone.paper(): Color =
    when (this) {
        PieceTone.Paper -> MaterialTheme.colorScheme.surfaceContainerLowest
        PieceTone.Cream -> MaterialTheme.colorScheme.surfaceContainerHigh
        PieceTone.Peach -> MaterialTheme.colorScheme.tertiaryContainer
        PieceTone.Blush -> MaterialTheme.colorScheme.primaryContainer
    }

@Composable
private fun PieceTone.ink(): Color =
    when (this) {
        PieceTone.Paper, PieceTone.Cream -> MaterialTheme.colorScheme.onSurface
        PieceTone.Peach -> MaterialTheme.colorScheme.onTertiaryContainer
        PieceTone.Blush -> MaterialTheme.colorScheme.onPrimaryContainer
    }

@Composable
internal fun OpeningPiece.paper(): Color =
    when (val content = content) {
        is StampContent -> content.tone.paper()
        is PrintContent -> if (content.frame == PrintFrame.Bare) MaterialTheme.colorScheme.surfaceContainerHigh else PieceTone.Paper.paper()
        is NoteContent -> content.tone.paper()
        is VoiceContent -> content.tone.paper()
        is SketchContent -> content.tone.paper()
    }

/** What is on a piece. [today] is the real date, which is the date on the table. */
@Composable
internal fun PieceFace(
    piece: OpeningPiece,
    today: LocalDate,
) {
    when (val content = piece.content) {
        is StampContent -> StampFace(today, content.tone)
        is PrintContent -> PrintFace(content, content.time?.onClock(today))
        is NoteContent -> NoteFace(content, content.time?.onClock(today), pill = piece.height <= PILL_TALLEST)
        is VoiceContent -> VoiceFace(content)
        is SketchContent -> SketchFace(content)
    }
}

/**
 * A time of day the way the rest of LogDate writes one, "4:42 PM" where the person's language
 * uses a twelve-hour clock. The date only places the time; any day gives the same words.
 */
private fun LocalTime.onClock(today: LocalDate): String = LocalDateTime(today, this).toInstant(TimeZone.currentSystemDefault()).asTime

/** A date: the weekday, the day large, and the month. */
@Composable
private fun StampFace(
    date: LocalDate,
    tone: PieceTone,
) {
    val ink = tone.ink()
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            DayOfWeekNames.ENGLISH_ABBREVIATED.names[date.dayOfWeek.isoDayNumber - 1].uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ink.copy(alpha = 0.72f),
        )
        Text(
            date.day.toString(),
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            color = ink,
        )
        Text(
            MonthNames.ENGLISH_ABBREVIATED.names[date.month.number - 1].uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = ink.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun PrintFace(
    content: PrintContent,
    time: String?,
) {
    when (content.frame) {
        PrintFrame.Instant -> {
            Column(Modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, end = 8.dp)) {
                Picture(content.photo, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(3.dp)))
                Box(Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
                    Text(time.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        PrintFrame.Border -> {
            Picture(content.photo, Modifier.fillMaxSize().padding(7.dp).clip(RoundedCornerShape(2.dp)))
        }

        PrintFrame.Bare -> {
            Box(Modifier.fillMaxSize()) {
                Picture(content.photo, Modifier.fillMaxSize())
                if (content.clip != null) ClipBadge(content.clip, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
        }
    }
}

/** The mark that makes a picture a clip: a play arrow and how long it runs. */
@Composable
private fun ClipBadge(
    length: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(start = 7.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Canvas(Modifier.size(9.dp)) { drawPath(playArrow(center, size.minDimension / 2f), Color.White) }
        Text(length, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
    }
}

/**
 * A photo, cropped to fill whatever room it is given.
 *
 * Four of the photos share one sheet, two by two, so each of those is cropped from its quarter.
 */
@Composable
private fun Picture(
    photo: ScenePhoto,
    modifier: Modifier = Modifier,
) {
    val source = photo.source()
    val image = imageResource(source.drawable)
    Canvas(modifier) {
        val quarter = source.quarter
        val regionWidth = if (quarter == null) image.width else image.width / 2
        val regionHeight = if (quarter == null) image.height else image.height / 2
        val regionLeft = if (quarter == null) 0 else quarter.first * regionWidth
        val regionTop = if (quarter == null) 0 else quarter.second * regionHeight
        val wanted = size.width / size.height
        val cropWidth = if (regionWidth.toFloat() / regionHeight > wanted) (regionHeight * wanted).roundToInt() else regionWidth
        val cropHeight = if (regionWidth.toFloat() / regionHeight > wanted) regionHeight else (regionWidth / wanted).roundToInt()
        drawImage(
            image = image,
            srcOffset = IntOffset(regionLeft + (regionWidth - cropWidth) / 2, regionTop + (regionHeight - cropHeight) / 2),
            srcSize = IntSize(cropWidth, cropHeight),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

private class PhotoSource(
    val drawable: DrawableResource,
    val quarter: Pair<Int, Int>? = null,
)

private fun ScenePhoto.source(): PhotoSource =
    when (this) {
        ScenePhoto.Cafe -> PhotoSource(Res.drawable.onboarding_scene_photo, 0 to 0)
        ScenePhoto.Dusk -> PhotoSource(Res.drawable.onboarding_scene_photo, 1 to 0)
        ScenePhoto.Cake -> PhotoSource(Res.drawable.onboarding_scene_photo, 0 to 1)
        ScenePhoto.Couch -> PhotoSource(Res.drawable.onboarding_scene_photo, 1 to 1)
        ScenePhoto.Pug -> PhotoSource(Res.drawable.onboarding_scene_evening)
        ScenePhoto.Concert -> PhotoSource(Res.drawable.onboarding_scene_concert)
        ScenePhoto.Hike -> PhotoSource(Res.drawable.onboarding_scene_hike)
        ScenePhoto.Pizza -> PhotoSource(Res.drawable.onboarding_scene_pizza)
        ScenePhoto.Record -> PhotoSource(Res.drawable.onboarding_scene_record)
        ScenePhoto.Beach -> PhotoSource(Res.drawable.onboarding_scene_beach)
        ScenePhoto.RoadTrip -> PhotoSource(Res.drawable.onboarding_scene_roadtrip)
        ScenePhoto.Rain -> PhotoSource(Res.drawable.onboarding_scene_rain)
    }

@Composable
private fun SceneText.words(): String =
    stringResource(
        when (this) {
            SceneText.Coffee -> Res.string.onboarding_scene_thought
            SceneText.Nana -> Res.string.onboarding_scene_note_nana
            SceneText.LongWay -> Res.string.onboarding_scene_note_long_way
            SceneText.Wish -> Res.string.onboarding_scene_voice_wish
        },
    )

/** A few words: one line across a pill, large on a sticky square, or under their time on a card. */
@Composable
private fun NoteFace(
    content: NoteContent,
    time: String?,
    pill: Boolean,
) {
    val ink = content.tone.ink()
    when {
        pill -> {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(
                    content.text.words(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = ink,
                    maxLines = 1,
                )
            }
        }

        content.tone == PieceTone.Peach -> {
            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.CenterStart) {
                Text(content.text.words(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = ink)
            }
        }

        else -> {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                if (time != null) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(content.text.words(), style = MaterialTheme.typography.bodyMedium, color = ink)
            }
        }
    }
}

/** A voice note: a play button, the sound, its length, and under them what was said where that is known. */
@Composable
private fun VoiceFace(content: VoiceContent) {
    val ink = content.tone.ink()
    val accent = if (content.tone == PieceTone.Paper) MaterialTheme.colorScheme.primary else ink
    val onAccent = content.tone.paper()
    Column(
        Modifier.fillMaxSize().padding(start = 9.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(accent)
                drawPath(playArrow(center, 5.dp.toPx()), onAccent)
            }
            Canvas(Modifier.weight(1f).height(22.dp)) {
                SOUND.forEachIndexed { index, level ->
                    val x = index * size.width / (SOUND.size - 1)
                    drawLine(
                        accent.copy(alpha = 0.7f),
                        Offset(x, center.y - level * 8.dp.toPx()),
                        Offset(x, center.y + level * 8.dp.toPx()),
                        2.dp.toPx(),
                        StrokeCap.Round,
                    )
                }
            }
            Text(content.length, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.72f))
        }
        if (content.heard != null) {
            Text(
                content.heard.words(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = ink,
                textAlign = TextAlign.Start,
                maxLines = 1,
                modifier = Modifier.padding(start = 7.dp),
            )
        }
    }
}

/** A drawing made with a finger or a pen: one unbroken line, a little uneven, the way a hand draws. */
@Composable
private fun SketchFace(content: SketchContent) {
    val ink = if (content.drawing == SceneSketch.Heart) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize().padding(14.dp)) {
        val pen = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (content.drawing) {
            SceneSketch.Heart -> {
                drawPath(
                    Path().apply {
                        moveTo(size.width * 0.50f, size.height * 0.88f)
                        cubicTo(
                            size.width * 0.04f,
                            size.height * 0.56f,
                            size.width * 0.08f,
                            size.height * 0.12f,
                            size.width * 0.33f,
                            size.height * 0.15f,
                        )
                        cubicTo(
                            size.width * 0.46f,
                            size.height * 0.17f,
                            size.width * 0.51f,
                            size.height * 0.30f,
                            size.width * 0.51f,
                            size.height * 0.38f,
                        )
                        cubicTo(
                            size.width * 0.53f,
                            size.height * 0.22f,
                            size.width * 0.64f,
                            size.height * 0.11f,
                            size.width * 0.76f,
                            size.height * 0.16f,
                        )
                        cubicTo(
                            size.width * 1.00f,
                            size.height * 0.27f,
                            size.width * 0.88f,
                            size.height * 0.62f,
                            size.width * 0.46f,
                            size.height * 0.93f,
                        )
                    },
                    ink,
                    style = pen,
                )
            }

            SceneSketch.Sun -> {
                val centre = Offset(size.width * 0.5f, size.height * 0.52f)
                val reach = size.minDimension
                drawArc(ink, 18f, 336f, false, centre - Offset(reach * 0.2f, reach * 0.2f), Size(reach * 0.4f, reach * 0.4f), style = pen)
                repeat(8) { ray ->
                    val turn = (ray * 45f + 8f) * (PI.toFloat() / 180f)
                    val far = reach * if (ray % 2 == 0) 0.47f else 0.41f
                    drawLine(
                        ink,
                        centre + Offset(cos(turn), sin(turn)) * (reach * 0.31f),
                        centre + Offset(cos(turn), sin(turn)) * far,
                        pen.width,
                        StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private fun playArrow(
    centre: Offset,
    reach: Float,
) = Path().apply {
    moveTo(centre.x - reach * 0.7f, centre.y - reach)
    lineTo(centre.x + reach, centre.y)
    lineTo(centre.x - reach * 0.7f, centre.y + reach)
    close()
}

// How loud the sound is along a voice note, from 0 to 1.
private val SOUND = listOf(0.3f, 0.55f, 0.85f, 0.45f, 1f, 0.7f, 0.4f, 0.82f, 0.6f, 0.92f, 0.5f, 0.3f, 0.45f, 0.25f)

// A note or a voice note no taller than this is a pill with one line across it.
private const val PILL_TALLEST = 56f
