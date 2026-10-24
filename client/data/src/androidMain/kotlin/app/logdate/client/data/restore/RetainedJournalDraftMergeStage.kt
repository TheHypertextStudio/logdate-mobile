package app.logdate.client.data.restore

import app.logdate.client.media.storage.StoredMediaReferences
import app.logdate.client.media.storage.isRecoveredOriginal
import app.logdate.client.repository.journals.DraftRecoveryScope
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableEntryBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableVideoBlock
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.uuid.Uuid

internal data class RetainedDraftMergePlan(
    val source: EditorDraft,
    val replacement: EditorDraft,
    val plan: RetainedJournalMergeCheckpoint.Plan,
    val known: RetainedJournalMergeCheckpoint.Entry?,
)

internal suspend fun RetainedJournalDatabaseMerger.mergeDrafts(
    journalTargets: Map<Uuid, Uuid>,
    pending: () -> Unit,
): Int {
    val file = sourceFiles["files/logdate.preferences_pb"] ?: return 0
    val captured = journals.getDraftRecoveryScope() ?: return 0.also { draftsCovered = false }
    if (!sameScope(captured)) return 0.also { draftsCovered = false }
    val sourceDrafts = readSourceDrafts(file) ?: return 0.also { draftsCovered = false }
    var applied = 0
    for (draft in sourceDrafts) {
        applied += applyRetainedDraft(prepareRetainedDraft(draft, journalTargets, pending), captured)
    }
    return applied
}

internal suspend fun RetainedJournalDatabaseMerger.prepareRetainedDraft(
    draft: EditorDraft,
    journalTargets: Map<Uuid, Uuid>,
    pending: () -> Unit,
): RetainedDraftMergePlan {
    requireCurrentScope()
    val sourceHash = hash(json.encodeToString(EditorDraft.serializer(), draft))
    val current = journals.getDraftForRestore(draft.id)
    val key = checkpointKey("draft", draft.id.toString())
    val known = checkpointStore.read(key)
    val knownTarget = known?.targetId?.let { parseUuid(it) }
    val mapped = knownTarget?.takeIf { it != draft.id }?.let { journals.getDraftForRestore(it) }
    val accepted = if (knownTarget != null && knownTarget != draft.id) mapped else current
    val needsMediaImport =
        shouldReimportRetainedMedia(
            known,
            sourceHash,
            accepted != null,
            accepted?.blocks.orEmpty().flatMap { draftMediaReferences(it) },
            { media.exists(it) },
        )
    val remappedBlocks =
        if (needsMediaImport) {
            draft.blocks.map { importDraftMedia(it, pending) }
        } else {
            accepted?.blocks ?: draft.blocks
        }
    val replacement =
        draft.copy(
            blocks = remappedBlocks,
            selectedJournalIds = draft.selectedJournalIds.mapNotNull(journalTargets::get).distinct(),
        )
    val targetHash = hash(json.encodeToString(EditorDraft.serializer(), replacement))
    val plan =
        RetainedJournalMergeCheckpoint(entries = known?.let { mapOf(key to it) }.orEmpty()).plan(
            generationId,
            "draft",
            draft.id.toString(),
            sourceHash,
            current?.let { hash(json.encodeToString(EditorDraft.serializer(), it)) },
            mapped?.let { hash(json.encodeToString(EditorDraft.serializer(), it)) },
            targetHash,
        )
    return RetainedDraftMergePlan(draft, replacement, plan, known)
}

internal suspend fun RetainedJournalDatabaseMerger.applyRetainedDraft(
    prepared: RetainedDraftMergePlan,
    captured: DraftRecoveryScope,
): Int {
    val (draft, replacement, plan, known) = prepared
    var applied = 0
    if (plan.targetId == null ||
        plan.decision in
        setOf(
            RetainedJournalMergeCheckpoint.Decision.SOURCE_CHANGED,
            RetainedJournalMergeCheckpoint.Decision.TARGET_CONFLICT,
        )
    ) {
        draftsCovered = false
        return 0
    }
    val targetId = parseUuid(plan.targetId)
    val next = replacement.copy(id = targetId)
    val expectedHash = hash(json.encodeToString(EditorDraft.serializer(), next))
    val checkpointEntry = persistCheckpointPlan(plan, known, expectedHash)
    if (plan.decision == RetainedJournalMergeCheckpoint.Decision.INSERT) {
        val existing = journals.getDraftForRestore(targetId)
        if (existing == null) {
            if (!journals.compareAndSetDraftForRetainedRestore(
                    draft.id,
                    targetId,
                    null,
                    next,
                    captured,
                    recoveryId(),
                )
            ) {
                draftsCovered = false
                return 0
            }
            applied++
        } else if (hash(json.encodeToString(EditorDraft.serializer(), existing)) != expectedHash) {
            draftsCovered = false
            return 0
        }
    } else if (plan.decision == RetainedJournalMergeCheckpoint.Decision.ALREADY_PRESENT) {
        if (!repairRetainedDraft(targetId, draft, next, captured)) return 0
    }
    if (!checkpointEntry.applied) {
        journals.ensureDraftRecoveryQueued(targetId, captured)
        checkpointStore.markApplied(plan.key, expectedHash)
    }
    return applied
}

