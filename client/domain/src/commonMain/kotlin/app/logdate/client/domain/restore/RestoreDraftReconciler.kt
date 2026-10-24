package app.logdate.client.domain.restore

import app.logdate.client.domain.export.ExportDraft
import app.logdate.client.repository.journals.DraftRecoveryScope
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.shared.model.EditorDraft
import kotlin.uuid.Uuid

internal class RestoreDraftReconciler(
    private val repository: JournalRepository,
    private val warnings: MutableList<String>,
    private val decoder: RestoreDraftDecoder,
    private val onPendingMedia: (UnavailableMediaAsset) -> Unit,
    private val onApplied: (DecodedArchiveDraft) -> Unit,
    private val expectedScope: app.logdate.client.repository.journals.JournalMergeScope?,
    private val recoveryId: String,
) {
    private data class Undo(
        val original: EditorDraft?,
        val writes: MutableList<EditorDraft> = mutableListOf(),
    )

    private val undo = linkedMapOf<Uuid, Undo>()
    private var recoveryScope: DraftRecoveryScope? = null

    suspend fun restore(
        drafts: List<ExportDraft>,
        strategy: RestoreStrategy,
    ): Int {
        recoveryScope = repository.getDraftRecoveryScope()
        check(
            expectedScope == null ||
                (
                    recoveryScope?.outboxOwnerId == expectedScope.ownerId &&
                        recoveryScope?.outboxServerOrigin == expectedScope.serverOrigin
                ),
        ) {
            "Draft recovery account changed"
        }
        var imported = 0
        for (draft in drafts) {
            check(repository.getDraftRecoveryScope() == recoveryScope) { "Draft recovery account changed" }
            val id = runCatching { Uuid.parse(draft.id) }.getOrNull()
            if (id == null) {
                warnings.add("Skipped draft with an invalid ID")
                continue
            }
            val existing = repository.getDraftForRestore(id)
            if (existing != null) repository.ensureDraftRecoveryQueued(id, recoveryScope)
            val prior = undo[id]
            if (prior != null && existing != (prior.writes.lastOrNull() ?: prior.original)) {
                warnings.add("Kept a draft changed during archive recovery")
                continue
            }
            if (!shouldOverwrite(existing?.lastModifiedAt, draft.updatedAt, strategy)) {
                repository.compareAndSetDraftForRestore(id, existing, existing, recoveryScope, enqueue = false, recoveryId = recoveryId)
                continue
            }
            val decoded = decoder.decode(draft, existing)
            val restored = decoded.draft
            decoded.unavailableMedia.forEach(onPendingMedia)
            val snapshot = undo.getOrPut(id) { Undo(existing) }
            // Track a possibly durable write so a duplicate cannot overwrite an intervening edit.
            snapshot.writes.add(restored)
            if (repository.compareAndSetDraftForRestore(id, existing, restored, recoveryScope, recoveryId = recoveryId)) {
                onApplied(decoded)
                imported++
            } else {
                snapshot.writes.removeAt(snapshot.writes.lastIndex)
                warnings.add("Kept a draft changed during archive recovery")
            }
        }
        check(repository.getDraftRecoveryScope() == recoveryScope) { "Draft recovery account changed" }
        return imported
    }
}
