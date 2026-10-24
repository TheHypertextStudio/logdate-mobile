package app.logdate.client.sync.recovery

import app.logdate.client.database.entities.sync.DownloadInboxEntity
import app.logdate.client.media.MediaManager
import app.logdate.client.repository.journals.DraftRecoveryScope
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.journals.mediaRefOrNull
import app.logdate.client.sync.DraftAssetMapping
import app.logdate.client.sync.DraftMediaHydrationResult
import app.logdate.client.sync.MediaHydrationResult
import app.logdate.client.sync.SyncMediaTransfer
import app.logdate.client.sync.SyncTransactionManager
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.MediaSyncRefStore
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableVideoBlock
import app.logdate.shared.model.diagnostics.DiagnosticReason
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/** Reopens attachment recovery when native backup retained metadata and omitted its original bytes. */
internal class NativeMediaRecovery(
    private val inbox: DownloadInbox,
    private val notes: JournalNotesRepository,
    private val drafts: JournalRepository,
    private val media: MediaManager,
    private val transfer: SyncMediaTransfer,
    private val refs: MediaSyncRefStore,
    private val transactions: SyncTransactionManager,
) {
    suspend fun recover(
        accessToken: String,
        type: EntityType,
    ) {
        val scope = inbox.currentScope()
        when (type) {
            EntityType.NOTE -> recoverNotes(accessToken, scope)
            EntityType.DRAFT -> recoverDrafts(accessToken, scope)
            else -> Unit
        }
    }

    private suspend fun isMissingLocal(reference: String?): Boolean =
        !reference.isNullOrBlank() &&
            !reference.startsWith("https://") &&
            !reference.startsWith("http://") &&
            !transfer.originalAvailable(reference)

    private suspend fun recoverNotes(
        accessToken: String,
        scope: DownloadScope,
    ) {
        val type = "MEDIA_NATIVE_NOTE"
        for (note in notes.allNotesObserved.first()) {
            if (scope != inbox.currentScope()) return
            val reference = note.mediaRefOrNull() ?: continue
            if (isMissingLocal(
                    reference,
                )
            ) {
                inbox.enqueueNativeMedia(type, note.uid.toString(), note.lastUpdated.toEpochMilliseconds(), reference, scope)
            }
        }
        for (row in inbox.pending(type, scope)) {
            if (scope != inbox.currentScope()) return
            try {
                val id = Uuid.parse(row.entityId)
                val original = notes.getNoteById(id)
                if (original == null || original.mediaRefOrNull() != row.payload) {
                    inbox.applied(type, row.entityId, row.version, scope)
                    continue
                }
                when (val result = transfer.hydrate(accessToken, original) { scope == inbox.currentScope() }) {
                    is MediaHydrationResult.Pending -> {
                        inbox.failed(type, row.entityId, result.reason.name, row.version, scope)
                    }

                    is MediaHydrationResult.Available -> {
                        transactions.withTransaction {
                            val current = notes.getNoteById(id)
                            if (current?.mediaRefOrNull() == row.payload && current.type == original.type) {
                                val syncable = notes as? SyncableJournalNotesRepository ?: error("Sync note repository unavailable")
                                syncable.updateMediaRef(id, requireNotNull(result.note.mediaRefOrNull()))
                                result.mapping?.let { refs.upsert(it.copy(ownerId = scope.owner, serverOrigin = scope.origin)) }
                                inbox.applied(type, row.entityId, row.version, scope)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.e("Media recovery failed: ${error::class.simpleName}")
                inbox.failed(type, row.entityId, DiagnosticReason.LOCAL_STORAGE.name, row.version, scope)
            }
        }
    }

    private suspend fun recoverDrafts(
        accessToken: String,
        scope: DownloadScope,
    ) {
        val type = "MEDIA_NATIVE_DRAFT"
        enqueueMissingDraftMedia(type, scope)
        recoverPendingDraftMedia(accessToken, type, scope)
    }

    private suspend fun enqueueMissingDraftMedia(
        type: String,
        scope: DownloadScope,
    ) {
        for (draft in drafts.getAllDraftsForSync()) {
            if (scope != inbox.currentScope()) return
            val references =
                draft.blocks.flatMap { block ->
                    when (block) {
                        is SerializableImageBlock -> listOf(block.uri)
                        is SerializableVideoBlock -> listOf(block.uri, block.thumbnailUri)
                        is SerializableAudioBlock -> listOf(block.uri)
                        is SerializableCameraBlock -> listOf(block.uri)
                        else -> emptyList()
                    }
                }
            if (references.any { isMissingLocal(it) }) {
                inbox.enqueueNativeMedia(type, draft.id.toString(), draft.lastModifiedAt.toEpochMilliseconds(), "draft", scope)
            }
        }
    }

    private suspend fun recoverPendingDraftMedia(
        accessToken: String,
        type: String,
        scope: DownloadScope,
    ) {
        for (row in inbox.pending(type, scope)) {
            if (scope != inbox.currentScope()) return
            recoverDraftRow(accessToken, type, row, scope)
        }
    }

    private suspend fun recoverDraftRow(
        accessToken: String,
        type: String,
        row: DownloadInboxEntity,
        scope: DownloadScope,
    ) {
        try {
            val id = Uuid.parse(row.entityId)
            val original = drafts.getDraftForRestore(id)
            if (original == null) {
                inbox.applied(type, row.entityId, row.version, scope)
                return
            }
            val draftScope = drafts.getDraftRecoveryScope()
            val result = transfer.hydrateDraftMedia(accessToken, original) { scope == inbox.currentScope() }
            if (scope != inbox.currentScope()) return
            val (updated, mappings) =
                when (result) {
                    is DraftMediaHydrationResult.Available -> result.draft to result.mappings
                    is DraftMediaHydrationResult.Pending -> result.draft to result.mappings
                }
            persistDraftHydration(id, original, draftScope, updated, mappings, result, row, type, scope)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.e("Draft media hydration failed: ${error::class.simpleName}")
            inbox.failed(type, row.entityId, DiagnosticReason.LOCAL_STORAGE.name, row.version, scope)
        }
    }

    private suspend fun persistDraftHydration(
        id: Uuid,
        original: EditorDraft,
        draftScope: DraftRecoveryScope?,
        updated: EditorDraft,
        mappings: List<DraftAssetMapping>,
        result: DraftMediaHydrationResult,
        row: DownloadInboxEntity,
        type: String,
        scope: DownloadScope,
    ) {
        transactions.withTransaction {
            if (scope != inbox.currentScope() || !inbox.isCurrentVersion(type, row.entityId, row.version, scope)) {
                return@withTransaction
            }
            val currentDraft = drafts.getDraftForRestore(id) ?: return@withTransaction
            val draftToPersist = resolveDraftAfterHydration(original, currentDraft, updated, mappings) ?: return@withTransaction
            mappings.forEach { mapping ->
                if (scope != inbox.currentScope()) return@withTransaction
                refs.upsertDraftAsset(
                    mapping.draftId,
                    mapping.blockId,
                    mapping.kind,
                    mapping.ref.copy(ownerId = scope.owner, serverOrigin = scope.origin),
                )
            }
            if (!drafts.compareAndSetDraftForRestore(id, currentDraft, draftToPersist, draftScope, enqueue = false)) {
                return@withTransaction
            }
            if (scope != inbox.currentScope() || !inbox.isCurrentVersion(type, row.entityId, row.version, scope)) {
                return@withTransaction
            }
            when (result) {
                is DraftMediaHydrationResult.Available -> inbox.applied(type, row.entityId, row.version, scope)
                is DraftMediaHydrationResult.Pending -> inbox.failed(type, row.entityId, result.reason.name, row.version, scope)
            }
        }
    }

    private fun resolveDraftAfterHydration(
        original: EditorDraft,
        current: EditorDraft,
        updated: EditorDraft,
        mappings: List<DraftAssetMapping>,
    ): EditorDraft? {
        if (current == original) return updated
        if (
            mappings.isEmpty() ||
            mappings.any { mapping ->
                val originalReference = attachmentReference(original, mapping.blockId, mapping.kind)
                originalReference.isNullOrBlank() || originalReference != attachmentReference(current, mapping.blockId, mapping.kind)
            }
        ) {
            return null
        }
        return mappings.fold(current, ::applyRecoveredAttachment)
    }

    private fun attachmentReference(
        draft: EditorDraft,
        blockId: Uuid,
        kind: String,
    ): String? {
        val block = draft.blocks.firstOrNull { it.id == blockId } ?: return null
        return when (kind) {
            "image" -> (block as? SerializableImageBlock)?.uri
            "video" -> (block as? SerializableVideoBlock)?.uri
            "thumbnail" -> (block as? SerializableVideoBlock)?.thumbnailUri
            "audio" -> (block as? SerializableAudioBlock)?.uri
            "camera" -> (block as? SerializableCameraBlock)?.uri
            else -> null
        }
    }

    private fun applyRecoveredAttachment(
        draft: EditorDraft,
        mapping: DraftAssetMapping,
    ): EditorDraft {
        val blocks = draft.blocks.toMutableList()
        val index = blocks.indexOfFirst { it.id == mapping.blockId }
        if (index < 0) return draft
        val block = blocks[index]
        blocks[index] =
            when (mapping.kind) {
                "image" -> (block as? SerializableImageBlock)?.copy(uri = mapping.ref.localUri) ?: block
                "video" -> (block as? SerializableVideoBlock)?.copy(uri = mapping.ref.localUri) ?: block
                "thumbnail" -> (block as? SerializableVideoBlock)?.copy(thumbnailUri = mapping.ref.localUri) ?: block
                "audio" -> (block as? SerializableAudioBlock)?.copy(uri = mapping.ref.localUri) ?: block
                "camera" -> (block as? SerializableCameraBlock)?.copy(uri = mapping.ref.localUri) ?: block
                else -> block
            }
        return draft.copy(blocks = blocks)
    }
}
