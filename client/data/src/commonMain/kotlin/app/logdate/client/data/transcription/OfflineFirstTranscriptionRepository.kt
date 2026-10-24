package app.logdate.client.data.transcription

import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.TranscriptionDao
import app.logdate.client.database.entities.AudioNoteEntity
import app.logdate.client.database.entities.TranscriptionEntity
import app.logdate.client.media.audio.transcription.TranscriptionFailure
import app.logdate.client.media.audio.transcription.TranscriptionManager
import app.logdate.client.media.audio.transcription.TranscriptionPriority
import app.logdate.client.media.storage.UnavailableMediaReferences
import app.logdate.client.repository.transcription.TranscriptDocument
import app.logdate.client.repository.transcription.TranscriptDocumentStatus
import app.logdate.client.repository.transcription.TranscriptionData
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.repository.transcription.TranscriptionStatus
import app.logdate.client.repository.transcription.TranscriptionWorkToken
import app.logdate.client.sync.NoOpSyncManager
import app.logdate.client.sync.SyncDebouncer
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.SyncMetadataService
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private typealias DbTranscriptionStatus = app.logdate.client.database.entities.TranscriptionStatus

/** Owns durable transcript documents and recovery independently of the audio view lifecycle. */
class OfflineFirstTranscriptionRepository(
    private val transcriptionDao: TranscriptionDao,
    private val audioNoteDao: AudioNoteDao,
    private val transcriptionManager: TranscriptionManager,
    private val now: () -> Instant = { Clock.System.now() },
    private val staleAfter: Duration = 10.minutes,
    private val syncMetadataService: SyncMetadataService? = null,
    private val transactionManager: SyncTransactionManager? = null,
    private val syncManagerProvider: () -> SyncManager = { NoOpSyncManager },
    private val syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val recoveryDeletions: TranscriptRecoveryDeletions = TranscriptRecoveryDeletions.None,
) : TranscriptionRepository {
    private val mutex = Mutex()
    private var automaticJob: Job? = null
    private val transcriptSync = SyncDebouncer(syncScope) { syncManagerProvider().syncContent() }

    override fun startAutomaticTranscriptions(scope: CoroutineScope) {
        if (automaticJob?.isActive == true) return
        automaticJob =
            scope.launch {
                val attempted = mutableSetOf<Triple<Uuid, String, Long>>()
                val awaitingOriginal = mutableSetOf<Uuid>()
                var initialSnapshot = true
                audioNoteDao
                    .getAllNotes()
                    .combine(transcriptionDao.observeAllTranscriptions()) { notes, transcripts ->
                        notes to transcripts.associateBy { it.noteId }
                    }.collect { (notes, transcripts) ->
                        for (note in notes) {
                            if (recoveryDeletions.contains(note.uid)) continue
                            if (note.deletedAt != null ||
                                note.contentUri.isBlank() ||
                                note.contentUri.startsWith("http")
                            ) {
                                continue
                            }
                            if (UnavailableMediaReferences.isUnavailable(note.contentUri)) {
                                awaitingOriginal.add(note.uid)
                                continue
                            }
                            val recoveredOriginal = awaitingOriginal.remove(note.uid)
                            val recovery = initialSnapshot || recoveredOriginal
                            val existing = transcripts[note.uid]
                            val mediaRevision = Triple(note.uid, note.contentUri, note.durationMs)
                            val matchesMedia = existing?.matchesMedia(note) != false
                            val final = matchesMedia && existing?.hasValidFinalDocument() == true
                            if (final && existing.documentJson == null) {
                                updateTranscription(note.uid, existing.text.orEmpty(), TranscriptionStatus.COMPLETED)
                            } else if (requiresAutomaticTranscription(existing, matchesMedia, final, recovery)) {
                                if (attempted.add(mediaRevision)) {
                                    val priority = if (recovery) TranscriptionPriority.RECOVERY else TranscriptionPriority.FOREGROUND
                                    requestTranscription(
                                        note.uid,
                                        resumeActive = recovery,
                                        priority = priority,
                                    )
                                }
                            }
                        }
                        initialSnapshot = false
                    }
            }
    }

    private fun requiresAutomaticTranscription(
        existing: TranscriptionEntity?,
        matchesMedia: Boolean,
        final: Boolean,
        recovery: Boolean,
    ): Boolean =
        existing == null ||
            !matchesMedia ||
            (existing.status == DbTranscriptionStatus.COMPLETED && !final) ||
            existing.status == DbTranscriptionStatus.PENDING ||
            (existing.status == DbTranscriptionStatus.FAILED && recovery) ||
            (
                existing.status == DbTranscriptionStatus.IN_PROGRESS &&
                    (recovery || now() - existing.lastUpdated >= staleAfter)
            )

    override fun observeAllTranscriptions(): Flow<List<TranscriptionData>> =
        transcriptionDao.observeAllTranscriptions().map { rows -> rows.map { it.toTranscriptionData() } }

    override suspend fun requestTranscription(noteId: Uuid): Boolean =
        requestTranscription(noteId, resumeActive = false, priority = TranscriptionPriority.FOREGROUND, explicitRequest = true)

    private suspend fun requestTranscription(
        noteId: Uuid,
        resumeActive: Boolean,
        priority: TranscriptionPriority,
        explicitRequest: Boolean = false,
    ): Boolean =
        safely {
            mutate {
                val note = audioNoteDao.getNoteOneOff(noteId)
                if (explicitRequest) {
                    recoveryDeletions.clear(noteId)
                } else if (recoveryDeletions.contains(noteId)) {
                    return@mutate false
                }
                if (note.deletedAt != null || UnavailableMediaReferences.isUnavailable(note.contentUri)) return@mutate false
                val existing = transcriptionDao.getTranscriptionByNoteId(noteId)
                if (existing?.matchesMedia(note) == true && existing.hasValidFinalDocument()) return@mutate true
                if (!resumeActive &&
                    existing?.matchesMedia(note) == true &&
                    existing.status == DbTranscriptionStatus.IN_PROGRESS &&
                    now() - existing.lastUpdated < staleAfter
                ) {
                    return@mutate enqueue(note, priority)
                }
                val pending = prepareWorkRow(note, existing, DbTranscriptionStatus.PENDING)
                if (existing == null) {
                    transcriptionDao.insertTranscription(pending)
                } else if (transcriptionDao.updateTranscription(pending) <= 0) {
                    return@mutate false
                }
                if (pending.documentJson == null && existing?.documentJson != null) transcriptionDao.deleteSegmentsByNoteId(noteId)
                if (enqueue(note, priority)) return@mutate true
                transcriptionDao.updateTranscription(
                    pending.copy(status = DbTranscriptionStatus.FAILED, errorMessage = TranscriptionFailure.SchedulingError.toString()),
                )
                false
            }
        }

    private suspend fun enqueue(
        note: AudioNoteEntity,
        priority: TranscriptionPriority,
    ): Boolean = transcriptionManager.enqueueTranscription(note.uid, note.contentUri, note.durationMs.toString(), priority)

    override suspend fun captureRecordingTranscript(
        noteId: Uuid,
        audioUri: String,
    ): TranscriptionWorkToken =
        mutate {
            val row = transcriptionDao.getTranscriptionByNoteId(noteId)
            TranscriptionWorkToken(noteId, audioUri, row?.revision ?: 0, transcriptionId = row?.id, documentJson = row?.documentJson)
        }

    override suspend fun persistRecordingTranscript(
        work: TranscriptionWorkToken,
        document: TranscriptDocument,
        status: TranscriptionStatus,
    ): TranscriptionWorkToken? =
        mutate {
            val note = audioNoteDao.getNoteOneOff(work.noteId)
            if (note.deletedAt != null || note.contentUri.removePrefix("file://") != work.audioUri.removePrefix("file://")) {
                return@mutate null
            }
            val row = transcriptionDao.getTranscriptionByNoteId(work.noteId)
            // Scheduling may have created a pending row after the recording started, before its first result.
            val unclaimed =
                work.transcriptionId == null &&
                    work.transcriptRevision == 0 &&
                    row?.documentJson == null &&
                    (row?.revision ?: 0) == 0
            if (!unclaimed &&
                (
                    row == null ||
                        row.id != work.transcriptionId ||
                        row.revision != work.transcriptRevision ||
                        row.documentJson != work.documentJson
                )
            ) {
                return@mutate null
            }
            check(persist(work.noteId, document, status, null, markForSync = true)) { "Could not persist recording transcript" }
            val saved = checkNotNull(transcriptionDao.getTranscriptionByNoteId(work.noteId))
            work.copy(transcriptRevision = saved.revision, transcriptionId = saved.id, documentJson = saved.documentJson)
        }

    override suspend fun beginTranscription(
        noteId: Uuid,
        audioUri: String,
    ): TranscriptionWorkToken? =
        mutate {
            if (recoveryDeletions.contains(noteId)) return@mutate null
            val note =
                try {
                    audioNoteDao.getNoteOneOff(noteId)
                } catch (_: NoSuchElementException) {
                    return@mutate null
                }
            if (note.deletedAt != null ||
                note.contentUri != audioUri ||
                UnavailableMediaReferences.isUnavailable(audioUri)
            ) {
                return@mutate null
            }
            val existing = transcriptionDao.getTranscriptionByNoteId(noteId)
            if (existing?.matchesMedia(note) == true && existing.hasValidFinalDocument()) return@mutate null
            val row = prepareWorkRow(note, existing, DbTranscriptionStatus.IN_PROGRESS)
            if (existing == null) {
                transcriptionDao.insertTranscription(row)
            } else {
                check(transcriptionDao.updateTranscription(row) > 0) { "Could not begin transcript work" }
            }
            if (row.documentJson == null && existing?.documentJson != null) transcriptionDao.deleteSegmentsByNoteId(noteId)
            TranscriptionWorkToken(noteId, audioUri, row.revision, note.durationMs, row.id, row.documentJson)
        }

    override suspend fun completeTranscription(
        work: TranscriptionWorkToken,
        document: TranscriptDocument,
        status: TranscriptionStatus,
        errorMessage: String?,
    ): Boolean =
        safely {
            mutate {
                val note =
                    try {
                        audioNoteDao.getNoteOneOff(work.noteId)
                    } catch (_: NoSuchElementException) {
                        return@mutate true
                    }
                val existing = transcriptionDao.getTranscriptionByNoteId(work.noteId)
                if (note.deletedAt != null ||
                    note.contentUri != work.audioUri ||
                    (
                        work.durationMs != null &&
                            note.durationMs != work.durationMs
                    ) ||
                    existing?.revision != work.transcriptRevision ||
                    existing.id != work.transcriptionId ||
                    existing.documentJson != work.documentJson ||
                    existing.hasValidFinalDocument()
                ) {
                    return@mutate true
                }
                persist(work.noteId, document, status, errorMessage, markForSync = true)
            }
        }

    override suspend fun getTranscription(noteId: Uuid): TranscriptionData? =
        transcriptionDao.getTranscriptionByNoteId(noteId)?.toTranscriptionData()

    override fun observeTranscription(noteId: Uuid): Flow<TranscriptionData?> =
        transcriptionDao.observeTranscriptionByNoteId(noteId).map {
            it?.toTranscriptionData()
        }

    override suspend fun getPendingTranscriptions(): List<TranscriptionData> =
        transcriptionDao.getTranscriptionsByStatus(DbTranscriptionStatus.PENDING).map {
            it.toTranscriptionData()
        }

    override suspend fun updateTranscription(
        noteId: Uuid,
        text: String?,
        status: TranscriptionStatus,
        errorMessage: String?,
    ): Boolean {
        val document =
            text?.let { TranscriptDocument.fromPlainText(it, status.toDocumentStatus()) }
                ?: if (status == TranscriptionStatus.COMPLETED) TranscriptDocument(status = TranscriptDocumentStatus.FINAL) else null
        return safely {
            mutate {
                persist(
                    noteId,
                    document,
                    status,
                    errorMessage,
                    markForSync = document != null,
                )
            }
        }
    }

    override suspend fun updateTranscriptDocument(
        noteId: Uuid,
        document: TranscriptDocument,
        status: TranscriptionStatus,
        errorMessage: String?,
    ): Boolean = safely { mutate { persist(noteId, document, status, errorMessage, markForSync = true) } }

    override suspend fun acceptSyncedTranscript(
        noteId: Uuid,
        document: TranscriptDocument,
    ): Boolean =
        safely {
            mutate {
                persist(
                    noteId,
                    document,
                    when (document.status) {
                        TranscriptDocumentStatus.FINAL -> TranscriptionStatus.COMPLETED
                        TranscriptDocumentStatus.FAILED -> TranscriptionStatus.FAILED
                        else -> TranscriptionStatus.IN_PROGRESS
                    },
                    null,
                    markForSync = false,
                )
            }
        }

    private suspend fun persist(
        noteId: Uuid,
        document: TranscriptDocument?,
        status: TranscriptionStatus,
        errorMessage: String?,
        markForSync: Boolean,
    ): Boolean {
        val note = audioNoteDao.getNoteOneOff(noteId)
        if (note.deletedAt != null) return false
        val existing = transcriptionDao.getTranscriptionByNoteId(noteId)
        val accepted = document?.let { if (markForSync) it.copy(revision = maxOf(it.revision, (existing?.revision ?: 0) + 1)) else it }
        val row =
            (
                existing
                    ?: TranscriptionEntity(noteId = noteId, text = null, status = status.toDbStatus(), created = now(), lastUpdated = now())
            ).withDocument(accepted, status.toDbStatus(), errorMessage, now())
                .copy(mediaUri = note.contentUri, mediaDurationMs = note.durationMs)
        if (existing == null) {
            transcriptionDao.insertTranscription(row)
        } else if (transcriptionDao.updateTranscription(row) <= 0) {
            return false
        }
        if (accepted != null) transcriptionDao.replaceSegmentsForNote(noteId, accepted.toSegmentEntities(noteId))
        if (markForSync && accepted != null) {
            syncMetadataService?.enqueueTranscriptMutation(noteId.toString())
            transcriptSync.trigger()
        }
        return true
    }

    override suspend fun rebindMediaReference(
        noteId: Uuid,
        previousUri: String,
        mediaUri: String,
    ) {
        mutate {
            val row = transcriptionDao.getTranscriptionByNoteId(noteId) ?: return@mutate
            if (row.mediaUri == null || row.mediaUri == previousUri) {
                transcriptionDao.updateTranscription(row.copy(mediaUri = mediaUri))
            }
        }
    }

    private fun prepareWorkRow(
        note: AudioNoteEntity,
        existing: TranscriptionEntity?,
        status: DbTranscriptionStatus,
    ): TranscriptionEntity {
        val base = existing ?: TranscriptionEntity(noteId = note.uid, text = null, status = status, created = now(), lastUpdated = now())
        val invalid = !base.matchesMedia(note) || (base.status == DbTranscriptionStatus.COMPLETED && !base.hasValidFinalDocument())
        val row =
            if (invalid) {
                base.copy(
                    text = null,
                    documentJson = null,
                    language = null,
                    source = null,
                    modelId = null,
                    revision = base.revision + 1,
                    isCloudEnhanced = false,
                    speakerCount = 0,
                )
            } else {
                base
            }
        return row.copy(
            status = status,
            mediaUri = note.contentUri,
            mediaDurationMs = note.durationMs,
            errorMessage = null,
            lastUpdated = now(),
        )
    }

    override suspend fun deleteTranscription(noteId: Uuid): Boolean =
        safely {
            mutate {
                transcriptionManager.cancelTranscription(noteId)
                recoveryDeletions.record(noteId)
                transcriptionDao.deleteSegmentsByNoteId(noteId)
                transcriptionDao.deleteTranscriptionByNoteId(noteId) > 0
            }
        }

    private suspend fun <T> mutate(block: suspend () -> T): T = transactionManager?.withTransaction(block) ?: mutex.withLock { block() }

    private suspend fun safely(block: suspend () -> Boolean): Boolean =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Transcript persistence or scheduling failed", e)
            false
        }
}
