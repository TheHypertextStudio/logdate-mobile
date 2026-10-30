package app.logdate.client.media.audio.transcription

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.logdate.client.media.audio.transcription.AndroidTranscriptionManager.Companion.KEY_AUDIO_URI
import app.logdate.client.media.audio.transcription.AndroidTranscriptionManager.Companion.KEY_NOTE_ID
import app.logdate.client.media.storage.MediaFileResolver
import app.logdate.client.repository.transcription.TranscriptionRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * Worker that handles audio transcription in the background.
 *
 * This worker:
 * 1. Retrieves the audio file URI from input data
 * 2. Updates the transcription status to IN_PROGRESS
 * 3. Calls the TranscriptionService to transcribe the audio
 * 4. Updates the transcription with the result
 */
class TranscriptionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {
    // Inject dependencies from Koin
    private val transcriptionRepository: TranscriptionRepository by inject()
    private val transcriptionService: TranscriptionService by inject()
    private val mediaFiles: MediaFileResolver by inject()

    companion object {
        const val TAG = "TranscriptionWorker"
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                // Get input data
                val noteIdString =
                    inputData.getString(KEY_NOTE_ID)
                        ?: return@withContext Result.failure()

                val audioUri =
                    inputData.getString(KEY_AUDIO_URI)
                        ?: return@withContext Result.failure()

                val noteId =
                    try {
                        Uuid.parse(noteIdString)
                    } catch (e: Exception) {
                        Napier.e("Invalid note ID: $noteIdString", e)
                        return@withContext Result.failure()
                    }

                Napier.d("Starting transcription for note $noteId, URI: $audioUri")
                val outcome =
                    runAndroidTranscriptionWork(
                        noteId,
                        audioUri,
                        transcriptionRepository,
                        transcriptionService,
                        mediaFiles,
                        runAttemptCount,
                    )
                when (outcome) {
                    TranscriptionWorkOutcome.Success -> Result.success()
                    TranscriptionWorkOutcome.Retry -> Result.retry()
                    TranscriptionWorkOutcome.Failure -> Result.failure()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Error in TranscriptionWorker", e)
                Result.failure()
            }
        }
}

internal suspend fun runAndroidTranscriptionWork(
    noteId: Uuid,
    audioUri: String,
    repository: TranscriptionRepository,
    service: TranscriptionService,
    mediaFiles: MediaFileResolver,
    attempt: Int = 0,
): TranscriptionWorkOutcome {
    val runner =
        TranscriptionWorkRunner(
            update = repository::updateTranscription,
            transcribe = { stored -> service.transcribeAudioFile(mediaFiles.filePath(stored) ?: stored) },
            repository = repository,
            retryAllowed = attempt < 2,
        )
    return runner.run(noteId, audioUri)
}
