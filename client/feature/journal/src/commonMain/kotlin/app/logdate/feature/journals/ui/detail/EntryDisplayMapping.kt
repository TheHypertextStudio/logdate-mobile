package app.logdate.feature.journals.ui.detail

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.isCapturedOnWatch

/**
 * Converts a note to the [EntryDisplayData] subtype for its kind, keeping media references for rich
 * rendering and whether it was captured on the watch.
 */
internal fun JournalNote.toEntryDisplayData(otherJournals: List<JournalReference> = emptyList()): EntryDisplayData =
    when (this) {
        is JournalNote.Handwriting -> {
            EntryDisplayData.HandwritingEntry(
                uid,
                creationTimestamp,
                document,
                otherJournals,
                isCapturedOnWatch,
                unreadable = unreadableContent != null,
                capturedDuring = capturedDuring,
            )
        }

        is JournalNote.Text -> {
            EntryDisplayData.TextEntry(
                id = uid,
                timestamp = creationTimestamp,
                content = content,
                otherJournals = otherJournals,
                capturedOnWatch = isCapturedOnWatch,
                capturedDuring = capturedDuring,
            )
        }

        is JournalNote.Image -> {
            EntryDisplayData.ImageEntry(
                id = uid,
                timestamp = creationTimestamp,
                mediaRef = mediaRef,
                caption = caption,
                presentation = presentation,
                otherJournals = otherJournals,
                capturedOnWatch = isCapturedOnWatch,
                capturedDuring = capturedDuring,
            )
        }

        is JournalNote.Video -> {
            EntryDisplayData.VideoEntry(
                id = uid,
                timestamp = creationTimestamp,
                mediaRef = mediaRef,
                caption = caption,
                otherJournals = otherJournals,
                capturedOnWatch = isCapturedOnWatch,
                capturedDuring = capturedDuring,
            )
        }

        is JournalNote.Audio -> {
            EntryDisplayData.AudioEntry(
                id = uid,
                timestamp = creationTimestamp,
                mediaRef = mediaRef,
                durationMs = durationMs,
                locationName = location?.displayName,
                otherJournals = otherJournals,
                capturedOnWatch = isCapturedOnWatch,
                capturedDuring = capturedDuring,
            )
        }
    }