internal suspend fun RetainedJournalDatabaseMerger.repairRetainedDraft(
    targetId: Uuid,
    draft: EditorDraft,
    next: EditorDraft,
    captured: DraftRecoveryScope,
): Boolean {
    val existing = journals.getDraftForRestore(targetId) ?: return false
    val repaired = repairDraftMedia(existing, draft, next)
    if (repaired != existing &&
        !journals.compareAndSetDraftForRetainedRestore(
            draft.id,
            targetId,
            existing,
            repaired,
            captured,
            recoveryId =
                retainedMediaRepairId(
                    generationId,
                    targetId.toString(),
                    hash(json.encodeToString(EditorDraft.serializer(), repaired)),
                ),
        )
    ) {
        draftsCovered = false
        return false
    }
    return true
}

internal fun RetainedJournalDatabaseMerger.draftMediaReferences(block: SerializableEntryBlock): List<String?> =
    when (block) {
        is SerializableImageBlock -> listOf(block.uri)
        is SerializableVideoBlock -> listOf(block.uri, block.thumbnailUri)
        is SerializableAudioBlock -> listOf(block.uri)
        is SerializableCameraBlock -> listOf(block.uri)
        else -> emptyList()
    }

internal suspend fun RetainedJournalDatabaseMerger.importDraftMedia(
    block: SerializableEntryBlock,
    pending: () -> Unit,
): SerializableEntryBlock {
    suspend fun imported(reference: String?): String? {
        if (reference == null) return null
        val asset = RetainedManagedMediaSource(sourceFiles, additionalMediaSources).resolve(reference)
        if (asset == null) {
            requireCurrentScope()
            val recovered =
                verifiedCanonicalMediaReference(reference) { media.isRecoveredOriginal(it, StoredMediaReferences.Unchanged) }
            requireCurrentScope()
            if (recovered != null) return recovered
            pending()
            return retainedUnavailableMediaReference(reference)
        }
        requireCurrentScope()
        val result = media.saveMediaFromFile(asset.file.absolutePath, asset.fileName, asset.mimeType)
        requireCurrentScope()
        return result
    }
    return when (block) {
        is SerializableImageBlock -> block.copy(uri = imported(block.uri))
        is SerializableVideoBlock -> block.copy(uri = imported(block.uri), thumbnailUri = imported(block.thumbnailUri))
        is SerializableAudioBlock -> block.copy(uri = imported(block.uri))
        is SerializableCameraBlock -> block.copy(uri = imported(block.uri))
        else -> block
    }
}

internal fun RetainedJournalDatabaseMerger.repairDraftMedia(
    current: EditorDraft,
    source: EditorDraft,
    imported: EditorDraft,
): EditorDraft {
    val sourceBlocks = source.blocks.associateBy { it.id }
    val importedBlocks = imported.blocks.associateBy { it.id }
    val repaired =
        current.blocks.map { block ->
            val original = sourceBlocks[block.id] ?: return@map block
            val recovered = importedBlocks[block.id] ?: return@map block
            when {
                block is SerializableImageBlock && original is SerializableImageBlock && recovered is SerializableImageBlock -> {
                    block.copy(uri = repairRetainedMediaReference(block.uri, original.uri, recovered.uri))
                }

                block is SerializableVideoBlock && original is SerializableVideoBlock && recovered is SerializableVideoBlock -> {
                    block.copy(
                        uri = repairRetainedMediaReference(block.uri, original.uri, recovered.uri),
                        thumbnailUri = repairRetainedMediaReference(block.thumbnailUri, original.thumbnailUri, recovered.thumbnailUri),
                    )
                }

                block is SerializableAudioBlock && original is SerializableAudioBlock && recovered is SerializableAudioBlock -> {
                    block.copy(uri = repairRetainedMediaReference(block.uri, original.uri, recovered.uri))
                }

                block is SerializableCameraBlock && original is SerializableCameraBlock && recovered is SerializableCameraBlock -> {
                    block.copy(uri = repairRetainedMediaReference(block.uri, original.uri, recovered.uri))
                }

                else -> {
                    block
                }
            }
        }
    return current.copy(blocks = repaired)
}

internal suspend fun RetainedJournalDatabaseMerger.readSourceDrafts(file: File): List<EditorDraft>? {
    val key = sourceScope.retainedDraftStorageKey() ?: return null
    return try {
        val preferences = readRetainedPreferencesSnapshot(file, scratchRoot) ?: return null
        val decoded = readRetainedDraftContents(preferences, key, json)
        if (!decoded.covered) draftsCovered = false
        decoded.drafts
    } catch (_: Exception) {
        null
    }
}
