package app.logdate.client.domain.notes

import app.logdate.client.repository.journals.EntryEditBase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository

/** A concurrent edit or deletion leaves the editor's draft available for separate publication. */
class EntryEditConflictException : IllegalStateException("This entry changed elsewhere. Your edited version is preserved in Drafts.")

/** Applies only the fields exposed by the editor, against the version loaded with its draft. */
internal suspend fun JournalNotesRepository.persistEditorNote(
    edited: JournalNote,
    base: EntryEditBase,
): Boolean {
    check(getNoteRecoveryScope() == base.scope) { "The entry account changed while editing" }
    if (edited.uid != base.original.uid || base.copyNoteId != null) return persistNewEditorNote(edited, base)
    val current = getNoteById(base.original.uid) ?: throw EntryEditConflictException()
    require(current.type == edited.type) { "An entry cannot change its content type" }
    val comparable = base.original.withEditorChanges(current, base.original.lastUpdated)
    val requested = base.original.withEditorChanges(edited, base.original.lastUpdated)
    if (comparable != base.original && comparable != requested) throw EntryEditConflictException()
    val replacement = current.withEditorChanges(edited)
    if (!compareAndSetNoteForRestore(current, replacement, base.scope)) throw EntryEditConflictException()
    return true
}

private suspend fun JournalNotesRepository.persistNewEditorNote(
    edited: JournalNote,
    base: EntryEditBase,
): Boolean {
    val note = if (edited.uid == base.copyNoteId) base.original.withEditorChanges(edited).asNewCopy(edited.uid) else edited
    val current = getNoteById(note.uid)
    if (current != null) {
        if (current.type != note.type || current.withEditorChanges(note, current.lastUpdated) != current) throw EntryEditConflictException()
        if (!compareAndSetNoteForRestore(current, current, base.scope)) throw EntryEditConflictException()
        return true
    }
    if (!compareAndSetNoteForRestore(null, note, base.scope)) throw EntryEditConflictException()
    return false
}

private fun JournalNote.asNewCopy(id: kotlin.uuid.Uuid): JournalNote =
    when (this) {
        is JournalNote.Handwriting -> copy(uid = id, syncVersion = 0)
        is JournalNote.Text -> copy(uid = id, syncVersion = 0)
        is JournalNote.Image -> copy(uid = id, syncVersion = 0)
        is JournalNote.Audio -> copy(uid = id, syncVersion = 0)
        is JournalNote.Video -> copy(uid = id, syncVersion = 0)
    }

private fun JournalNote.withEditorChanges(
    edited: JournalNote,
    updatedAt: kotlin.time.Instant = edited.lastUpdated,
): JournalNote =
    when (this) {
        is JournalNote.Handwriting -> {
            withHandwritingEditorChanges(edited as JournalNote.Handwriting, updatedAt)
        }

        is JournalNote.Text -> {
            (edited as JournalNote.Text).let {
                copy(
                    content = it.content,
                    creationTimestamp = it.creationTimestamp,
                    lastUpdated = updatedAt,
                    timeZoneId =
                        it.timeZoneId ?: timeZoneId,
                )
            }
        }

        is JournalNote.Image -> {
            (edited as JournalNote.Image).let {
                copy(
                    mediaRef = it.mediaRef,
                    caption = it.caption,
                    presentation = it.presentation,
                    creationTimestamp = it.creationTimestamp,
                    lastUpdated = updatedAt,
                    timeZoneId = it.timeZoneId ?: timeZoneId,
                )
            }
        }

        is JournalNote.Video -> {
            (edited as JournalNote.Video).let {
                copy(
                    mediaRef = it.mediaRef,
                    caption = it.caption,
                    durationMs = it.durationMs ?: durationMs,
                    creationTimestamp = it.creationTimestamp,
                    lastUpdated = updatedAt,
                    timeZoneId = it.timeZoneId ?: timeZoneId,
                )
            }
        }

        is JournalNote.Audio -> {
            (edited as JournalNote.Audio).let {
                copy(
                    mediaRef = it.mediaRef,
                    caption = it.caption,
                    durationMs = it.durationMs,
                    transcription = it.transcription,
                    creationTimestamp = it.creationTimestamp,
                    lastUpdated = updatedAt,
                    timeZoneId = it.timeZoneId ?: timeZoneId,
                )
            }
        }
    }

private fun JournalNote.Handwriting.withHandwritingEditorChanges(
    edited: JournalNote.Handwriting,
    updatedAt: kotlin.time.Instant,
): JournalNote.Handwriting =
    copy(
        document = edited.document,
        unreadableContent = edited.unreadableContent,
        creationTimestamp = edited.creationTimestamp,
        lastUpdated = updatedAt,
        timeZoneId = edited.timeZoneId ?: timeZoneId,
    )
