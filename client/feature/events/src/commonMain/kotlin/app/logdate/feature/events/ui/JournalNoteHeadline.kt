package app.logdate.feature.events.ui

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import logdate.client.feature.events.generated.resources.Res
import logdate.client.feature.events.generated.resources.event_note_headline_handwriting
import logdate.client.feature.events.generated.resources.event_note_headline_photo
import logdate.client.feature.events.generated.resources.event_note_headline_text
import logdate.client.feature.events.generated.resources.event_note_headline_video
import logdate.client.feature.events.generated.resources.event_note_headline_voice_memo

/**
 * Short single-line label for a [JournalNote] used in the event editor's compact lists.
 *
 * Text notes show their first line truncated; media notes show a generic media-type label.
 * Used by both the linked-notes list and the attach-note picker so they stay consistent.
 */
internal fun JournalNote.headline(): UiText =
    when (this) {
        is JournalNote.Handwriting -> {
            Res.string.event_note_headline_handwriting.toUiText()
        }

        is JournalNote.Text -> {
            val firstLine =
                content
                    .lineSequence()
                    .firstOrNull()
                    ?.take(120)
                    .orEmpty()
            if (firstLine.isEmpty()) Res.string.event_note_headline_text.toUiText() else firstLine.toUiText()
        }

        is JournalNote.Image -> {
            Res.string.event_note_headline_photo.toUiText()
        }

        is JournalNote.Video -> {
            Res.string.event_note_headline_video.toUiText()
        }

        is JournalNote.Audio -> {
            Res.string.event_note_headline_voice_memo.toUiText()
        }
    }
