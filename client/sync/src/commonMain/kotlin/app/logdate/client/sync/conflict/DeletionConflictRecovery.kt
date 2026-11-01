package app.logdate.client.sync.conflict

import app.logdate.client.repository.journals.JournalContentRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.JOURNAL_COVER_KIND
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableEntryBlock
import app.logdate.shared.model.SerializableHandwritingBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Saved edits are independent of the outbox and of the server's deletion fence. */
class DeletionConflictRecovery(
    private val store: SyncConflictStore,
    private val notes: JournalNotesRepository,
    private val journals: JournalRepository,
    private val contents: JournalContentRepository,
    private val transactions: SyncTransactionManager,
    private val currentScope: () -> UploadScope?,
    private val mediaManager: app.logdate.client.media.MediaManager? = null,
    private val mediaRefs: app.logdate.client.sync.metadata.MediaSyncRefStore? = null,
    private val metadata: SyncMetadataService? = null,
    private val mediaFiles: app.logdate.client.media.storage.MediaFileResolver? = null,
) {
    private val mutex = Mutex()

    suspend fun list(): List<SyncConflictRecord> {
        val scope = currentScope() ?: return emptyList()
        return store.list().filter { it.scope == scope && it.deletedEdit != null }
    }

    suspend fun review(): SyncReviewInventory = syncReviewInventory(store.list(), currentScope())

    suspend fun retain(
        type: EntityType,
        id: String,
    ) = mutex.withLock {
        val scope = requireNotNull(currentScope()) { "A bound account is required to preserve an edit" }
        val snapshot = captureSnapshot(type, id, scope)
        check(scope == currentScope()) { "Account changed while preserving an edit" }
        val revision = snapshot.note?.lastUpdated ?: snapshot.journal?.lastUpdated ?: requireNotNull(snapshot.draft).lastModifiedAt
        val key =
            "deleted:${scope.ownerId.length}:${scope.ownerId}:" +
                "${scope.serverOrigin.length}:${scope.serverOrigin}:${type.name}:$id:${revision.toEpochMilliseconds()}"
        val existing =
            store.list().firstOrNull {
                it.scope == scope &&
                    it.entityType == type.name &&
                    it.entityId == id &&
                    it.deletedEdit == snapshot
            }
        if (existing == null) {
            store.add(
                SyncConflictRecord(
                    id = "$key:${Uuid.random()}",
                    entityType = type.name,
                    entityId = id,
                    localVersion = snapshot.note?.syncVersion ?: snapshot.journal?.syncVersion,
                    remoteVersion = null,
                    localUpdatedAt = revision.toEpochMilliseconds(),
                    remoteUpdatedAt = null,
                    reason = "DELETION_CONFLICT",
                    detectedAt = Clock.System.now().toEpochMilliseconds(),
                    scope = scope,
                    deletedEdit = snapshot,
                ),
            )
        }
        check(scope == currentScope()) { "Account changed while preserving an edit" }
    }

    private suspend fun captureSnapshot(
        type: EntityType,
        id: String,
        scope: UploadScope,
    ): DeletedEdit {
        val association =
            if (type ==
                EntityType.ASSOCIATION
            ) {
                requireNotNull(
                    app.logdate.client.sync.metadata.AssociationPendingKey
                        .fromPendingId(id),
                )
            } else {
                null
            }
        val uuid = association?.contentId ?: Uuid.parse(id)
        val pendingLinks =
            metadata
                ?.getPendingUploads(EntityType.ASSOCIATION)
                .orEmpty()
                .filter { it.operation != PendingOperation.DELETE }
                .mapNotNull { AssociationPendingKey.fromPendingId(it.entityId) }
        return when (type) {
            EntityType.NOTE, EntityType.ASSOCIATION -> {
                val note = notes.getNoteById(uuid) ?: error("The local edit is unavailable")
                val membershipIds =
                    association?.let { listOf(it.journalId.toString()) } ?: (
                        notes.getAllJournalNoteLinks().filter { it.second == uuid }.map { it.first.toString() } +
                            pendingLinks.filter { it.contentId == uuid }.map { it.journalId.toString() }
                    ).distinct()
                DeletedEdit(note = preserveMedia(note, scope), journalIds = membershipIds)
            }

            EntityType.JOURNAL -> {
                val journal = journals.getJournalById(uuid) ?: error("The local journal is unavailable")
                val retainedCover =
                    journal.coverImageUri?.let { uri ->
                        val cachedCover = mediaRefs?.getDraftAsset(journal.id, journal.id, JOURNAL_COVER_KIND)
                        preserveReference(uri, scope, cached = cachedCover)
                    }
                DeletedEdit(
                    journal = journal.copy(coverImageUri = retainedCover),
                    notes =
                        (
                            notes.observeNotesInJournal(uuid).first() +
                                pendingLinks.filter { it.journalId == uuid }.mapNotNull { notes.getNoteById(it.contentId) }
                        ).distinctBy { it.uid }
                            .map { preserveMedia(it, scope) },
                )
            }

            EntityType.DRAFT -> {
                DeletedEdit(draft = preserveDraft(journals.getDraft(uuid) ?: error("The draft is unavailable"), scope))
            }

            else -> {
                error("This edit cannot be preserved as an item")
            }
        }
    }

    suspend fun saveAsNew(conflictId: String): Uuid? =
        mutex.withLock {
            val scope = currentScope() ?: return@withLock null
            val record = store.list().firstOrNull { it.id == conflictId && it.scope == scope } ?: return@withLock null
            val id = record.recoveryId?.let(Uuid::parse) ?: Uuid.random()
            if (record.recovered) return@withLock id
            val snapshot = record.deletedEdit ?: return@withLock null
            // Save identities first so a crash after creation cannot create a second copy on retry.
            val memberIds =
                record.recoveryNoteIds.ifEmpty {
                    (
                        snapshot.notes.map { it.uid } +
                            snapshot.draft
                                ?.blocks
                                .orEmpty()
                                .map { it.id }
                    ).associate { it.toString() to Uuid.random().toString() }
                }
            val prepared = record.copy(recoveryId = id.toString(), recoveryNoteIds = memberIds)
            store.add(prepared)
            transactions.withTransaction {
                check(scope == currentScope()) { "Account changed during recovery" }
                snapshot.note?.let { original ->
                    if (notes.getNoteById(id) == null) notes.create(original.recoveredAs(id))
                    for (journalId in snapshot.journalIds.map(Uuid::parse)) {
                        if (journals.getJournalById(journalId) != null) contents.addContentToJournal(id, journalId)
                    }
                }
                restoreJournal(snapshot, id, memberIds)
                snapshot.draft?.let { original ->
                    if (journals.getDraft(id) == null) {
                        journals.saveDraft(
                            original.copy(
                                id = id,
                                lastModifiedAt = Clock.System.now(),
                                selectedJournalIds = original.selectedJournalIds.filter { journals.getJournalById(it) != null },
                                blocks = original.blocks.map { it.recoveredAs(Uuid.parse(memberIds.getValue(it.id.toString()))) },
                            ),
                        )
                    }
                }
                check(scope == currentScope()) { "Account changed during recovery" }
            }
            // The new item now owns its media. Retain only a receipt so later deletion can free it.
            store.add(prepared.copy(recovered = true, deletedEdit = null, recoveryNoteIds = emptyMap()))
            id
        }

    private suspend fun restoreJournal(
        snapshot: DeletedEdit,
        id: Uuid,
        memberIds: Map<String, String>,
    ) {
        val original = snapshot.journal ?: return
        if (journals.getJournalById(id) ==
            null
        ) {
            journals.create(original.copy(id = id, syncVersion = 0, lastUpdated = Clock.System.now()))
        }
        for (note in snapshot.notes) {
            // Keep surviving memberships. If that note was separately deleted, save a fresh copy.
            val noteId =
                if (notes.getNoteById(note.uid) !=
                    null
                ) {
                    note.uid
                } else {
                    Uuid.parse(memberIds.getValue(note.uid.toString()))
                }
            if (notes.getNoteById(noteId) == null) notes.create(note.recoveredAs(noteId))
            contents.addContentToJournal(noteId, id)
        }
    }

    private suspend fun preserveMedia(
        note: JournalNote,
        scope: UploadScope,
    ): JournalNote {
        val ref = note.mediaRefOrNull() ?: return note
        val retained = preserveReference(ref, scope, note.uid)
        return when (note) {
            is JournalNote.Text, is JournalNote.Handwriting -> note
            is JournalNote.Image -> note.copy(mediaRef = retained)
            is JournalNote.Video -> note.copy(mediaRef = retained)
            is JournalNote.Audio -> note.copy(mediaRef = retained)
        }
    }

    private suspend fun preserveReference(
        ref: String,
        scope: UploadScope,
        noteId: Uuid? = null,
        cached: app.logdate.client.sync.metadata.MediaSyncRef? = null,
    ): String {
        val manager = mediaManager ?: return ref
        val local =
            if (ref.startsWith("https://") || ref.startsWith("http://")) {
                val mapping = cached ?: noteId?.let { mediaRefs?.get(it) }
                mapping?.takeIf { it.ownerId == scope.ownerId && it.serverOrigin == scope.serverOrigin }?.localUri
                    ?: throw app.logdate.client.sync
                        .MissingMediaException("saved attachment", IllegalStateException("Local bytes unavailable"))
            } else {
                ref
            }
        if (!manager.exists(
                local,
            )
        ) {
            throw app.logdate.client.sync
                .MissingMediaException("saved attachment", IllegalStateException("Local bytes unavailable"))
        }
        // Keep a canonical app-owned copy when the old entry used an external or legacy file.
        val file = mediaFiles?.filePath(local)
        if (file != null && mediaFiles?.refFor(file) != null) return requireNotNull(mediaFiles).storedReference(local)
        val preserved =
            if (file != null) {
                val source = manager.openMedia(local)
                manager.saveMediaFromFile(file, source.fileName, source.mimeType)
            } else if (local.startsWith("logdate-media://")) {
                local
            } else {
                manager.saveMedia(manager.readMedia(local))
            }
        check(!preserved.startsWith("http") && manager.exists(preserved)) { "The attachment could not be retained" }
        return preserved
    }

    private suspend fun preserveDraft(
        draft: EditorDraft,
        scope: UploadScope,
    ): EditorDraft =
        draft.copy(
            blocks =
                draft.blocks.map { block ->
                    when (block) {
                        is SerializableHandwritingBlock -> block
                        is SerializableTextBlock -> {
                            block
                        }

                        is SerializableImageBlock -> {
                            block.copy(
                                uri =
                                    block.uri?.let {
                                        preserveReference(it, scope, cached = mediaRefs?.getDraftAsset(draft.id, block.id, "image"))
                                    },
                            )
                        }

                        is SerializableVideoBlock -> {
                            block.copy(
                                uri =
                                    block.uri?.let {
                                        preserveReference(it, scope, cached = mediaRefs?.getDraftAsset(draft.id, block.id, "video"))
                                    },
                                thumbnailUri =
                                    block.thumbnailUri?.let {
                                        preserveReference(it, scope, cached = mediaRefs?.getDraftAsset(draft.id, block.id, "thumbnail"))
                                    },
                            )
                        }

                        is SerializableAudioBlock -> {
                            block.copy(
                                uri =
                                    block.uri?.let {
                                        preserveReference(it, scope, cached = mediaRefs?.getDraftAsset(draft.id, block.id, "audio"))
                                    },
                            )
                        }

                        is SerializableCameraBlock -> {
                            block.copy(
                                uri =
                                    block.uri?.let {
                                        preserveReference(it, scope, cached = mediaRefs?.getDraftAsset(draft.id, block.id, "camera"))
                                    },
                            )
                        }
                    }
                },
        )
}

private fun JournalNote.recoveredAs(id: Uuid): JournalNote =
    when (this) {
        is JournalNote.Handwriting -> copy(uid = id, syncVersion = 0, lastUpdated = Clock.System.now())
        is JournalNote.Text -> copy(uid = id, syncVersion = 0, lastUpdated = Clock.System.now())
        is JournalNote.Image -> copy(uid = id, syncVersion = 0, lastUpdated = Clock.System.now())
        is JournalNote.Video -> copy(uid = id, syncVersion = 0, lastUpdated = Clock.System.now())
        is JournalNote.Audio -> copy(uid = id, syncVersion = 0, lastUpdated = Clock.System.now())
    }

private fun SerializableEntryBlock.recoveredAs(id: Uuid): SerializableEntryBlock =
    when (this) {
        is SerializableHandwritingBlock -> copy(id = id)
        is SerializableTextBlock -> copy(id = id)
        is SerializableImageBlock -> copy(id = id)
        is SerializableVideoBlock -> copy(id = id)
        is SerializableAudioBlock -> copy(id = id)
        is SerializableCameraBlock -> copy(id = id)
    }
