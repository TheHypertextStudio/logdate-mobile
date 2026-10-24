package app.logdate.client.data.journals

import app.logdate.client.repository.journals.DraftRecoveryScope
import app.logdate.client.repository.journals.DraftRepository
import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.metadata.UploadScope
import app.logdate.shared.model.EditorDraft
import kotlin.uuid.Uuid

internal class JournalDraftPersistence(
    private val drafts: DraftRepository,
    private val metadata: SyncMetadataService,
    private val resolve: suspend (EditorDraft) -> EditorDraft,
    private val changed: () -> Unit,
    private val transactions: app.logdate.client.sync.SyncTransactionManager = DirectArchiveRecoveryTransactions,
    private val currentOutboxScope: (suspend () -> JournalMergeScope)? = null,
) {
    suspend fun save(draft: EditorDraft) {
        val scope = mutationScope()
        val resolved = resolve(draft)
        persist(draft.id, resolved, scope)
    }

    suspend fun delete(id: Uuid) = persist(id, null, mutationScope())

    private suspend fun mutationScope(): DraftRecoveryScope? {
        val storage = drafts.getDraftRecoveryScope()
        val outbox = currentOutboxScope?.invoke()
        requireStorageScope(storage)
        check(
            storage?.ownerId == null ||
                outbox == null ||
                (
                    storage.ownerId == outbox.ownerId &&
                        storage.serverOrigin == outbox.serverOrigin
                ),
        ) {
            "Draft storage and upload scope differ"
        }
        return storage?.copy(
            outboxOwnerId = outbox?.ownerId?.takeIf { it.isNotBlank() } ?: storage.outboxOwnerId,
            outboxServerOrigin = outbox?.serverOrigin?.takeIf { it.isNotBlank() } ?: storage.outboxServerOrigin,
        )
    }

    private suspend fun persist(
        id: Uuid,
        replacement: EditorDraft?,
        scope: DraftRecoveryScope?,
    ) {
        requireStorageScope(scope)
        if (scope != null && drafts.persistDraftMutation(id, replacement, scope)) {
            changed()
            return
        }
        while (true) {
            requireStorageScope(scope)
            val expected = drafts.getDraftForRestore(id)
            requireStorageScope(scope)
            val updated =
                replacement?.let {
                    if (expected == null) {
                        it
                    } else {
                        it.copy(
                            lastModifiedAt =
                                kotlin.time.Clock.System
                                    .now(),
                        )
                    }
                }
            if (drafts.compareAndSetDraftForRestore(id, expected, updated, scope)) break
        }
        val operation = if (replacement == null) PendingOperation.DELETE else PendingOperation.UPDATE
        val captured = uploadScope(scope)
        if (captured == null) {
            metadata.enqueuePending(id.toString(), EntityType.DRAFT, operation)
        } else {
            metadata.enqueuePendingForScope(id.toString(), EntityType.DRAFT, operation, captured)
        }
        changed()
    }

    suspend fun captureScope(currentOutboxScope: suspend () -> JournalMergeScope): DraftRecoveryScope? {
        val storage = drafts.getDraftRecoveryScope() ?: return null
        val outbox = currentOutboxScope()
        check(drafts.getDraftRecoveryScope() == storage) { "Draft recovery account changed" }
        check(storage.ownerId == null || (storage.ownerId == outbox.ownerId && storage.serverOrigin == outbox.serverOrigin)) {
            "Draft recovery storage and upload scope differ"
        }
        return storage.copy(outboxOwnerId = outbox.ownerId, outboxServerOrigin = outbox.serverOrigin)
    }

    private suspend fun requireStorageScope(scope: DraftRecoveryScope?) {
        val current = drafts.getDraftRecoveryScope()
        check(
            if (scope ==
                null
            ) {
                current == null
            } else {
                current != null && current.ownerId == scope.ownerId && current.serverOrigin == scope.serverOrigin
            },
        ) {
            "Draft recovery account changed"
        }
    }

    private suspend fun requireRecoveryScope(scope: DraftRecoveryScope?) {
        requireStorageScope(scope)
        if (scope == null) return
        val outbox = currentOutboxScope?.invoke() ?: return
        check(
            app.logdate.client.device.identity
                .sameOwnerId(outbox.ownerId, scope.outboxOwnerId) &&
                outbox.serverOrigin.trimEnd('/') == scope.outboxServerOrigin?.trimEnd('/'),
        ) { "Draft recovery upload scope changed" }
    }

    private fun uploadScope(scope: DraftRecoveryScope?): UploadScope? =
        scope?.outboxOwnerId?.let { owner -> scope.outboxServerOrigin?.let { UploadScope(owner, it) } }

    suspend fun getForRestore(id: Uuid): EditorDraft? = drafts.getDraftForRestore(id)

    suspend fun ensureRecoveryQueued(
        id: Uuid,
        recoveryScope: DraftRecoveryScope?,
    ) {
        val present =
            transactions.withTransaction {
                requireRecoveryScope(recoveryScope)
                if (drafts.getDraftForRestore(id) == null) return@withTransaction false
                requireRecoveryScope(recoveryScope)
                val captured = uploadScope(recoveryScope)
                if (captured == null) {
                    metadata.enqueueRepairIfAbsent(id.toString(), EntityType.DRAFT)
                } else {
                    metadata.enqueueRepairForScopeIfAbsent(id.toString(), EntityType.DRAFT, captured)
                }
                requireRecoveryScope(recoveryScope)
                true
            }
        if (present) changed()
    }

    suspend fun compareAndSet(
        id: Uuid,
        expected: EditorDraft?,
        replacement: EditorDraft?,
        recoveryScope: DraftRecoveryScope? = null,
        enqueue: Boolean = true,
        recoveryId: String? = null,
        retainedSourceId: Uuid? = null,
    ): Boolean {
        val applied =
            transactions.withTransaction {
                requireRecoveryScope(recoveryScope)
                if (recoveryId != null && drafts.hasArchiveRecoveryDecision(id, recoveryScope, recoveryId)) return@withTransaction false
                val current = drafts.getDraftForRestore(id)
                if (retainedSourceId != null && drafts.hasLocalDeletionForRetainedRestore(retainedSourceId, id, recoveryScope)) {
                    requireRecoveryScope(recoveryScope)
                    drafts.compareAndSetDraftForRetainedRestore(
                        retainedSourceId,
                        id,
                        current,
                        current,
                        recoveryScope,
                        recoveryId,
                    )
                    return@withTransaction false
                }
                if (current != expected) {
                    requireRecoveryScope(recoveryScope)
                    drafts.compareAndSetDraftForRestore(id, current, current, recoveryScope, recoveryId)
                    return@withTransaction false
                }
                if (!enqueue || expected == replacement) {
                    return@withTransaction persistDraftDecision(
                        id,
                        expected,
                        replacement,
                        recoveryScope,
                        recoveryId,
                        retainedSourceId,
                    )
                }
                if (!enqueueRecoveryMutation(id, replacement, recoveryScope)) {
                    requireRecoveryScope(recoveryScope)
                    drafts.compareAndSetDraftForRestore(id, current, current, recoveryScope, recoveryId)
                    requireRecoveryScope(recoveryScope)
                    return@withTransaction false
                }
                val stored =
                    persistDraftDecision(
                        id,
                        expected,
                        replacement,
                        recoveryScope,
                        recoveryId,
                        retainedSourceId,
                    )
                requireStorageScope(recoveryScope)
                stored
            }
        if (applied && enqueue && expected != replacement) changed()
        return applied
    }

    private suspend fun enqueueRecoveryMutation(
        id: Uuid,
        replacement: EditorDraft?,
        scope: DraftRecoveryScope?,
    ): Boolean {
        requireRecoveryScope(scope)
        val captured = uploadScope(scope)
        if (replacement != null) return metadata.enqueueRestoreMutation(id.toString(), EntityType.DRAFT, PendingOperation.UPDATE, captured)
        if (captured == null) {
            metadata.enqueuePending(id.toString(), EntityType.DRAFT, PendingOperation.DELETE)
        } else {
            metadata.enqueuePendingForScope(id.toString(), EntityType.DRAFT, PendingOperation.DELETE, captured)
        }
        return true
    }

    private suspend fun persistDraftDecision(
        id: Uuid,
        expected: EditorDraft?,
        replacement: EditorDraft?,
        scope: DraftRecoveryScope?,
        recoveryId: String?,
        retainedSourceId: Uuid?,
    ): Boolean {
        requireRecoveryScope(scope)
        return if (retainedSourceId == null) {
            drafts.compareAndSetDraftForRestore(id, expected, replacement, scope, recoveryId)
        } else {
            drafts.compareAndSetDraftForRetainedRestore(retainedSourceId, id, expected, replacement, scope, recoveryId)
        }
    }
}

internal suspend fun app.logdate.client.repository.journals.JournalRepository.resolveDraft(draft: EditorDraft): EditorDraft =
    draft.copy(selectedJournalIds = draft.selectedJournalIds.map { resolveJournalId(it) }.distinct())
