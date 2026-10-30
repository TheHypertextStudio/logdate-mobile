package app.logdate.client.media.audio.transcription

import app.logdate.client.repository.transcription.TranscriptionRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** Shared scheduling and cancellation ownership for local file recognition. */
internal class FileTranscriptionScheduler(
    private val service: TranscriptionService,
    private val repository: () -> TranscriptionRepository,
    private val scope: CoroutineScope,
    private val fileExists: (String) -> Boolean,
) : TranscriptionManager {
    private data class Work(
        val audioUri: String,
        val mediaRevision: String,
        val job: Job,
        val ready: CompletableDeferred<Unit>,
        var priority: TranscriptionPriority,
        var order: Long,
    )

    private val mutex = Mutex()
    private val jobs = mutableMapOf<Uuid, Work>()
    private var active: Work? = null
    private var sequence = 0L

    override suspend fun enqueueTranscription(
        noteId: Uuid,
        audioUri: String,
    ): Boolean = enqueueTranscription(noteId, audioUri, "")

    override suspend fun enqueueTranscription(
        noteId: Uuid,
        audioUri: String,
        mediaRevision: String,
    ): Boolean = enqueueTranscription(noteId, audioUri, mediaRevision, TranscriptionPriority.RECOVERY)

    override suspend fun enqueueTranscription(
        noteId: Uuid,
        audioUri: String,
        mediaRevision: String,
        priority: TranscriptionPriority,
    ): Boolean =
        mutex.withLock {
            if (!scope.isActive || !fileExists(audioUri)) return@withLock false
            val existing = jobs[noteId]
            if (existing?.job?.isActive == true &&
                existing.audioUri == audioUri &&
                existing.mediaRevision == mediaRevision
            ) {
                if (priority == TranscriptionPriority.FOREGROUND && existing.priority != priority && active !== existing) {
                    existing.priority = priority
                    existing.order = ++sequence
                }
                return@withLock true
            }
            existing?.job?.cancel()
            val ready = CompletableDeferred<Unit>()
            val job = recognitionJob(noteId, audioUri, ready)
            jobs[noteId] = Work(audioUri, mediaRevision, job, ready, priority, ++sequence)
            job.invokeOnCompletion { error ->
                if (error != null &&
                    error !is kotlinx.coroutines.CancellationException
                ) {
                    Napier.e("File transcription failed", error)
                }
            }
            job.start()
            startNext()
            true
        }

    private fun recognitionJob(
        noteId: Uuid,
        audioUri: String,
        ready: CompletableDeferred<Unit>,
    ): Job =
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                ready.await()
                val store = repository()
                var attempts = 0
                TranscriptionPersistenceRetrier().persist {
                    val runner =
                        TranscriptionWorkRunner(
                            store::updateTranscription,
                            service::transcribeAudioFile,
                            store,
                            retryAllowed = ++attempts < 10,
                        )
                    runner.run(noteId, audioUri) != TranscriptionWorkOutcome.Retry
                }
            } finally {
                val owner = coroutineContext[Job]
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (jobs[noteId]?.job === owner) jobs.remove(noteId)
                        if (active?.job === owner) active = null
                        startNext()
                    }
                }
            }
        }

    private fun startNext() {
        if (active != null) return
        val next =
            jobs.values
                .filter { it.job.isActive }
                .minWithOrNull(compareBy({ if (it.priority == TranscriptionPriority.FOREGROUND) 0 else 1 }, { it.order }))
                ?: return
        active = next
        next.ready.complete(Unit)
    }

    override suspend fun cancelTranscription(noteId: Uuid): Boolean =
        mutex.withLock {
            val work = jobs.remove(noteId) ?: return@withLock false
            work.job.cancel()
            true
        }

    override suspend fun cancelAllTranscriptions(): Int =
        mutex.withLock {
            val count = jobs.size
            jobs.values.forEach { it.job.cancel() }
            jobs.clear()
            count
        }
}
