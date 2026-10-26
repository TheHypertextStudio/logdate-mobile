package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import kotlin.uuid.Uuid

/**
 * Where a watch audio note's file and not-yet-stored metadata live on the phone.
 *
 * Metadata and audio bytes reach the phone over separate Data Layer channels in either order, so
 * whichever arrives first has to survive until the other one does.
 */
interface WatchAudioStore {
    fun audioPath(noteId: Uuid): String

    fun hasAudio(noteId: Uuid): Boolean

    /** Writes the complete file at [audioPath], or returns false and leaves nothing behind. */
    suspend fun writeAudio(
        noteId: Uuid,
        source: InputStream,
    ): Boolean

    suspend fun stashMetadata(
        noteId: Uuid,
        data: Map<String, String>,
    )

    suspend fun stashedMetadata(noteId: Uuid): Map<String, String>?

    suspend fun clearMetadata(noteId: Uuid)

    /** Removes the file and any stashed metadata. A deletion marker set by [markDeleted] stays. */
    suspend fun discard(noteId: Uuid)

    /**
     * Remembers that the watch deleted this note. Data items and channels are not ordered against each
     * other, so a delete can arrive before the note it removes; the marker stops the late note and
     * audio from being stored after the fact.
     */
    suspend fun markDeleted(noteId: Uuid)

    suspend fun isDeleted(noteId: Uuid): Boolean

    /** Checks the complete accepted bytes before acknowledging a versioned transfer. */
    suspend fun matchesAudioDigest(
        noteId: Uuid,
        expectedDigest: String,
    ): Boolean = false
}

/** Tells the watch a note is stored on the phone, so the watch can stop retrying it. */
fun interface WatchNoteAcknowledger {
    suspend fun acknowledge(noteId: Uuid)

    suspend fun acknowledge(
        noteId: Uuid,
        transferId: String?,
    ) = acknowledge(noteId)
}

fun interface WatchNoteNotifier {
    fun noteReceived(note: JournalNote)
}

/**
 * Turns notes and audio bytes arriving from the watch into phone notes queued for cloud backup.
 *
 * A watch audio note is stored only once its file is on the phone, so the phone never lists a note
 * it cannot play and never uploads a note whose bytes are missing. Delivery is at-least-once: a
 * redelivered note or file is acknowledged again and never stored twice, and a note the watch has
 * deleted is never stored, even when its data arrives after the delete.
 *
 * Work is serialized per note, not globally, so a long audio transfer for one note does not hold up
 * the others.
 */
