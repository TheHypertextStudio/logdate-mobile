package app.logdate.feature.editor.ui.editor.delegate

import app.logdate.client.domain.notes.drafts.CleanupExpiredDraftsUseCase
import app.logdate.client.domain.notes.drafts.CreateEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.DeleteAllDraftsUseCase
import app.logdate.client.domain.notes.drafts.DeleteEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.FetchEntryDraftUseCase
import app.logdate.client.domain.notes.drafts.UpdateEntryDraftUseCase
import app.logdate.client.repository.journals.EntryEditBase
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.PendingMediaRecord
import app.logdate.client.repository.journals.PendingMediaType
import app.logdate.feature.editor.ui.camera.CapturedMediaType
import app.logdate.feature.editor.ui.editor.AudioBlockUiState
import app.logdate.feature.editor.ui.editor.AudioCaptureState
import app.logdate.feature.editor.ui.editor.CameraBlockUiState
import app.logdate.feature.editor.ui.editor.DraftState
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.EntryBlockUiState
import app.logdate.feature.editor.ui.mapper.toDomainBlock
import app.logdate.feature.editor.ui.mapper.toJournalNote
import app.logdate.shared.model.location.VisitMemoryContext
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Result of loading a draft, containing the converted blocks and the draft ID.
 */
data class LoadedDraft(
    val blocks: List<EntryBlockUiState>,
    val draftId: Uuid,
    val selectedJournalIds: List<Uuid> = emptyList(),
    val visitContext: VisitMemoryContext? = null,
    val markId: String? = null,
    val editBase: EntryEditBase? = null,
)

/**
 * Manages all draft operations: auto-save, load, delete, and cleanup.
 */
