package app.logdate.feature.library.ui

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.media.IndexedMedia
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A single media item visible in the Library UI.
 *
 * Items may come directly from indexed media or be synthesized from note-backed media until
 * indexing catches up. Matching notes are retained so detail screens can still surface journal
 * and location metadata for whatever the user can currently see in Library.
 */
internal data class LibraryMediaSource(
    val id: Uuid,
    val uri: String,
    val timestamp: Instant,
    val isVideo: Boolean,
    val isAudio: Boolean,
    val durationMs: Long,
    val indexedMedia: IndexedMedia?,
    val matchingNotes: List<JournalNote>,
)

/**
 * Builds the user-visible Library dataset from indexed media plus note-backed image/video notes.
 *
 * Duplicates are collapsed by URI, and indexed records win when both sources describe the same
 * media. Note-only items remain visible so the Library does not regress while older data is still
 * being reconciled into the indexed media store.
 */
internal fun buildLibraryMediaSources(
    indexedMedia: List<IndexedMedia>,
    notes: List<JournalNote>,
): List<LibraryMediaSource> {
    val noteMediaByUri =
        notes
            .mapNotNull { note -> note.asLibraryNoteMedia() }
            .groupBy { it.uri }
            .mapValues { (_, noteMedia) -> noteMedia.sortedByDescending { it.note.creationTimestamp } }

    val indexedSources =
        indexedMedia
            .sortedByDescending { it.timestamp }
            .groupBy { it.uri }
            .values
            .map { mediaForUri ->
                val primary = mediaForUri.first()
                LibraryMediaSource(
                    id = primary.uid,
                    uri = primary.uri,
                    timestamp = primary.timestamp,
                    isVideo = primary is IndexedMedia.Video,
                    isAudio = false,
                    durationMs = (primary as? IndexedMedia.Video)?.duration?.inWholeMilliseconds ?: 0L,
                    indexedMedia = primary,
                    matchingNotes = noteMediaByUri[primary.uri]?.map { it.note }.orEmpty(),
                )
            }

    val noteOnlySources =
        noteMediaByUri
            .filterKeys { uri -> indexedSources.none { it.uri == uri } }
            .values
            .map { noteMedia ->
                val primary = noteMedia.first()
                LibraryMediaSource(
                    id = primary.note.uid,
                    uri = primary.uri,
                    timestamp = primary.note.creationTimestamp,
                    isVideo = primary.isVideo,
                    isAudio = primary.isAudio,
                    durationMs = primary.durationMs,
                    indexedMedia = null,
                    matchingNotes = noteMedia.map { it.note },
                )
            }

    return (indexedSources + noteOnlySources).sortedByDescending { it.timestamp }
}

private data class LibraryNoteMedia(
    val note: JournalNote,
    val uri: String,
    val isVideo: Boolean,
    val isAudio: Boolean = false,
    val durationMs: Long = 0L,
)

private fun JournalNote.asLibraryNoteMedia(): LibraryNoteMedia? =
    when (this) {
        is JournalNote.Image -> {
            LibraryNoteMedia(note = this, uri = mediaRef, isVideo = false)
        }

        is JournalNote.Video -> {
            LibraryNoteMedia(note = this, uri = mediaRef, isVideo = true)
        }

        is JournalNote.Audio -> {
            LibraryNoteMedia(
                note = this,
                uri = mediaRef,
                isVideo = false,
                isAudio = true,
                durationMs = durationMs,
            )
        }

        else -> {
            null
        }
    }
