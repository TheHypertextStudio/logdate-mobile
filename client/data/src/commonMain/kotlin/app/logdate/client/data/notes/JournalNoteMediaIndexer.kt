package app.logdate.client.data.notes

import app.logdate.client.media.MediaManager
import app.logdate.client.media.MediaObject
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.media.IndexedMediaRepository

/**
 * Validates and indexes image and video media attached to newly persisted notes.
 */
internal class JournalNoteMediaIndexer(
    private val mediaManager: MediaManager?,
    private val indexedMediaRepository: IndexedMediaRepository?,
) {
    suspend fun buildPendingMediaIndex(note: JournalNote): PendingMediaIndex? {
        if ((note is JournalNote.Image && UnavailableMediaReferences.isUnavailable(note.mediaRef)) ||
            (note is JournalNote.Video && UnavailableMediaReferences.isUnavailable(note.mediaRef))
        ) {
            return null
        }
        val manager = mediaManager ?: return null
        indexedMediaRepository ?: return null

        return when (note) {
            is JournalNote.Image -> {
                manager
                    .getMedia(note.mediaRef)
                    .also { check(it is MediaObject.Image) { "Expected image media for ${note.mediaRef}" } }
                PendingMediaIndex.Image(
                    uri = note.mediaRef,
                    timestamp = note.creationTimestamp,
                )
            }

            is JournalNote.Video -> {
                val media =
                    manager
                        .getMedia(note.mediaRef)
                        .also { check(it is MediaObject.Video) { "Expected video media for ${note.mediaRef}" } } as MediaObject.Video
                PendingMediaIndex.Video(
                    uri = note.mediaRef,
                    timestamp = note.creationTimestamp,
                    duration = media.duration,
                )
            }

            else -> {
                null
            }
        }
    }

    suspend fun indexPendingMediaIfNeeded(pendingMediaIndex: PendingMediaIndex?) {
        val repository = indexedMediaRepository ?: return
        val pending = pendingMediaIndex ?: return
        if (repository.isIndexed(pending.uri)) return

        when (pending) {
            is PendingMediaIndex.Image -> {
                repository.indexImage(
                    uri = pending.uri,
                    timestamp = pending.timestamp,
                )
            }

            is PendingMediaIndex.Video -> {
                repository.indexVideo(
                    uri = pending.uri,
                    timestamp = pending.timestamp,
                    duration = pending.duration,
                )
            }
        }
    }
}

internal fun JournalNote.Video.withDurationFrom(pendingMediaIndex: PendingMediaIndex?): JournalNote.Video {
    if (durationMs != null) return this
    val duration = (pendingMediaIndex as? PendingMediaIndex.Video)?.duration ?: return this
    return copy(durationMs = duration.inWholeMilliseconds)
}