class DraftManager(
    private val updateEntryDraft: UpdateEntryDraftUseCase,
    private val createEntryDraft: CreateEntryDraftUseCase,
    private val fetchEntryDraft: FetchEntryDraftUseCase,
    private val deleteEntryDraft: DeleteEntryDraftUseCase,
    private val deleteAllDraftsUseCase: DeleteAllDraftsUseCase,
    private val cleanupExpiredDraftsUseCase: CleanupExpiredDraftsUseCase,
    private val recordingOwner: suspend () -> String? = { null },
) {
    private var pendingDraftId: Uuid? = null

    /**
     * Auto-saves the current entry state as a draft.
     *
     * Persistable blocks (Ready audio, text with content, finalized images, etc.)
     * are written as `notes`. Audio blocks in a non-Ready, non-Empty state are
     * written as `pendingMedia` so the recording survives process death and the
     * editor can recover it on relaunch.
     *
     * @return The draft ID if a draft was created or updated, null if skipped.
     */
    suspend fun autoSave(state: EditorState): Uuid? {
        val notes = mutableListOf<JournalNote>()
        val pendingMedia = mutableListOf<PendingMediaRecord>()
        for (block in state.blocks) {
            if (state.isReadOnly(block.id)) continue
            val position = notes.size + pendingMedia.size
            if (block.hasContent()) {
                block.toJournalNote()?.let(notes::add)
            }
            if (block is AudioBlockUiState) {
                block.toPendingMediaRecord(position)?.let(pendingMedia::add)
            }
            if (block is CameraBlockUiState && block.uri == null) {
                block.toPendingMediaRecord(position)?.let(pendingMedia::add)
            }
        }

        if (
            notes.isEmpty() &&
            pendingMedia.isEmpty() &&
            !state.hasJournalSelectionChanges &&
            state.draftState == DraftState.None
        ) {
            Napier.d("Skip autosave: no editable content")
            return null
        }

        val draftId =
            when (val draft = state.draftState) {
                is DraftState.Active -> {
                    updateEntryDraft(
                        draftId = draft.id,
                        content = notes,
                        pendingMedia = pendingMedia,
                        selectedJournalIds = state.selectedJournalIds,
                        visitContext = state.visitContext,
                        markId = state.markId,
                        editBase = state.entryEditBase,
                    )
                    pendingDraftId = null
                    draft.id
                }

                DraftState.None -> {
                    val candidateId = pendingDraftId ?: Uuid.random().also { pendingDraftId = it }
                    createEntryDraft(
                        draftId = candidateId,
                        notes = notes,
                        pendingMedia = pendingMedia,
                        selectedJournalIds = state.selectedJournalIds,
                        visitContext = state.visitContext,
                        markId = state.markId,
                        editBase = state.entryEditBase,
                    ).also {
                        pendingDraftId = null
                    }
                }
            }

        Napier.d("Auto-saved draft: $draftId (${notes.size} notes, ${pendingMedia.size} pending)")
        return draftId
    }

    /**
     * Loads a draft by ID and converts its notes and pending media to UI blocks.
     *
     * Pending media records reconstruct as audio blocks in [AudioCaptureState.Stopping]
     * — the original recording session is gone after process death, so the editor
     * surfaces them as "needs recovery" rather than continuing to record. A
     * follow-up step (orphan recovery) validates the file on disk and either
     * promotes the block to [AudioCaptureState.Ready] or marks it failed.
     */
    suspend fun loadDraft(draftId: Uuid): Result<LoadedDraft> =
        try {
            val result = fetchEntryDraft(draftId).first()
            val owner = recordingOwner()
            result.map { draft ->
                check(
                    owner == null ||
                        (
                            draft.pendingMedia.none { it.recordingOwnerId != null && it.recordingOwnerId != owner } &&
                                draft.recordingOwners.values.none { it != owner } &&
                                (draft.quietSave?.let { it.ownerId == owner } != false)
                        ),
                ) { "Switch back to the account that started this recording to recover it." }
                val blocks = draft.notes.map { it.toDomainBlock() }.toMutableList()
                draft.pendingMedia.sortedBy { it.position ?: Int.MAX_VALUE }.forEach { record ->
                    record.toBlock()?.let { block ->
                        val position = record.position?.coerceIn(0, blocks.size) ?: blocks.size
                        blocks.add(position, block)
                    }
                }
                LoadedDraft(
                    blocks = blocks,
                    draftId = draft.id,
                    selectedJournalIds = draft.selectedJournalIds,
                    visitContext = draft.visitContext,
                    markId = draft.markId,
                    editBase = draft.editBase,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Deletes a single draft by ID.
     */
    suspend fun deleteDraft(draftId: Uuid): Result<Unit> =
        try {
            deleteEntryDraft(draftId)
            acknowledgeDraftDeletion(draftId)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * The id the next new draft will be saved under, chosen now if no save has chosen one yet, so it
     * can be shared before the save finishes.
     */
    fun reserveDraftId(): Uuid = pendingDraftId ?: Uuid.random().also { pendingDraftId = it }

    /** Returns the durable draft that must be removed after a successful publish or discard. */
    fun draftIdForCleanup(activeDraftId: Uuid?): Uuid? = activeDraftId ?: pendingDraftId

    /** Returns every durable identity owned by this editor, ordered to retain an active draft on failure. */
    fun draftIdsForCleanup(activeDraftId: Uuid?): List<Uuid> = listOfNotNull(pendingDraftId, activeDraftId).distinct()

    /** Clears a retained create candidate only after its durable row was deleted. */
    fun acknowledgeDraftDeletion(draftId: Uuid?) {
        if (pendingDraftId == draftId) {
            pendingDraftId = null
        }
    }

    /**
     * Deletes all drafts atomically.
     */
    suspend fun deleteAllDrafts(): Result<Unit> =
        try {
            deleteAllDraftsUseCase()
            pendingDraftId = null
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Result.failure(e)
        }

    /**
     * Removes expired drafts.
     *
     * @return The number of drafts deleted.
     */
    suspend fun cleanupExpired(): Int = cleanupExpiredDraftsUseCase()
}

/**
 * Builds the [PendingMediaRecord] list for a draft from the editor's blocks.
 *
 * Read-only blocks (already-persisted entries loaded into the editor) are
 * skipped. So are Empty and Ready audio blocks — Empty has no content, and
 * Ready becomes a [JournalNote.Audio] in the draft's notes list.
 */
private fun AudioBlockUiState.toPendingMediaRecord(position: Int): PendingMediaRecord? {
    val filePath =
        when (val capture = captureState) {
            is AudioCaptureState.Recording -> capture.filePath

            is AudioCaptureState.Stopping -> capture.filePath

            is AudioCaptureState.Failed -> capture.filePath

            AudioCaptureState.Empty,
            is AudioCaptureState.Ready,
            -> return null
        }
    return PendingMediaRecord(
        blockId = id,
        mediaType = PendingMediaType.AUDIO,
        createdAt = timestamp,
        filePath = filePath,
        fallbackFilePath =
            when (val capture = captureState) {
                is AudioCaptureState.Recording -> capture.fallbackFilePath
                is AudioCaptureState.Stopping -> capture.fallbackFilePath
                is AudioCaptureState.Failed -> capture.fallbackFilePath
            },
        timeZoneId = timeZoneId,
        position = position,
        caption = caption,
        transcription = transcription,
    )
}

private fun CameraBlockUiState.toPendingMediaRecord(position: Int): PendingMediaRecord? {
    val path = pendingFilePath ?: return null
    return PendingMediaRecord(
        blockId = id,
        mediaType = if (mediaType == CapturedMediaType.PHOTO) PendingMediaType.CAMERA_PHOTO else PendingMediaType.CAMERA_VIDEO,
        createdAt = timestamp,
        filePath = path,
        timeZoneId = timeZoneId,
        position = position,
        caption = caption,
    )
}

/**
 * Reconstructs an [EntryBlockUiState] from a [PendingMediaRecord].
 *
 * Recording / Stopping states from a previous session are surfaced as
 * [AudioCaptureState.Stopping] — the recorder is no longer active so we cannot
 * resume in-progress recording, but the file may exist on disk and be valid.
 */
private fun PendingMediaRecord.toBlock(): EntryBlockUiState? =
    when (mediaType) {
        PendingMediaType.AUDIO -> {
            AudioBlockUiState(
                id = blockId,
                timestamp = createdAt,
                captureState = AudioCaptureState.Stopping(filePath = filePath, fallbackFilePath = fallbackFilePath),
                caption = caption,
                transcription = transcription,
                timeZoneId = timeZoneId,
            )
        }

        PendingMediaType.CAMERA_PHOTO,
        PendingMediaType.CAMERA_VIDEO,
        -> {
            CameraBlockUiState(
                id = blockId,
                timestamp = createdAt,
                pendingFilePath = filePath,
                caption = caption,
                mediaType = if (mediaType == PendingMediaType.CAMERA_PHOTO) CapturedMediaType.PHOTO else CapturedMediaType.VIDEO,
                timeZoneId = timeZoneId,
            )
        }
    }
