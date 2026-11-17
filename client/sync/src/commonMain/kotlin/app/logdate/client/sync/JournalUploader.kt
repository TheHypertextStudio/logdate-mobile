package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableJournalRepository
import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.CloudJournalDataSource
import app.logdate.client.sync.cloud.SyncUploadResult
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import app.logdate.shared.model.Journal
import io.github.aakira.napier.Napier
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Uploads journal changes using the same scoped retry and conditional-create contracts as notes. */
internal class JournalUploader(
    private val journalRepository: JournalRepository,
    private val cloudJournalDataSource: CloudJournalDataSource,
    private val syncMetadataService: SyncMetadataService,
    private val tokenRefresher: SyncTokenRefresher,
    private val retryCoordinator: SyncRetryCoordinator,
    private val recordProgress: (Int) -> Unit,
    private val profile: SyncProfile,
) {
    private val conditionalCreates = ConditionalCreateSettler(retryCoordinator, recordProgress)
    private val syncableRepository = journalRepository as? SyncableJournalRepository

    suspend fun uploadPending(): SyncResult {
        var uploadedCount = 0
        val errors = mutableListOf<SyncError>()
        val pendingUploads = syncMetadataService.getPendingUploads(EntityType.JOURNAL).dueNow(EntityType.JOURNAL, retryCoordinator)
        if (pendingUploads.isEmpty()) return SyncResult(success = true, uploadedItems = 0)
        for (pending in pendingUploads) {
            val journalId = runCatching { Uuid.parse(pending.entityId) }.getOrNull()
            if (journalId == null) {
                errors.add(retryCoordinator.recordUnparsableOutboxEntry(EntityType.JOURNAL, pending, "journal ID"))
                continue
            }
            if (journalRepository.resolveJournalId(journalId) != journalId) {
                retryCoordinator.markUploadSettled(EntityType.JOURNAL, pending, Clock.System.now(), 0L)
                continue
            }
            if (pending.operation == PendingOperation.DELETE) {
                uploadedCount += delete(pending, journalId, errors)
                continue
            }
            if (pending.operation == PendingOperation.UPDATE && !profile.uploadsUpdates) {
                Napier.d("Leaving a journal edit for the phone to relay")
                continue
            }
            val journal = journalRepository.getJournalById(journalId)
            if (journal == null) {
                errors.add(retryCoordinator.recordUnavailableOutboxEntry(EntityType.JOURNAL, pending))
                continue
            }
            uploadedCount += upload(pending, journal, errors)
        }
        return SyncResult(success = errors.isEmpty(), uploadedItems = uploadedCount, errors = errors)
    }

    private suspend fun delete(
        pending: PendingUpload,
        journalId: Uuid,
        errors: MutableList<SyncError>,
    ): Int {
        if (!retryCoordinator.beginAttempt(EntityType.JOURNAL, pending, errors)) return 0
        val result =
            tokenRefresher.withFreshToken(
                { token -> cloudJournalDataSource.deleteJournal(token, journalId) },
                "deleteJournal($journalId)",
                expectedScope = pending.scope,
            )
        if (result.isSuccess) {
            recordProgress(1)
            retryCoordinator.markUploadSettled(EntityType.JOURNAL, pending, Clock.System.now(), 0L)
            Napier.d("Deleted journal")
            return 1
        }
        val error = result.exceptionOrNull() ?: Exception("Unknown delete error")
        val movedToDeadLetter = retryCoordinator.handleRetryFailure(EntityType.JOURNAL, pending, error)
        errors.add(
            SyncError(
                SyncErrorType.SERVER_ERROR,
                "Failed to delete journal $journalId: ${error.message}",
                error,
                retryable = !movedToDeadLetter,
            ),
        )
        Napier.w("Failed to delete journal")
        return 0
    }

    private suspend fun upload(
        pending: PendingUpload,
        journal: Journal,
        errors: MutableList<SyncError>,
    ): Int {
        if (!retryCoordinator.beginAttempt(EntityType.JOURNAL, pending, errors)) return 0
        if (pending.operation == PendingOperation.CREATE && profile.createsConditionally) return createIfAbsent(pending, journal, errors)
        val result = send(pending, journal)
        if (result.isSuccess) {
            val upload = result.getOrThrow()
            recordProgress(1)
            syncableRepository?.updateSyncMetadata(journal.id, upload.serverVersion, upload.syncedAt)
            retryCoordinator.markUploadSettled(EntityType.JOURNAL, pending, upload.syncedAt, upload.serverVersion)
            Napier.d("Uploaded journal")
            return 1
        }
        recordUploadFailure(pending, journal, result.exceptionOrNull() ?: Exception("Unknown upload error"), errors)
        return 0
    }

    private suspend fun createIfAbsent(
        pending: PendingUpload,
        journal: Journal,
        errors: MutableList<SyncError>,
    ): Int {
        val created =
            tokenRefresher.withFreshToken(
                { token -> cloudJournalDataSource.createJournalIfAbsent(token, journal) },
                "createJournalIfAbsent(${journal.id})",
                expectedScope = pending.scope,
            )
        return conditionalCreates.settle(
            entityType = EntityType.JOURNAL,
            pending = pending,
            result = created,
            errors = errors,
            onCreated = { upload -> syncableRepository?.updateSyncMetadata(journal.id, upload.serverVersion, upload.syncedAt) },
            onDeleted = {
                if (syncableRepository != null) {
                    syncableRepository.deleteFromSync(journal.id)
                } else {
                    journalRepository.delete(journal.id)
                }
            },
        )
    }

    private suspend fun send(
        pending: PendingUpload,
        journal: Journal,
    ): Result<SyncUploadResult> =
        if (pending.operation == PendingOperation.CREATE) {
            tokenRefresher.withFreshToken(
                { token -> cloudJournalDataSource.uploadJournal(token, journal) },
                "uploadJournal(${journal.id})",
                expectedScope = pending.scope,
            )
        } else {
            tokenRefresher.withFreshToken(
                { token ->
                    cloudJournalDataSource.updateJournal(
                        token,
                        journal.copy(
                            syncVersion =
                                pending.expectedServerVersion ?: journal.syncVersion,
                        ),
                    )
                },
                "updateJournal(${journal.id})",
                expectedScope = pending.scope,
            )
        }

    private suspend fun recordUploadFailure(
        pending: PendingUpload,
        journal: Journal,
        error: Throwable,
        errors: MutableList<SyncError>,
    ) {
        if ((error as? CloudApiException)?.statusCode == 409) {
            errors.add(
                retryCoordinator.handleUploadConflict(
                    entityType = EntityType.JOURNAL,
                    pending = pending,
                    error = error,
                    localVersion = journal.syncVersion,
                    localUpdatedAt = journal.lastUpdated,
                ),
            )
            return
        }
        val movedToDeadLetter = retryCoordinator.handleRetryFailure(EntityType.JOURNAL, pending, error)
        errors.add(
            SyncError(
                SyncErrorType.SERVER_ERROR,
                "Failed to upload journal ${journal.id}: ${error.message}",
                error,
                retryable = !movedToDeadLetter,
            ),
        )
        Napier.w("Failed to upload journal")
    }
}
