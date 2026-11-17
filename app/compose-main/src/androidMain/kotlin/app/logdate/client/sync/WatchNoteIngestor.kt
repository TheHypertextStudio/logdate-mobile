package app.logdate.client.sync

import app.logdate.client.repository.journals.CaptureDevice
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.journals.withCapturedOn
import app.logdate.client.sync.datalayer.AudioFileDigest
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import kotlin.time.Instant
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

    /**
     * The length and SHA-256 of the file at [audioPath], measured when it was written, or null when
     * there is no file or it cannot be read.
     */
    suspend fun storedDigest(noteId: Uuid): AudioFileDigest?

    suspend fun stashMetadata(
        noteId: Uuid,
        data: Map<String, String>,
    )

    suspend fun stashedMetadata(noteId: Uuid): Map<String, String>?

    /** Removes what waited for the note to be stored: its stashed metadata and its audio's recorded digest. */
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
}

/** Tells the watch a note is stored on the phone, so the watch can stop retrying it. */
fun interface WatchNoteAcknowledger {
    /** [lastUpdated] is the version of the note the phone stored. */
    suspend fun acknowledge(
        noteId: Uuid,
        lastUpdated: Instant,
    )

    suspend fun acknowledge(
        noteId: Uuid,
        lastUpdated: Instant,
        transferId: String?,
    ) = acknowledge(noteId, lastUpdated)
}

fun interface WatchNoteNotifier {
    suspend fun noteReceived(note: JournalNote)
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
 *
 * A note from a watch build that does not record its capture device is stored as captured on the
 * watch, because everything this path receives was captured there.
 */
class WatchNoteIngestor(
    private val notesRepository: JournalNotesRepository,
    private val audioStore: WatchAudioStore,
    private val acknowledger: WatchNoteAcknowledger,
    private val notifier: WatchNoteNotifier,
    private val noteDataMapper: NoteDataMapper = NoteDataMapper(),
    /** Verifies both note identity and the bytes at the repository's resolved media reference. */
    private val proveExistingAudio: (suspend (JournalNote, Map<String, String>?) -> Boolean)? = null,
) {
    private class NoteLock {
        val mutex = Mutex()
        var holders = 0
    }

    private val noteLocks = HashMap<Uuid, NoteLock>()

    /**
     * How many notes currently have a lock, which is zero once nothing is being ingested.
     *
     * Read by WatchNoteIngestorTest to prove a note's lock is released when its ingestion ends.
     */
    @Suppress("unused")
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
        val note = noteDataMapper.fromDataMap(data).capturedOnWatchUnlessNamed()
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
        val existing = notesRepository.getNoteById(noteId)
        if (existing != null) {
            val metadata = audioStore.stashedMetadata(noteId)
            if (metadata != null) {
                acknowledgeExisting(noteDataMapper.fromDataMap(metadata).capturedOnWatchUnlessNamed() as? JournalNote.Audio, metadata)
            } else if (proveExistingAudio == null || proveExistingAudio.invoke(existing, null)) {
                acknowledger.acknowledge(noteId, existing.lastUpdated)
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
        val existing = notesRepository.getNoteById(note.uid)
        if (existing != null) {
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
        val proof = proveExistingAudio
        if (proof != null) {
            if (!proof(existing, data)) return false
            acknowledger.acknowledge(note.uid, existing.lastUpdated, data[WearAudioRequestPaths.KEY_TRANSFER_ID])
            return true
        }
        if (existing.copy(
                mediaRef = note.mediaRef,
                syncVersion = note.syncVersion,
                lastUpdated = note.lastUpdated,
                capturedOn = note.capturedOn,
            ) != note
        ) {
            return false
        }
        val expected = NoteDataMapper.audioDigest(data)
        if (expected != null && audioStore.storedDigest(note.uid) != expected) return false
        val legacyDigest = data[WearAudioRequestPaths.KEY_AUDIO_DIGEST]
        if (legacyDigest != null && audioStore.storedDigest(note.uid)?.sha256Hex != legacyDigest) return false
        acknowledger.acknowledge(note.uid, existing.lastUpdated, data[WearAudioRequestPaths.KEY_TRANSFER_ID])
        return true
    }

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
        if (!audioMatchesWhatTheWatchSent(noteId, data)) return

        val note = watchNote.copy(mediaRef = audioStore.audioPath(noteId), capturedOn = watchNote.capturedOn ?: CaptureDevice.WATCH)
        notesRepository.create(note)
        audioStore.clearMetadata(noteId)
        acknowledger.acknowledge(noteId, note.lastUpdated, data[WearAudioRequestPaths.KEY_TRANSFER_ID])
        notifier.noteReceived(note)
    }

    /**
     * A watch that sends a digest lets the phone tell a complete file from one cut short on the way.
     * A file that does not match is removed with its metadata, so the note is neither stored nor
     * acknowledged and the watch's next resend replaces both. A watch that sends no digest is trusted,
     * as every watch was before digests existed.
     */
    private suspend fun audioMatchesWhatTheWatchSent(
        noteId: Uuid,
        data: Map<String, String>,
    ): Boolean {
        val expected = NoteDataMapper.audioDigest(data)
        if (expected == null) {
            val legacyDigest = data[WearAudioRequestPaths.KEY_AUDIO_DIGEST] ?: return true
            return audioStore.storedDigest(noteId)?.sha256Hex == legacyDigest
        }
        val received = audioStore.storedDigest(noteId)
        if (received == expected) return true
        Napier.w("Audio for watch note $noteId is $received, but the watch sent $expected; waiting for it to be sent again")
        audioStore.discard(noteId)
        return false
    }

    private fun JournalNote.capturedOnWatchUnlessNamed(): JournalNote =
        if (capturedOn != null) this else withCapturedOn(CaptureDevice.WATCH)
}
