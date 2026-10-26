package app.logdate.client.data.notes

import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.JournalRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Counts ordinary and retained-edit references before deleting shared media. */
internal class NoteMediaOwnership(
    private val queries: JournalNoteQueries,
    private val journals: JournalRepository,
    private val imageNoteDao: ImageNoteDao,
    private val audioNoteDao: AudioNoteDao,
    private val videoNoteDao: VideoNoteDao,
    private val mediaManager: MediaManager?,
    private val mediaReferences: StoredMediaReferences,
    private val retainedMedia: suspend () -> Set<String>,
) {
    suspend fun referencedPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        val retained = readProtectedMedia() ?: return paths
        val spellings = paths.associateWith(::spellingsOf)
        val referenced = queries.notesReferencingMediaPaths(spellings.values.flatten().toSet()) + retained.flatMap(::spellingsOf)
        return paths.filterTo(mutableSetOf()) { path -> spellings.getValue(path).any(referenced::contains) }
    }

    /** [reference] as written and as LogDate stores it, which differ for a legacy file path. */
    private fun spellingsOf(reference: String): Set<String> = setOf(reference, mediaReferences.storedReference(reference))

    /**
     * Removes the media a deleted note pointed at, if nothing else points at it.
     *
     * Media is stored content-addressed, so two entries holding identical bytes share one file --
     * deleting one entry must not take the other's photo with it. This runs after the note rows
     * are gone, so the count it reads is the number of *remaining* references, under both the
     * spelling the deleted note used and the one LogDate stores.
     *
     * [MediaManager.deleteOwnedMedia] refuses anything LogDate did not store itself, so a note
     * that referenced a photo from the user's own library leaves that photo alone.
     */
    suspend fun deleteIfUnreferenced(contentUri: String?) {
        if (contentUri.isNullOrEmpty()) return
        val manager = mediaManager ?: return

        val retained = readProtectedMedia() ?: return
        if (retained.any { spellingsOf(it).intersect(spellingsOf(contentUri)).isNotEmpty() }) return

        val remainingReferences =
            spellingsOf(contentUri).sumOf { spelling ->
                imageNoteDao.countByContentUri(spelling) +
                    videoNoteDao.countByContentUri(spelling) +
                    audioNoteDao.countByContentUri(spelling)
            }
        if (remainingReferences > 0) return

        runCatching { manager.deleteOwnedMedia(contentUri) }
            .onFailure { Napier.w("Could not remove media for a deleted note", it) }
    }

    private suspend fun readProtectedMedia(): Set<String>? =
        try {
            retainedMedia() + journals.allJournalsObserved.first().mapNotNull { it.coverImageUri }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Napier.w("Could not check media ownership; keeping media", failure)
            null
        }
}
