package app.logdate.client.data.notes

import app.logdate.client.data.journals.LocalDeletionType
import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.dao.MediaCaptionDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.database.entities.GenericNoteData
import app.logdate.client.database.entities.MediaCaptionEntity
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class JournalNoteRecoveryPersistence(
    private val textNoteDao: TextNoteDao,
    private val imageNoteDao: ImageNoteDao,
    private val audioNoteDao: AudioNoteDao,
    private val videoNoteDao: VideoNoteDao,
    private val mediaCaptionDao: MediaCaptionDao,
    private val transcriptionRepository: TranscriptionRepository?,
    private val transactionManager: SyncTransactionManager,
    private val syncMetadataService: SyncMetadataService,
    private val mediaReferences: StoredMediaReferences,
    private val getNoteById: suspend (Uuid) -> JournalNote?,
    private val currentScope: suspend () -> JournalMergeScope?,
    private val changed: () -> Unit,
    private val indexAvailableMedia: suspend (JournalNote) -> Unit,
    private val receipts: app.logdate.client.data.journals.ArchiveRecoveryReceipts,
    private val notePlaceResolver: NotePlaceResolver,
) {
    suspend fun compareAndSet(
        expected: JournalNote?,
        replacement: JournalNote,
        scope: JournalMergeScope?,
        recoveryId: String?,
        retainedSource: Boolean = false,
        sourceId: Uuid = replacement.uid,
    ): Boolean {
        require(expected == null || expected.uid == replacement.uid)
        val saved =
            transactionManager.withTransaction {
                check(currentScope() == scope) { "Note recovery account changed" }
                if (scope != null &&
                    recoveryId != null &&
                    receipts.contains(scope, "NOTE", recoveryId, replacement.uid.toString())
                ) {
                    return@withTransaction false
                }
                if (retainedSource &&
                    scope != null &&
                    (
                        receipts.containsLocalDeletion(scope, LocalDeletionType.NOTE, sourceId.toString()) ||
                            receipts.containsLocalDeletion(scope, LocalDeletionType.NOTE, replacement.uid.toString())
                    )
                ) {
                    recordDecision(scope, recoveryId, replacement.uid)
                    return@withTransaction false
                }
                val current = getNoteById(replacement.uid)
                if (current != expected || (current != null && current.type != replacement.type) || expected == replacement) {
                    recordDecision(scope, recoveryId, replacement.uid)
                    return@withTransaction current == expected && expected == replacement
                }
                val operation = if (expected == null) PendingOperation.CREATE else PendingOperation.UPDATE
                val uploadScope = scope?.let { UploadScope(it.ownerId, it.serverOrigin) }
                if (!syncMetadataService.enqueueRestoreMutation(
                        replacement.uid.toString(),
                        EntityType.NOTE,
                        operation,
                        uploadScope,
                    )
                ) {
                    recordDecision(scope, recoveryId, replacement.uid)
                    return@withTransaction false
                }
                if (!retainedSource && expected == null && scope != null) {
                    receipts.clearLocalDeletion(scope, LocalDeletionType.NOTE, replacement.uid.toString())
                }
                val note = replacement.withStoredMediaRef(mediaReferences)
                if (expected == null) {
                    createFromSync(note, preserveSourcePlace = true)
                } else {
                    updateForRestore(expected, note, retainedSource)
                }
                indexAvailableMedia(note)
                recordDecision(scope, recoveryId, note.uid)
                true
            }
        if (saved) changed()
        return saved
    }

    private suspend fun updateForRestore(
        expected: JournalNote,
        note: JournalNote,
        retainedSource: Boolean,
    ) {
        val sourceLocation = if (expected.location == note.location) storedNoteRecord(note) else null
        val linkedPlaceId =
            if (sourceLocation != null) {
                sourceLocation.placeId
            } else {
                note.location
                    ?.place
                    ?.id
                    ?.let { notePlaceResolver.get(it)?.id }
            }
        val count =
            when (note) {
                is JournalNote.Handwriting -> {
                    textNoteDao.updateForRestore(
                        note.toEntity(linkedPlaceId, preserveSourcePlace = true, sourceLocation = sourceLocation),
                    )
                }

                is JournalNote.Text -> {
                    textNoteDao.updateForRestore(
                        note.toEntity(linkedPlaceId, preserveSourcePlace = true, sourceLocation = sourceLocation),
                    )
                }

                is JournalNote.Image -> {
                    imageNoteDao.updateForRestore(
                        note.toEntity(linkedPlaceId, preserveSourcePlace = true, sourceLocation = sourceLocation),
                    )
                }

                is JournalNote.Audio -> {
                    audioNoteDao.updateForRestore(
                        note.toEntity(linkedPlaceId, preserveSourcePlace = true, sourceLocation = sourceLocation),
                    )
                }

                is JournalNote.Video -> {
                    videoNoteDao.updateForRestore(
                        note.toEntity(linkedPlaceId, preserveSourcePlace = true, sourceLocation = sourceLocation),
                    )
                }
            }
        check(count == 1) { "Note changed during archive recovery" }
        when (note) {
            is JournalNote.Text, is JournalNote.Handwriting -> {
                Unit
            }

            is JournalNote.Image -> {
                mediaCaptionDao.upsertCaption(MediaCaptionEntity(note.uid, note.caption))
            }

            is JournalNote.Video -> {
                mediaCaptionDao.upsertCaption(MediaCaptionEntity(note.uid, note.caption))
            }

            is JournalNote.Audio -> {
                val previous = (expected as JournalNote.Audio).mediaRef
                if (previous != note.mediaRef) transcriptionRepository?.rebindMediaReference(note.uid, previous, note.mediaRef)
                mediaCaptionDao.upsertCaption(MediaCaptionEntity(note.uid, note.caption))
                if (!retainedSource || (expected as JournalNote.Audio).transcript != note.transcript) {
                    note.persistTranscript(transcriptionRepository)
                }
            }
        }
    }

    private suspend fun recordDecision(
        scope: JournalMergeScope?,
        recoveryId: String?,
        id: Uuid,
    ) {
        if (scope != null && recoveryId != null) receipts.record(scope, "NOTE", recoveryId, id.toString())
        check(currentScope() == scope) { "Note recovery account changed" }
    }

    private suspend fun storedNoteRecord(note: JournalNote): GenericNoteData? =
        when (note) {
            is JournalNote.Text, is JournalNote.Handwriting -> textNoteDao.findNoteOneOff(note.uid)
            is JournalNote.Image -> imageNoteDao.findNoteOneOff(note.uid)
            is JournalNote.Video -> videoNoteDao.findNoteOneOff(note.uid)
            is JournalNote.Audio -> audioNoteDao.findNoteOneOff(note.uid)
        }

    suspend fun createFromSync(
        note: JournalNote,
        preserveSourcePlace: Boolean = false,
    ) {
        transactionManager.withTransaction {
            val scope = currentScope()
            val storedNote = note.withStoredMediaRef(mediaReferences)
            val linkedPlaceId =
                storedNote.location
                    ?.place
                    ?.id
                    ?.let { notePlaceResolver.get(it)?.id }
            when (storedNote) {
                is JournalNote.Handwriting -> {
                    textNoteDao.addNote(storedNote.toEntity(linkedPlaceId, preserveSourcePlace))
                }

                is JournalNote.Text -> {
                    textNoteDao.addNote(storedNote.toEntity(linkedPlaceId, preserveSourcePlace))
                }

                is JournalNote.Image -> {
                    imageNoteDao.addNote(storedNote.toEntity(linkedPlaceId, preserveSourcePlace))
                    mediaCaptionDao.upsertCaption(MediaCaptionEntity(storedNote.uid, storedNote.caption))
                }

                is JournalNote.Audio -> {
                    audioNoteDao.addNote(storedNote.toEntity(linkedPlaceId, preserveSourcePlace))
                    mediaCaptionDao.upsertCaption(MediaCaptionEntity(storedNote.uid, storedNote.caption))
                    storedNote.persistTranscript(transcriptionRepository)
                }

                is JournalNote.Video -> {
                    videoNoteDao.addNote(storedNote.toEntity(linkedPlaceId, preserveSourcePlace))
                    mediaCaptionDao.upsertCaption(MediaCaptionEntity(storedNote.uid, storedNote.caption))
                }
            }
            if (scope != null) receipts.clearLocalDeletion(scope, LocalDeletionType.NOTE, storedNote.uid.toString())
            check(currentScope() == scope) { "Note sync account changed" }
        }
    }

    suspend fun deleteFromSync(noteId: Uuid) {
        transactionManager.withTransaction {
            val scope = currentScope()
            if (scope != null) receipts.recordLocalDeletion(scope, LocalDeletionType.NOTE, noteId.toString())
            textNoteDao.removeNote(noteId)
            imageNoteDao.removeNote(noteId)
            transcriptionRepository?.deleteTranscription(noteId)
            audioNoteDao.removeNote(noteId)
            videoNoteDao.removeNote(noteId)
            mediaCaptionDao.deleteCaption(noteId)
            check(currentScope() == scope) { "Note deletion account changed" }
        }
    }

    suspend fun updateSyncMetadata(
        note: JournalNote,
        syncVersion: Long,
        syncedAt: Instant,
    ) {
        when (note) {
            is JournalNote.Text, is JournalNote.Handwriting -> textNoteDao.updateSyncMetadata(note.uid, syncVersion, syncedAt)
            is JournalNote.Image -> imageNoteDao.updateSyncMetadata(note.uid, syncVersion, syncedAt)
            is JournalNote.Audio -> audioNoteDao.updateSyncMetadata(note.uid, syncVersion, syncedAt)
            is JournalNote.Video -> videoNoteDao.updateSyncMetadata(note.uid, syncVersion, syncedAt)
        }
    }

    suspend fun updateMediaRef(
        noteId: Uuid,
        mediaRef: String,
    ) {
        val storedRef = mediaReferences.storedReference(mediaRef)
        transactionManager.withTransaction {
            val previous = (getNoteById(noteId) as? JournalNote.Audio)?.mediaRef
            if (previous != null) transcriptionRepository?.rebindMediaReference(noteId, previous, storedRef)
            imageNoteDao.updateContentUri(noteId, storedRef)
            audioNoteDao.updateContentUri(noteId, storedRef)
            videoNoteDao.updateContentUri(noteId, storedRef)
        }
    }
}
