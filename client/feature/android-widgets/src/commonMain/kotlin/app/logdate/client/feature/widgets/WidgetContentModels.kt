package app.logdate.client.feature.widgets

import app.logdate.client.media.MediaObject
import app.logdate.client.repository.journals.JournalNote
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

enum class WidgetEntryKind { TEXT, IMAGE, VIDEO, AUDIO }

data class ChosenEntryContent(
    val noteId: String,
    val dateIso: String,
    val summary: String,
    val imageUri: String?,
    val kind: WidgetEntryKind,
)

fun JournalNote.toChosenEntryContent(transcript: String? = null): ChosenEntryContent {
    val (summary, imageUri, kind) =
        when (this) {
            is JournalNote.Handwriting -> Triple("Handwriting", null, WidgetEntryKind.TEXT)
            is JournalNote.Text -> Triple(content, null, WidgetEntryKind.TEXT)
            is JournalNote.Image -> Triple(caption, mediaRef, WidgetEntryKind.IMAGE)
            is JournalNote.Video -> Triple(caption, mediaRef, WidgetEntryKind.VIDEO)
            is JournalNote.Audio -> Triple(transcript.orEmpty(), null, WidgetEntryKind.AUDIO)
        }
    return ChosenEntryContent(
        noteId = uid.toString(),
        dateIso = creationTimestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString(),
        summary = summary.take(160),
        imageUri = imageUri,
        kind = kind,
    )
}

fun choosePhotoPrompt(
    media: List<MediaObject>,
    today: LocalDate,
): MediaObject.Image? {
    val candidates =
        media
            .filterIsInstance<MediaObject.Image>()
            .filter { image ->
                val photoDay = image.timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date
                photoDay >= today.minus(30, kotlinx.datetime.DateTimeUnit.DAY) && photoDay <= today
            }.sortedWith(compareByDescending<MediaObject.Image> { it.timestamp }.thenBy(MediaObject.Image::uri))
    if (candidates.isEmpty()) return null
    return candidates[today.toEpochDays().mod(candidates.size)]
}
