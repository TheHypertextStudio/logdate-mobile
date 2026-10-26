package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingUpload

/**
 * Drops entries still inside their retry backoff before callers decide whether there is any
 * work. Dead-lettered entries stay queued indefinitely, so without this an entry that can
 * never upload keeps every sync run loading a whole table to do nothing with.
 */
internal suspend fun List<PendingUpload>.dueNow(
    entityType: EntityType,
    retryCoordinator: SyncRetryCoordinator,
): List<PendingUpload> = filter { retryCoordinator.shouldAttempt(entityType, it) }

/**
 * Records that an upload of [pending] is starting, before anything that could take the app down with
 * it. Returns false when earlier attempts never finished and the entry has been set aside instead,
 * with the reason added to [errors].
 */
internal suspend fun SyncRetryCoordinator.beginAttempt(
    entityType: EntityType,
    pending: PendingUpload,
    errors: MutableList<SyncError>,
): Boolean {
    val setAside = beginUpload(entityType, pending) ?: return true
    errors.add(setAside)
    return false
}

internal fun JournalNote.withRepairVersion(version: Long?): JournalNote =
    if (version == null) {
        this
    } else {
        when (this) {
            is JournalNote.Text -> copy(syncVersion = version)
            is JournalNote.Image -> copy(syncVersion = version)
            is JournalNote.Video -> copy(syncVersion = version)
            is JournalNote.Audio -> copy(syncVersion = version)
        }
    }

internal suspend fun SyncRetryCoordinator.recordDeletedNoteConflict(
    pending: PendingUpload,
    note: JournalNote,
    error: Throwable,
): SyncError = handleUploadConflict(EntityType.NOTE, pending, "note", "Deleted note", error, note.syncVersion, note.lastUpdated)