class WatchNoteIngestor(
    private val notesRepository: JournalNotesRepository,
    private val audioStore: WatchAudioStore,
    private val acknowledger: WatchNoteAcknowledger,
    private val notifier: WatchNoteNotifier,
    private val noteDataMapper: NoteDataMapper = NoteDataMapper(),
) {
    private class NoteLock {
        val mutex = Mutex()
        var holders = 0
    }

    private val noteLocks = HashMap<Uuid, NoteLock>()

    /** How many notes currently have a lock, which is zero once nothing is being ingested. */
    val activeNoteLockCount: Int get() = synchronized(noteLocks) { noteLocks.size }

    /** A note's lock lives only while someone holds or waits for it, so the map never outgrows the work in flight. */
    private suspend fun <T> withNoteLock(
        noteId: Uuid,
        block: suspend () -> T,
    ): T {
        val lock = synchronized(noteLocks) { noteLocks.getOrPut(noteId) { NoteLock() }.also { it.holders++ } }
        try {
            return lock.mutex.withLock { block() }
        } finally {
            synchronized(noteLocks) { if (--lock.holders == 0) noteLocks.remove(noteId) }
        }
    }

    /** @throws IllegalArgumentException if [data] carries no decodable note. */
    suspend fun onNoteMetadata(data: Map<String, String>) {
        val note = noteDataMapper.fromDataMap(data)
        withNoteLock(note.uid) {
            when {
                audioStore.isDeleted(note.uid) -> Napier.d("Ignoring note ${note.uid}: the watch deleted it")
                note is JournalNote.Audio -> receiveAudioMetadata(note, data)
                else -> receiveNote(note)
            }
        }
    }

    suspend fun onAudioBytes(
        noteId: Uuid,
        source: InputStream,
    ) = withNoteLock(noteId) {
        if (audioStore.isDeleted(noteId)) {
            Napier.d("Dropping audio for note $noteId: the watch deleted it")
            return@withNoteLock
        }
        if (notesRepository.getNoteById(noteId) != null) {
            // A different edit under this ID needs review; receiving bytes cannot acknowledge it.
            val metadata = audioStore.stashedMetadata(noteId)
            if (metadata == null) {
                acknowledger.acknowledge(noteId)
            } else {
                acknowledgeExisting(noteDataMapper.fromDataMap(metadata) as? JournalNote.Audio, metadata)
            }
            return@withNoteLock
        }
        if (!audioStore.writeAudio(noteId, source)) {
            Napier.w("Could not store audio bytes from the watch for note $noteId")
            return@withNoteLock
        }
        storeIfComplete(noteId)
    }

    suspend fun onNoteDeleted(noteId: Uuid) =
        withNoteLock(noteId) {
            audioStore.markDeleted(noteId)
            if (notesRepository.getNoteById(noteId) != null) {
                notesRepository.removeById(noteId)
            }
            audioStore.discard(noteId)
        }

    private suspend fun receiveAudioMetadata(
        note: JournalNote.Audio,
        data: Map<String, String>,
    ) {
        if (notesRepository.getNoteById(note.uid) != null) {
            if (!acknowledgeExisting(note, data)) audioStore.stashMetadata(note.uid, data)
            return
        }
        audioStore.stashMetadata(note.uid, data)
        storeIfComplete(note.uid)
    }

    private suspend fun acknowledgeExisting(
        note: JournalNote.Audio?,
        data: Map<String, String>,
    ): Boolean {
        if (note == null) return false
        val existing = notesRepository.getNoteById(note.uid) as? JournalNote.Audio ?: return false
        if (existing.copy(mediaRef = note.mediaRef, syncVersion = note.syncVersion, lastUpdated = note.lastUpdated) != note) {
            Napier.w("An incoming watch audio edit differs from the stored note; retaining it for review")
            return false
        }
        if (!matchesDigest(note.uid, data)) return false
        acknowledger.acknowledge(note.uid, data[WearAudioRequestPaths.KEY_TRANSFER_ID])
        return true
    }

    private suspend fun matchesDigest(
        noteId: Uuid,
        data: Map<String, String>,
    ): Boolean = data[WearAudioRequestPaths.KEY_AUDIO_DIGEST]?.let { audioStore.matchesAudioDigest(noteId, it) } ?: true

    private suspend fun receiveNote(note: JournalNote) {
        if (notesRepository.getNoteById(note.uid) != null) {
            (notesRepository as? SyncableJournalNotesRepository)?.createFromSync(note)
            return
        }
        notesRepository.create(note)
        notifier.noteReceived(note)
    }

    private suspend fun storeIfComplete(noteId: Uuid) {
        if (!audioStore.hasAudio(noteId)) return
        val data = audioStore.stashedMetadata(noteId) ?: return
        val watchNote = noteDataMapper.fromDataMap(data) as? JournalNote.Audio ?: return

        if (!matchesDigest(noteId, data)) {
            Napier.w("Watch audio bytes do not match their metadata; waiting for the matching transfer")
            return
        }
        val note = watchNote.copy(mediaRef = audioStore.audioPath(noteId))
        notesRepository.create(note)
        audioStore.clearMetadata(noteId)
        acknowledger.acknowledge(noteId, data[WearAudioRequestPaths.KEY_TRANSFER_ID])
        notifier.noteReceived(note)
    }
}
