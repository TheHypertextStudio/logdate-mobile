package app.logdate.feature.events.ui

import app.logdate.client.repository.journals.JournalNote
import app.logdate.ui.notes.noteFirstLine

/**
 * The first line of a [JournalNote]'s own words (its text, caption or transcript), for the event
 * editor's compact lists. Null when the note has no words; the row then leads with its date and
 * its thumbnail shows the note, rather than a label naming its type.
 *
 * Used by both the linked-notes list and the attach-note picker so they stay consistent.
 */
internal fun JournalNote.headline(): String? =
    noteFirstLine()?.take(HEADLINE_LENGTH)

private const val HEADLINE_LENGTH = 120
