package app.logdate.wear.capture

import app.logdate.client.domain.today.MoodNoteTag
import app.logdate.client.repository.journals.JournalNote

/** What a note on the watch holds, as the watch's summaries see it. */
enum class MomentNoteKind {
    /** A mood check-in: a text note that starts with a `#mood:` tag. */
    FEELING,
    TEXT,
    VOICE,
    PHOTO,
    VIDEO,
    HANDWRITING,
}

/**
 * Writes and reads the text note a mood check-in saves.
 *
 * A check-in is stored as a text note such as `#mood:great Feeling great`: the tag first, then a
 * sentence for anyone reading the note somewhere else, such as the phone's timeline. Only the tag
 * carries the feeling; the sentence is never parsed. Saved notes already use this format, so it
 * does not change. [MoodNoteTag] reads the tag for the watch and for shared summaries alike.
 */
object MomentNoteCodec {
    /** The text a check-in of [feeling] is saved as. */
    fun encode(feeling: Feeling): String = "${MoodNoteTag.PREFIX}${feeling.tag} Feeling ${feeling.tag}"

    fun classify(note: JournalNote): MomentNoteKind =
        when (note) {
            is JournalNote.Text -> if (MoodNoteTag.isFeeling(note)) MomentNoteKind.FEELING else MomentNoteKind.TEXT
            is JournalNote.Audio -> MomentNoteKind.VOICE
            is JournalNote.Image -> MomentNoteKind.PHOTO
            is JournalNote.Video -> MomentNoteKind.VIDEO
            is JournalNote.Handwriting -> MomentNoteKind.HANDWRITING
        }

    /**
     * The feeling of the newest check-in among [notes] whose tag is known, or null. A newer check-in
     * with an unknown tag, written by a later build, is skipped.
     */
    fun latestFeeling(notes: List<JournalNote>): Feeling? =
        MoodNoteTag.latestTag(notes) { tag -> Feeling.fromTag(tag) != null }?.let(Feeling::fromTag)
}
