package app.logdate.wear.v2.moments

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.logdate.client.repository.journals.JournalNote
import app.logdate.wear.R
import kotlinx.datetime.TimeZone
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** A moment in a few words: its first line of text, its caption, or its kind and length. */
@Composable
fun momentLabel(note: JournalNote): String =
    when (note) {
        is JournalNote.Text -> {
            note.content
                .lineSequence()
                .map(String::trim)
                .firstOrNull(String::isNotEmpty)
                ?: stringResource(R.string.wear_moment_text)
        }

        is JournalNote.Audio -> {
            stringResource(R.string.wear_moment_voice, formatClockDuration(note.durationMs.milliseconds))
        }

        is JournalNote.Image -> {
            note.caption.ifBlank { stringResource(R.string.wear_moment_photo) }
        }

        is JournalNote.Video -> {
            note.caption.ifBlank { stringResource(R.string.wear_moment_video) }
        }

        is JournalNote.Handwriting -> {
            stringResource(R.string.wear_moment_handwriting)
        }
    }

/** The clock time of [instant] in [timeZone], in the user's 12 or 24 hour style. */
@Composable
fun momentClockTime(
    instant: Instant,
    timeZone: TimeZone,
): String {
    val context = LocalContext.current
    return remember(context, instant, timeZone) {
        val format = DateFormat.getTimeFormat(context)
        format.timeZone = java.util.TimeZone.getTimeZone(timeZone.id)
        format.format(Date(instant.toEpochMilliseconds()))
    }
}

/** The weekday and clock time of [instant] in [timeZone], such as "Fri 3:14 PM", in the user's 12 or 24 hour style. */
@Composable
fun momentDayAndTime(
    instant: Instant,
    timeZone: TimeZone,
): String {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(context, locale, instant, timeZone) {
        val skeleton = if (DateFormat.is24HourFormat(context)) DAY_AND_TIME_24_HOUR else DAY_AND_TIME_12_HOUR
        val format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        format.timeZone = java.util.TimeZone.getTimeZone(timeZone.id)
        format.format(Date(instant.toEpochMilliseconds()))
    }
}

private const val DAY_AND_TIME_24_HOUR = "EEEHHmm"
private const val DAY_AND_TIME_12_HOUR = "EEEhmma"
