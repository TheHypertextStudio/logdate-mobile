package app.logdate.client.data.notes

import app.logdate.client.data.journals.ArchiveRecoveryReceipts
import app.logdate.client.data.journals.LocalDeletionType
import app.logdate.client.database.dao.AudioNoteDao
import app.logdate.client.database.dao.ImageNoteDao
import app.logdate.client.database.dao.MediaCaptionDao
import app.logdate.client.database.dao.TextNoteDao
import app.logdate.client.database.dao.VideoNoteDao
import app.logdate.client.database.dao.journals.JournalContentDao
import app.logdate.client.database.entities.MediaCaptionEntity
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.media.MediaManager
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.media.storage.MediaReclamationJournal
import app.logdate.client.media.storage.MediaReference
import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.ExportableJournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.NoteCounts
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.media.IndexedMediaRepository
import app.logdate.client.repository.transcription.TranscriptionRepository
import app.logdate.client.sync.NoOpSyncManager
import app.logdate.client.sync.SyncDebouncer
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A repository for journal notes that stores data locally.
 */
class OfflineFirstJournalNotesRepository(
    private val textNoteDao: TextNoteDao,
    private val imageNoteDao: ImageNoteDao,
    private val audioNoteDao: AudioNoteDao,
    private val videoNoteDao: VideoNoteDao,
    private val journalContentDao: JournalContentDao,
    private val journalRepository: JournalRepository,
    private val mediaCaptionDao: MediaCaptionDao,
    private val notePlaceResolver: NotePlaceResolver = EmptyNotePlaceResolver,
    private val indexedMediaRepository: IndexedMediaRepository? = null,
    private val mediaManager: MediaManager? = null,
    private val transactionManager: SyncTransactionManager = PassthroughSyncTransactionManager,
    private val syncManagerProvider: () -> SyncManager = { NoOpSyncManager },
    private val syncMetadataService: SyncMetadataService,
    private val syncScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val transcriptionRepository: TranscriptionRepository? = null,
    private val mediaReferences: StoredMediaReferences = StoredMediaReferences.Unchanged,
    private val recoveryReceipts: ArchiveRecoveryReceipts = ArchiveRecoveryReceipts.None,
    private val currentRecoveryScope: suspend () -> app.logdate.client.repository.journals.JournalMergeScope? = { null },
    private val draftSnapshots: suspend () -> List<EntryDraft>? = { null },
    private val journalCoverUris: suspend () -> List<String?>? = { null },
    private val mediaReclamation: MediaReclamationJournal = MediaReclamationJournal.None,
    private val retainedMedia: suspend () -> Set<String> = { emptySet() },
    private val mediaClaimFence: MediaClaimFence = MediaClaimFence.Disabled,
) : JournalNotesRepository,
    ExportableJournalContentRepository,
    SyncableJournalNotesRepository {
    init {
        transcriptionRepository?.startAutomaticTranscriptions(syncScope)
    }

    private val queries =
        JournalNoteQueries(
            textNoteDao,
            imageNoteDao,
            audioNoteDao,
            videoNoteDao,
            journalContentDao,
            mediaCaptionDao,
            notePlaceResolver,
            transcriptionRepository,
        )

    private val mediaIndexer = JournalNoteMediaIndexer(mediaManager, indexedMediaRepository)

    override val allNotesObserved: Flow<List<JournalNote>> = queries.allNotesObserved

    override fun observeNotesInJournal(journalId: Uuid): Flow<List<JournalNote>> = queries.observeNotesInJournal(journalId)

    override suspend fun getAllJournalNoteLinks(): List<Pair<Uuid, Uuid>> = queries.getAllJournalNoteLinks()

    override fun observeNotesInRange(
        start: Instant,
        end: Instant,
    ): Flow<List<JournalNote>> = queries.observeNotesInRange(start, end)

    override fun observeNotesPage(
        pageSize: Int,
        offset: Int,
    ): Flow<List<JournalNote>> = queries.observeNotesPage(pageSize, offset)

    override fun observeNotesStream(pageSize: Int): Flow<List<JournalNote>> = queries.observeNotesStream(pageSize)

    override fun observeNoteById(noteId: Uuid): Flow<JournalNote?> = queries.observeNoteById(noteId)

    override fun observeEntryTimestamps(): Flow<List<Instant>> = queries.observeEntryTimestamps()

    override fun observeRecentNotes(limit: Int): Flow<List<JournalNote>> = queries.observeRecentNotes(limit)

    override fun observeRecentAudioNotes(limit: Int): Flow<List<JournalNote.Audio>> = queries.observeRecentAudioNotes(limit)

    override fun observeNotesForDay(day: LocalDate): Flow<List<JournalNote>> = queries.observeNotesForDay(day)

    override suspend fun getNotesBefore(
        beforeExclusive: Instant,
        limit: Int,
    ): List<JournalNote> = queries.getNotesBefore(beforeExclusive, limit)

    override suspend fun hasNotesBefore(beforeExclusive: Instant): Boolean = queries.hasNotesBefore(beforeExclusive)

    /**
     * Returns the [paths] a note still references, recognising a file under any spelling: a
     * note may store a `logdate-media://` reference for a file a draft names by its path.
     */
    override suspend fun notesReferencingMediaPaths(paths: Set<String>): Set<String> {
        if (paths.isEmpty()) return emptySet()
        return try {
            val protected = retainedMedia() + journalRepository.allJournalsObserved.first().mapNotNull { it.coverImageUri }
            val referenced =
                (queries.allNotesObserved.first().mapNotNull(JournalNote::mediaRefOrNull) + protected)
                    .flatMap { mediaReferences.spellingsOf(it) }
                    .toSet()
            paths.filterTo(mutableSetOf()) { path -> mediaReferences.spellingsOf(path).any(referenced::contains) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.w("Could not verify media references; keeping media", error)
            paths
        }
    }

    override suspend fun getNoteById(noteId: Uuid): JournalNote? = queries.getNoteById(noteId)

    override suspend fun countNotes(): NoteCounts = queries.countNotes()

    override suspend fun getNotesByIds(noteIds: Set<Uuid>): Map<Uuid, JournalNote> = queries.getNotesByIds(noteIds)

    override suspend fun create(note: JournalNote): Uuid =
        mediaClaimFence.withClaim {
            val storedNote = note.withStoredMediaRef(mediaReferences)
            val pendingMediaIndex = mediaIndexer.buildPendingMediaIndex(storedNote)
            val noteId =
                transactionManager.withTransaction {
                    // Keep note persistence, optional index creation, and sync metadata aligned so a
                    // local write failure cannot leave orphaned indexed media behind.
                    createNoteRecord(
                        note = storedNote,
                        pendingMediaIndex = pendingMediaIndex,
                    )
                }

            triggerContentSync()
            noteId
        }

    private suspend fun createNoteRecord(
        note: JournalNote,
        pendingMediaIndex: PendingMediaIndex?,
    ): Uuid {
        getNoteById(note.uid)?.let { existing ->
            check(existing.withStoredMediaRef(mediaReferences).hasSamePersistedContent(note)) {
                "This note already exists with different content. Keep your changes in a draft or save them as a new entry."
            }
            return existing.uid
        }
        val linkedPlaceId =
            note.location
                ?.place
                ?.id
                ?.let { notePlaceResolver.get(it)?.id }
        val noteId =
            when (note) {
                is JournalNote.Handwriting -> {
                    textNoteDao.addNote(note.toEntity(linkedPlaceId))
                    note.uid
                }

                is JournalNote.Text -> {
                    textNoteDao.addNote(note.toEntity(linkedPlaceId))
                    note.uid
                }

                is JournalNote.Image -> {
                    imageNoteDao.addNote(note.toEntity(linkedPlaceId))
                    if (note.caption.isNotEmpty()) {
                        mediaCaptionDao.upsertCaption(MediaCaptionEntity(note.uid, note.caption))
                    }
                    note.uid
                }

                is JournalNote.Audio -> {
                    audioNoteDao.addNote(note.toEntity(linkedPlaceId))
                    mediaCaptionDao.upsertCaption(MediaCaptionEntity(note.uid, note.caption))
                    note.persistTranscript(transcriptionRepository)
                    note.uid
                }

                is JournalNote.Video -> {
                    videoNoteDao.addNote(note.withDurationFrom(pendingMediaIndex).toEntity(linkedPlaceId))
                    if (note.caption.isNotEmpty()) {
                        mediaCaptionDao.upsertCaption(MediaCaptionEntity(note.uid, note.caption))
                    }
                    note.uid
                }
            }

        mediaIndexer.indexPendingMediaIfNeeded(pendingMediaIndex)
        syncMetadataService.enqueuePending(
            entityId = note.uid.toString(),
            entityType = EntityType.NOTE,
            operation = PendingOperation.CREATE,
        )

        return noteId
    }

    /** Deletes owned media only when notes, editor drafts, and journal covers no longer name it. */
    private suspend fun deleteMediaIfUnreferenced(contentUri: String?) {
        if (contentUri.isNullOrEmpty()) return
        val manager = mediaManager ?: return

        val spellings = mediaReferences.spellingsOf(contentUri)
        val stillPublished =
            try {
                notesReferencingMediaPaths(setOf(contentUri)).isNotEmpty()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Napier.w("Could not verify note media references before deletion", error)
                return
            }
        if (stillPublished) return

        val otherReferences =
            try {
                val drafts = draftSnapshots() ?: return
                val covers = journalCoverUris() ?: return
                drafts.flatMap { draft ->
                    draft.notes.mapNotNull(JournalNote::mediaRefOrNull) +
                        draft.pendingMedia.flatMap { pending ->
                            listOfNotNull(pending.filePath, pending.fallbackFilePath)
                        }
                } + covers.filterNotNull() + retainedMedia()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Napier.w("Could not verify other media references before deletion", error)
                return
            }
        if (otherReferences.any { reference -> mediaReferences.spellingsOf(reference).any(spellings::contains) }) return

        runCatching { manager.deleteOwnedMedia(contentUri) }
            .onFailure { Napier.w("Could not remove media for a deleted note", it) }
    }

    override suspend fun remove(note: JournalNote) {
        removeLocalNote(note.uid)
    }

    override suspend fun removeById(noteId: Uuid) {
        removeLocalNote(noteId)
    }

    private suspend fun removeLocalNote(noteId: Uuid) {
        mediaClaimFence.withClaim {
            val journalIds =
                transactionManager.withTransaction {
                    val scope = currentRecoveryScope()
                    val affectedJournals = journalContentDao.getJournalsForContent(noteId).first()
                    val mediaRef = getNoteById(noteId)?.mediaRefOrNull()
                    if (
                        mediaReclamation !== MediaReclamationJournal.None &&
                        mediaRef != null &&
                        MediaReference.parse(mediaRef) !is MediaReference.External
                    ) {
                        mediaReclamation.recordCandidate(mediaRef)
                    }
                    if (scope != null) {
                        recoveryReceipts.recordLocalDeletion(scope, LocalDeletionType.NOTE, noteId.toString())
                    }
                    textNoteDao.removeNote(noteId)
                    imageNoteDao.removeNote(noteId)
                    transcriptionRepository?.deleteTranscription(noteId)
                    audioNoteDao.removeNote(noteId)
                    videoNoteDao.removeNote(noteId)
                    mediaCaptionDao.deleteCaption(noteId)
                    affectedJournals.forEach { journalId ->
                        val key = AssociationPendingKey(journalId, noteId).toPendingId()
                        if (scope != null) recoveryReceipts.recordLocalDeletion(scope, LocalDeletionType.ASSOCIATION, key)
                        syncMetadataService.enqueuePending(key, EntityType.ASSOCIATION, PendingOperation.DELETE)
                    }
                    journalContentDao.removeContentFromAllJournals(noteId)
                    syncMetadataService.enqueuePending(noteId.toString(), EntityType.NOTE, PendingOperation.DELETE)
                    transactionManager.afterCommit { deleteMediaIfUnreferenced(mediaRef) }
                    check(currentRecoveryScope() == scope) { "Note deletion account changed" }
                    affectedJournals
                }
            triggerContentSync()
            if (journalIds.isNotEmpty()) triggerAssociationSync()
        }
    }

    override suspend fun create(
        note: JournalNote,
        journalId: Uuid,
    ) {
        mediaClaimFence.withClaim {
            val storedNote = note.withStoredMediaRef(mediaReferences)
            val pendingMediaIndex = mediaIndexer.buildPendingMediaIndex(storedNote)
            transactionManager.withTransaction {
                val scope = currentRecoveryScope()
                scope?.let {
                    recoveryReceipts.clearLocalDeletion(it, LocalDeletionType.NOTE, note.uid.toString())
                }
                createNoteRecord(
                    note = storedNote,
                    pendingMediaIndex = pendingMediaIndex,
                )
                val destination = journalRepository.resolveJournalId(journalId)
                journalContentDao.addContentToJournal(JournalContentEntityLink(destination, note.uid))
                scope?.let {
                    recoveryReceipts.clearLocalDeletion(
                        it,
                        LocalDeletionType.ASSOCIATION,
                        AssociationPendingKey(destination, note.uid).toPendingId(),
                    )
                }
                syncMetadataService.enqueuePending(
                    entityId = AssociationPendingKey(destination, note.uid).toPendingId(),
                    entityType = EntityType.ASSOCIATION,
                    operation = PendingOperation.CREATE,
                )
                check(currentRecoveryScope() == scope) { "Note creation account changed" }
            }

            triggerContentSync()
            triggerAssociationSync()
        }
    }

    override suspend fun removeFromJournal(
        noteId: Uuid,
        journalId: Uuid,
    ) {
        val removed =
            transactionManager.withTransaction {
                if (journalRepository.resolveJournalId(journalId) != journalId) return@withTransaction false
                val capturedScope = currentRecoveryScope()
                val key = AssociationPendingKey(journalId, noteId).toPendingId()
                if (capturedScope != null) recoveryReceipts.recordLocalDeletion(capturedScope, LocalDeletionType.ASSOCIATION, key)
                journalContentDao.removeContentFromJournal(journalId, noteId)
                syncMetadataService.enqueuePending(key, EntityType.ASSOCIATION, PendingOperation.DELETE)
                check(currentRecoveryScope() == capturedScope) { "Membership deletion account changed" }
                true
            }
        if (!removed) return

        triggerAssociationSync()
    }

    private val recovery by lazy {
        JournalNoteRecoveryPersistence(
            textNoteDao,
            imageNoteDao,
            audioNoteDao,
            videoNoteDao,
            mediaCaptionDao,
            transcriptionRepository,
            transactionManager,
            syncMetadataService,
            mediaReferences,
            ::getNoteById,
            currentRecoveryScope,
            ::triggerContentSync,
            ::indexAvailableRecoveryMedia,
            recoveryReceipts,
            notePlaceResolver,
        )
    }

    private suspend fun indexAvailableRecoveryMedia(note: JournalNote) {
        val reference = note.mediaRefOrNull() ?: return
        if (mediaManager?.exists(reference) == true) mediaIndexer.indexPendingMediaIfNeeded(mediaIndexer.buildPendingMediaIndex(note))
    }

    override suspend fun getNoteRecoveryScope() = currentRecoveryScope()

    override suspend fun compareAndSetNoteForRestore(
        expected: JournalNote?,
        replacement: JournalNote,
        scope: app.logdate.client.repository.journals.JournalMergeScope?,
        recoveryId: String?,
    ): Boolean = recovery.compareAndSet(expected, replacement, scope, recoveryId)

    override suspend fun compareAndSetNoteForRetainedRestore(
        sourceId: Uuid,
        expected: JournalNote?,
        replacement: JournalNote,
        scope: app.logdate.client.repository.journals.JournalMergeScope?,
        recoveryId: String?,
    ): Boolean = recovery.compareAndSet(expected, replacement, scope, recoveryId, retainedSource = true, sourceId = sourceId)

    override suspend fun createFromSync(note: JournalNote) = recovery.createFromSync(note)

    override suspend fun deleteFromSync(noteId: Uuid) = recovery.deleteFromSync(noteId)

    override suspend fun updateSyncMetadata(
        note: JournalNote,
        syncVersion: Long,
        syncedAt: Instant,
    ) = recovery.updateSyncMetadata(note, syncVersion, syncedAt)

    private val mediaMetadata =
        NoteMediaMetadataWriter(
            audioNoteDao,
            imageNoteDao,
            videoNoteDao,
            transactionManager,
            syncMetadataService,
            mediaReferences,
            transcriptionRepository,
            ::getNoteById,
        )

    override suspend fun updateAudioDurationIfUnknown(
        noteId: Uuid,
        mediaRef: String,
        durationMs: Long,
    ): Boolean = mediaMetadata.fillAudioDuration(noteId, mediaRef, durationMs).also { changed -> if (changed) triggerContentSync() }

    override suspend fun updateMediaRef(
        noteId: Uuid,
        mediaRef: String,
    ) = mediaMetadata.updateReference(noteId, mediaRef)

    private val contentSyncDebouncer =
        SyncDebouncer(scope = syncScope) {
            syncManagerProvider().syncContent()
        }

    private val associationSyncDebouncer =
        SyncDebouncer(scope = syncScope) {
            syncManagerProvider().syncAssociations()
        }

    private fun triggerContentSync() = contentSyncDebouncer.trigger()

    private fun triggerAssociationSync() = associationSyncDebouncer.trigger()

    override suspend fun exportContentToFile(
        destination: String,
        overwrite: Boolean,
        startTimestamp: Instant,
        endTimestamp: Instant,
    ) = JournalNoteExporter(
        textNoteDao,
        imageNoteDao,
        audioNoteDao,
        videoNoteDao,
        mediaCaptionDao,
        journalRepository,
        transcriptionRepository,
        queries,
    ).exportContentToFile(destination, overwrite, startTimestamp, endTimestamp)
}
