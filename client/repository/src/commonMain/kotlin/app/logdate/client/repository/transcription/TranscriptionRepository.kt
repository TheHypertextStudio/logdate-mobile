package app.logdate.client.repository.transcription

import app.logdate.client.repository.journals.JournalNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.uuid.Uuid

/**
 * Repository for managing audio transcriptions.
 */
interface TranscriptionRepository {
    /** Starts durable missing-transcript recovery with the app's repository lifecycle. */
    fun startAutomaticTranscriptions(scope: CoroutineScope) = Unit

    fun observeAllTranscriptions(): Flow<List<TranscriptionData>> = flowOf(emptyList())

    /** Keeps a downloaded file's transcript bound when its remote URI becomes a local path. */
    suspend fun rebindMediaReference(
        noteId: Uuid,
        previousUri: String,
        mediaUri: String,
    ) = Unit

    /** Captures the audio and transcript revision before background work starts. */
    suspend fun beginTranscription(
        noteId: Uuid,
        audioUri: String,
    ): TranscriptionWorkToken? = TranscriptionWorkToken(noteId, audioUri, getTranscription(noteId)?.revision ?: 0)

    /** Returns true also when obsolete work was safely discarded. */
    suspend fun completeTranscription(
        work: TranscriptionWorkToken,
        document: TranscriptDocument,
        status: TranscriptionStatus,
        errorMessage: String? = null,
    ): Boolean = updateTranscriptDocument(work.noteId, document, status, errorMessage)

    /** Relinquishes active work without creating a document, changing text or queueing a sync. */
    suspend fun deferTranscription(work: TranscriptionWorkToken): Boolean =
        updateTranscription(work.noteId, null, TranscriptionStatus.PENDING)

    /** Records exhausted work without discarding an accepted document or changing its revision. */
    suspend fun failTranscription(
        work: TranscriptionWorkToken,
        errorMessage: String,
    ): Boolean = updateTranscription(work.noteId, null, TranscriptionStatus.FAILED, errorMessage)

    /** Captures a recording before its audio note is saved. */
    suspend fun captureRecordingTranscript(
        noteId: Uuid,
        audioUri: String,
    ): TranscriptionWorkToken = TranscriptionWorkToken(noteId, audioUri, getTranscription(noteId)?.revision ?: 0)

    /** Returns renewed ownership after a write, or null when another transcript or media won. */
    suspend fun persistRecordingTranscript(
        work: TranscriptionWorkToken,
        document: TranscriptDocument,
        status: TranscriptionStatus,
    ): TranscriptionWorkToken? {
        check(updateTranscriptDocument(work.noteId, document, status)) { "Could not persist recording transcript" }
        return work
    }

    /** Applies a downloaded transcript without queueing another sync upload. */
    suspend fun acceptSyncedTranscript(
        noteId: Uuid,
        document: TranscriptDocument,
    ): Boolean =
        updateTranscriptDocument(
            noteId,
            document,
            when (document.status) {
                TranscriptDocumentStatus.FINAL -> TranscriptionStatus.COMPLETED
                TranscriptDocumentStatus.FAILED -> TranscriptionStatus.FAILED
                else -> TranscriptionStatus.IN_PROGRESS
            },
        )

    /**
     * Requests transcription for an audio note.
     *
     * @param noteId The ID of the audio note to transcribe.
     * @return true if the transcription request was successfully queued, false otherwise.
     */
    suspend fun requestTranscription(noteId: Uuid): Boolean

    /**
     * Gets the current transcription for an audio note.
     *
     * @param noteId The ID of the audio note.
     * @return The transcription data, or null if no transcription exists.
     */
    suspend fun getTranscription(noteId: Uuid): TranscriptionData?

    /**
     * Observes the transcription for an audio note.
     *
     * @param noteId The ID of the audio note.
     * @return A Flow emitting the transcription data whenever it changes.
     */
    fun observeTranscription(noteId: Uuid): Flow<TranscriptionData?>

    /**
     * Observes the transcription for an audio note.
     *
     * @param note The audio note.
     * @return A Flow emitting the transcription data whenever it changes.
     */
    fun observeTranscription(note: JournalNote.Audio): Flow<TranscriptionData?> = observeTranscription(note.uid)

    /**
     * Gets all pending transcriptions.
     *
     * @return A list of pending transcription data.
     */
    suspend fun getPendingTranscriptions(): List<TranscriptionData>

    /**
     * Updates the transcription for an audio note.
     *
     * @param noteId The ID of the audio note.
     * @param text The transcribed text.
     * @param status The new status of the transcription.
     * @param errorMessage An optional error message if the transcription failed.
     * @return true if the update was successful, false otherwise.
     */
    suspend fun updateTranscription(
        noteId: Uuid,
        text: String?,
        status: TranscriptionStatus,
        errorMessage: String? = null,
    ): Boolean

    /**
     * Updates the structured transcript document for an audio note. Implementations
     * that have not yet adopted document persistence can safely fall back to the
     * document's plain text.
     */
    suspend fun updateTranscriptDocument(
        noteId: Uuid,
        document: TranscriptDocument,
        status: TranscriptionStatus,
        errorMessage: String? = null,
    ): Boolean =
        updateTranscription(
            noteId = noteId,
            text = document.plainText.takeUnless { it.isBlank() },
            status = status,
            errorMessage = errorMessage,
        )

    /**
     * Deletes the transcription for an audio note.
     *
     * @param noteId The ID of the audio note.
     * @return true if the deletion was successful, false otherwise.
     */
    suspend fun deleteTranscription(noteId: Uuid): Boolean
}

/** Work ownership captured before recognizing a specific saved audio file. */
data class TranscriptionWorkToken(
    val noteId: Uuid,
    val audioUri: String,
    val transcriptRevision: Int,
    val durationMs: Long? = null,
    val transcriptionId: Uuid? = null,
    val documentJson: String? = null,
)
