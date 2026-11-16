package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableDraftRepository
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.client.sync.recovery.DownloadInbox
import app.logdate.client.sync.recovery.DownloadScope
import app.logdate.client.sync.space.SpaceKind
import app.logdate.client.sync.space.SpaceMediaReference
import app.logdate.client.sync.space.SpaceStagedRecords
import app.logdate.client.sync.space.SpaceSync
import app.logdate.client.sync.space.mappers.SpaceDraft
import app.logdate.client.sync.space.spaceFetchError
import app.logdate.shared.model.EditorDraft
import app.logdate.shared.model.SerializableAudioBlock
import app.logdate.shared.model.SerializableCameraBlock
import app.logdate.shared.model.SerializableImageBlock
import app.logdate.shared.model.SerializableTextBlock
import app.logdate.shared.model.SerializableVideoBlock
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class SpaceDraftDownloader(
    private val journalRepository: JournalRepository,
    private val syncMetadataService: SyncMetadataService,
    private val transactionManager: SyncTransactionManager,
    private val downloadEngine: SyncDownloadEngine,
    private val downloadInbox: DownloadInbox?,
    private val saveDraft: suspend (EditorDraft) -> Unit,
    private val recordPendingConflict: suspend (Uuid, EditorDraft?, Long?, Instant?) -> Unit,
    private val hasPendingLocal: suspend (Uuid) -> Boolean,
    private val checkScope: (DownloadScope?) -> Unit,
    private val mapCloudApiError: (CloudApiException) -> SyncResult,
    private val mapException: (Exception, String) -> SyncResult,
) {
    private val syncableDraftRepository = journalRepository as? SyncableDraftRepository

    private class BatchTally {
        var downloadedCount = 0
        var conflictsResolved = 0
        val errors = mutableListOf<SyncError>()
    }

    /**
     * Applies the drafts the personal space changed, one at a time so one failure leaves the rest
     * applied. A draft edited here since it was last uploaded keeps the edit and uploads it again;
     * otherwise the newer of the two wins, as for drafts from the server.
     */
    suspend fun download(
        sync: SpaceSync,
        kind: SpaceKind<SpaceDraft>,
    ): SyncResult =
        try {
            val selected = downloadInbox?.currentScope()
            val tally = BatchTally()
            val staged = sync.read(kind).getOrElse { failure -> throw failure }
            downloadInbox.spaceFetchError()?.let { tally.errors += it }
            do {
                val page = staged.page()
                page.changes.forEach { applySpaceDraft(staged, it, selected, tally) }
                page.deletions.forEach { deleteSpaceDraft(staged, it, selected, tally) }
            } while (page.hasMore)
            SyncResult(
                success = tally.errors.isEmpty(),
                downloadedItems = tally.downloadedCount,
                conflictsResolved = tally.conflictsResolved,
                errors = tally.errors,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: CloudApiException) {
            mapCloudApiError(error)
        } catch (error: Exception) {
            mapException(error, "Download drafts")
        }

    private suspend fun applySpaceDraft(
        staged: SpaceStagedRecords<SpaceDraft>,
        remote: SpaceDraft,
        selected: DownloadScope?,
        tally: BatchTally,
    ) {
        val draft = remote.draft
        val id = draft.id.toString()
        try {
            transactionManager.withTransaction {
                checkScope(selected)
                val existing = journalRepository.getDraft(draft.id)
                val outcome =
                    when {
                        hasPendingLocal(draft.id) -> {
                            recordPendingConflict(draft.id, existing, remote.syncVersion, draft.lastModifiedAt)
                            tally.conflictsResolved++
                            RemoteOutcome.DECLINED
                        }

                        existing != null && existing.lastModifiedAt > draft.lastModifiedAt -> {
                            syncMetadataService.enqueuePending(id, EntityType.DRAFT, PendingOperation.UPDATE)
                            tally.conflictsResolved++
                            RemoteOutcome.KEPT_LOCAL
                        }

                        else -> {
                            if (existing != draft) saveDraft(draft)
                            downloadInbox?.takeIf { draft.hasSpaceMedia() }?.enqueueSpaceDraftMedia(id, remote.syncVersion)
                            tally.downloadedCount++
                            RemoteOutcome.STORED
                        }
                    }
                staged.settle(id, outcome)
                checkScope(selected)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            staged.settleFailed(id)
            tally.errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Failed to apply remote draft", cause = error)
            Napier.e("Failed to apply a remote draft: ${error::class.simpleName}")
        }
    }

    private suspend fun deleteSpaceDraft(
        staged: SpaceStagedRecords<SpaceDraft>,
        id: String,
        selected: DownloadScope?,
        tally: BatchTally,
    ) {
        val draftId = Uuid.parse(id)
        try {
            transactionManager.withTransaction {
                checkScope(selected)
                val pending = syncMetadataService.getPendingUploads(EntityType.DRAFT).firstOrNull { it.entityId == id }
                if (pending != null && pending.operation != PendingOperation.DELETE) {
                    downloadEngine.preserveDeletionConflict(EntityType.DRAFT, id)
                    tally.conflictsResolved++
                } else if (pending != null) {
                    check(
                        syncMetadataService.settleIfCurrent(
                            EntityType.DRAFT,
                            pending,
                            kotlin.time.Clock.System
                                .now(),
                            0L,
                        ),
                    )
                }
                if (syncableDraftRepository != null) {
                    syncableDraftRepository.deleteDraftFromSync(draftId)
                } else {
                    journalRepository.deleteDraft(draftId)
                }
                staged.settle(id, RemoteOutcome.DELETED)
                tally.downloadedCount++
                checkScope(selected)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throwIfDeviceBlocked(error)
            staged.settleFailed(id)
            tally.errors += SyncError(SyncErrorType.UNKNOWN_ERROR, "Failed to delete remote draft", cause = error)
            Napier.e("Failed to delete a remote draft: ${error::class.simpleName}")
        }
    }

    private fun EditorDraft.hasSpaceMedia(): Boolean =
        blocks.any { block ->
            when (block) {
                is app.logdate.shared.model.SerializableHandwritingBlock, is SerializableTextBlock -> false
                is SerializableImageBlock -> block.uri.isSpaceFile()
                is SerializableVideoBlock -> block.uri.isSpaceFile() || block.thumbnailUri.isSpaceFile()
                is SerializableAudioBlock -> block.uri.isSpaceFile()
                is SerializableCameraBlock -> block.uri.isSpaceFile()
            }
        }

    private fun String?.isSpaceFile(): Boolean = this != null && SpaceMediaReference.parse(this) != null
}
