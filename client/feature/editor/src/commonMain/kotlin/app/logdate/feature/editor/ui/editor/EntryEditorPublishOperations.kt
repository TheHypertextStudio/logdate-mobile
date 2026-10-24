package app.logdate.feature.editor.ui.editor

import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.notes.EntryEditConflictException
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.text.toUiText
import app.logdate.feature.editor.ui.mapper.toJournalNote
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.editor_error_changed_while_saving
import logdate.client.feature.editor.generated.resources.editor_error_clear_draft_failed
import logdate.client.feature.editor.generated.resources.editor_error_recording_still_finalizing
import logdate.client.feature.editor.generated.resources.editor_error_save_failed
import kotlin.uuid.Uuid

/**
 * Saves the current entry.
 *
 * Pending audio blocks are finalized first so a recording whose URI hasn't yet
 * been propagated into the block from the recording side is absorbed into the
 * save instead of being silently dropped by the mapper.
 */

internal fun EntryEditorViewModel.saveEntryImpl(state: EditorState) {
    val admittedContentRevision = tryBeginEntrySave(state) ?: return

    viewModelScope.launch {
        try {
            saveEntryAfterAdmission(admittedContentRevision)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (conflict: EntryEditConflictException) {
            try {
                val draftId = persistDraft(currentDraftPersistenceState(mutableEditorState.value), allowWhileExplicitlySaving = true)
                checkNotNull(draftId) { "The conflicting edit could not be saved as a draft" }
                mutableEditorState.update { it.copy(entryEditConflict = true, errorMessage = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Napier.e("Could not preserve conflicting entry edits", failure)
                mutableEditorState.update {
                    it.copy(
                        errorMessage = Res.string.editor_error_save_failed.toUiText(failure.message.toString()),
                    )
                }
            }
        } catch (error: Exception) {
            Napier.e("Failed to save entry: ${error.message}", error)
            mutableEditorState.update {
                it.copy(errorMessage = Res.string.editor_error_save_failed.toUiText(error.message.toString()))
            }
        } finally {
            mutableEditorState.update { currentState ->
                if (currentState.shouldExit) {
                    currentState.copy(isSaving = false)
                } else {
                    currentState.copy(
                        isSaving = false,
                        isEditingLocked = false,
                    )
                }
            }
        }
    }
}

internal suspend fun EntryEditorViewModel.saveEntryAfterAdmission(admittedContentRevision: Long) {
    val resolvedAudio = finalizePendingAudio() ?: return

    // Wait for any local draft write already in progress. Reading the latest state and
    // deleting its active draft under the same lock prevents a completed autosave from
    // recreating a draft after publish.
    draftPersistenceMutex.withLock {
        val latestEditableState = mutableEditorState.value
        if (latestEditableState.shouldExit) return@withLock
        var publishSnapshot = currentDraftPersistenceState(latestEditableState)
        if (publishSnapshot.entryEditBase?.copyNoteId != null) {
            checkNotNull(
                persistLatestDraftUntilStable(publishSnapshot),
            ) { "The separate version must be saved as a draft before publishing" }
            publishSnapshot = currentDraftPersistenceState(mutableEditorState.value)
        }
        if (publishSnapshot.contentRevision != admittedContentRevision) {
            failPublishInvariant()
            return@withLock
        }
        val publishFingerprint = getEditorDraftFingerprint(publishSnapshot)
        val notes =
            publishSnapshot.blocks.mapNotNull { block ->
                if (publishSnapshot.isReadOnly(block.id)) return@mapNotNull null
                val effective =
                    if (block is AudioBlockUiState && block.id in resolvedAudio) {
                        block.copy(captureState = resolvedAudio.getValue(block.id))
                    } else {
                        block
                    }
                effective.toJournalNote()
            }
        val publishedBlockIds = notes.map { it.uid }.toSet()

        if (notes.isEmpty()) {
            finishEmptyPublish(publishSnapshot, admittedContentRevision, publishFingerprint, publishedBlockIds, resolvedAudio)
            return@withLock
        }

        persistPublishedNotes(notes, publishSnapshot, resolvedAudio)
        if (publishSnapshotChanged(admittedContentRevision, publishFingerprint, publishSnapshot, publishedBlockIds)) {
            applyResolvedAudio(resolvedAudio)
            failPublishInvariant()
            return@withLock
        }
        applyResolvedAudio(resolvedAudio)
        runCatching { captureUsage.entrySaved(captureModesOf(notes), publishSnapshot.selectedJournalIds) }
            .onFailure { error -> Napier.w("Could not report capture usage", error) }
        mutableEditorState.update {
            it.copy(
                draftState = DraftState.None,
                hasJournalSelectionChanges = false,
                isModified = false,
                errorMessage = null,
                shouldExit = true,
                exitReason = EditorExitReason.ENTRY_SAVED,
            )
        }
    }
}

/**
 * @param publishedBlockIds Block IDs this save just persisted. Their own read-only status
 *   flipping true is this save's own expected side effect (the reactive "today's notes" flow
 *   now includes the note it just created), not a conflicting external edit, so it's excluded
 *   from the comparison below -- every other block's read-only status is still compared live,
 *   so a genuine concurrent change is still caught.
 */
internal fun EntryEditorViewModel.publishSnapshotChanged(
    admittedContentRevision: Long,
    admittedFingerprint: String,
    template: EditorState,
    publishedBlockIds: Set<Uuid>,
): Boolean {
    val liveReadOnlyBlocks = editorState.value.readOnlyBlocks
    val readOnlyBlocksIgnoringOwnPublish =
        liveReadOnlyBlocks + template.readOnlyBlocks.filterKeys { it in publishedBlockIds }
    val currentSnapshot =
        currentDraftPersistenceState(template).copy(readOnlyBlocks = readOnlyBlocksIgnoringOwnPublish)
    val currentFingerprint = getEditorDraftFingerprint(currentSnapshot)
    val revisionChanged = currentSnapshot.contentRevision != admittedContentRevision
    val fingerprintChanged = currentFingerprint != admittedFingerprint
    return revisionChanged || fingerprintChanged
}

internal fun EntryEditorViewModel.failPublishInvariant() {
    mutableEditorState.update {
        it.copy(
            errorMessage = Res.string.editor_error_changed_while_saving.toUiText(),
            isSaving = false,
            isEditingLocked = false,
        )
    }
}

internal fun EntryEditorViewModel.tryBeginEntrySave(requestedState: EditorState): Long? =
    tryBeginExclusiveOperation(requestedState, lockEditing = true)

internal fun EntryEditorViewModel.tryBeginExclusiveOperation(
    requestedState: EditorState,
    lockEditing: Boolean,
): Long? {
    if (requestedState.shouldExit) return null
    while (true) {
        val currentState = mutableEditorState.value
        if (currentState.shouldExit ||
            currentState.isSaving ||
            currentState.isEditingLocked ||
            currentState.audioCaptureStartReservations.isNotEmpty()
        ) {
            return null
        }
        if (
            mutableEditorState.compareAndSet(
                currentState,
                currentState.copy(
                    isSaving = true,
                    isEditingLocked = lockEditing,
                    errorMessage = null,
                ),
            )
        ) {
            return currentState.contentRevision
        }
    }
}

/**
 * Drives any in-flight audio recordings to a finalized state.
 *
 * Returns a map of blockId → resolved [AudioCaptureState] when every finalize
 * succeeds (possibly empty if no pending audio existed). Returns null when
 * any finalize times out or returns [AudioCaptureState.Failed] — in that case
 * the editor's [EditorState.errorMessage] is set and [EditorState.isSaving]
 * is cleared so the caller can abort the save without further work.
 *
 * The returned map is applied to [mutableEditorState] only after the save
 * completes, to avoid relying on [editorState] (a combined flow) re-emitting
 * synchronously within this coroutine.
 */
internal suspend fun EntryEditorViewModel.finalizePendingAudio(blockIds: Set<Uuid>? = null): Map<Uuid, AudioCaptureState>? {
    val initialState = mutableEditorState.value
    val pendingBlocks =
        initialState.blocks.filterIsInstance<AudioBlockUiState>().filter { block ->
            (blockIds == null || block.id in blockIds) &&
                !initialState.isReadOnly(block.id) &&
                (!block.isPersistable() || block.durationMs <= 0L)
        }
    if (pendingBlocks.isEmpty()) return emptyMap()

    val finalized: List<Pair<Uuid, AudioCaptureState?>> =
        coroutineScope {
            pendingBlocks
                .map { block ->
                    async {
                        block.id to
                            withTimeoutOrNull(EntryEditorViewModel.AUDIO_FINALIZE_TIMEOUT_MS) {
                                val state = audioBlockFinalizerFor(block.id).finalize(block.id, block.captureState)
                                if (state is AudioCaptureState.Ready) resolveAudioDuration(state) else state
                            }
                    }
                }.awaitAll()
        }

    val resolved = mutableMapOf<Uuid, AudioCaptureState>()
    for ((blockId, state) in finalized) {
        when (state) {
            null -> {
                Napier.w("Audio finalization timed out for block $blockId")
                mutableEditorState.update {
                    it.copy(
                        errorMessage = Res.string.editor_error_recording_still_finalizing.toUiText(),
                        isSaving = false,
                        isEditingLocked = false,
                    )
                }
                return null
            }

            is AudioCaptureState.Failed -> {
                Napier.w("Audio finalization failed for block $blockId: ${state.reason.key}")
                mutableEditorState.update {
                    it.copy(
                        errorMessage = state.reason.toUiText(),
                        isSaving = false,
                        isEditingLocked = false,
                    )
                }
                return null
            }

            else -> {
                resolved[blockId] = state
            }
        }
    }
    return resolved
}

/**
 * Pushes the resolved capture states from [finalizePendingAudio] back into
 * [mutableEditorState] so the UI reflects finalized recordings even on the
 * non-exit paths (e.g., `notes.isEmpty()`, save failure).
 */
internal fun EntryEditorViewModel.applyResolvedAudio(resolved: Map<Uuid, AudioCaptureState>) {
    if (resolved.isEmpty()) return
    mutableEditorState.update { state ->
        state.copy(
            blocks =
                state.blocks.map { existing ->
                    if (existing is AudioBlockUiState && existing.id in resolved) {
                        existing.copy(captureState = resolved.getValue(existing.id))
                    } else {
                        existing
                    }
                },
        )
    }
}

private suspend fun EntryEditorViewModel.finishEmptyPublish(
    publishSnapshot: EditorState,
    admittedContentRevision: Long,
    publishFingerprint: String,
    publishedBlockIds: Set<Uuid>,
    resolvedAudio: Map<Uuid, AudioCaptureState>,
) {
    val activeDraftId = (publishSnapshot.draftState as? DraftState.Active)?.id
    val cleanupDraftId = draftManager.draftIdForCleanup(activeDraftId)
    val deletionFailure = cleanupDraftId?.let { draftManager.deleteDraft(it).exceptionOrNull() }
    if (deletionFailure != null) {
        if (deletionFailure is CancellationException) throw deletionFailure
        Napier.e("Failed to delete cleared draft: ${deletionFailure.message}", deletionFailure)
        applyResolvedAudio(resolvedAudio)
        mutableEditorState.update {
            it.copy(errorMessage = Res.string.editor_error_clear_draft_failed.toUiText(deletionFailure.message.toString()))
        }
        return
    }
    if (publishSnapshotChanged(admittedContentRevision, publishFingerprint, publishSnapshot, publishedBlockIds)) {
        applyResolvedAudio(resolvedAudio)
        failPublishInvariant()
        return
    }
    applyResolvedAudio(resolvedAudio)
    mutableEditorState.update {
        it.copy(
            draftState = DraftState.None,
            hasJournalSelectionChanges = false,
            isModified = false,
            shouldExit = true,
            exitReason = EditorExitReason.ENTRY_SAVED,
        )
    }
    return
}

private suspend fun EntryEditorViewModel.persistPublishedNotes(
    notes: List<JournalNote>,
    publishSnapshot: EditorState,
    resolvedAudio: Map<Uuid, AudioCaptureState>,
) {
    val activeDraftId = (publishSnapshot.draftState as? DraftState.Active)?.id
    val cleanupDraftId = draftManager.draftIdForCleanup(activeDraftId)
    try {
        saveEntryUseCase(
            notes,
            publishSnapshot.selectedJournalIds,
            cleanupDraftId,
            publishSnapshot.visitContext,
            publishSnapshot.markId,
            publishSnapshot.entryEditBase,
        )
        draftManager.acknowledgeDraftDeletion(cleanupDraftId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        applyResolvedAudio(resolvedAudio)
        throw error
    }
}
