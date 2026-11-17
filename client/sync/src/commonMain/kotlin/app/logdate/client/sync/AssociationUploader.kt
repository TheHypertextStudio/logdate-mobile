package app.logdate.client.sync

import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.sync.cloud.CloudAssociationDataSource
import app.logdate.client.sync.cloud.JournalContentAssociation
import app.logdate.client.sync.metadata.AssociationPendingKey
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import io.github.aakira.napier.Napier
import kotlin.time.Clock

/**
 * The association half of [SyncUploader]: unlike the other entity types, pending journal-entry
 * links are sent in two batches -- every addition in one request, every removal in another -- and
 * each batch settles or retries all of its entries together.
 *
 * [SyncUploader] wraps [uploadPending] in its own failure handling, so an unexpected failure here is
 * reported the same way as for every other entity type.
 */
internal class AssociationUploader(
    private val cloudAssociationDataSource: CloudAssociationDataSource,
    private val syncMetadataService: SyncMetadataService,
    private val tokenRefresher: SyncTokenRefresher,
    private val retryCoordinator: SyncRetryCoordinator,
    private val recordProgress: (Int) -> Unit,
    private val journalRepository: JournalRepository? = null,
    /** Creates links only if the server has none, for a linked watch. */
    private val createsIfAbsent: Boolean = false,
) {
    private class Batch {
        val associations = mutableListOf<JournalContentAssociation>()
        val ids = mutableListOf<String>()

        fun add(
            association: JournalContentAssociation,
            id: String,
        ) {
            associations.add(association)
            ids.add(id)
        }
    }

    suspend fun uploadPending(): SyncResult {
        var uploadedCount = 0
        val errors = mutableListOf<SyncError>()

        val pendingUploads = syncMetadataService.getPendingUploads(EntityType.ASSOCIATION)
        if (pendingUploads.isEmpty()) {
            return SyncResult(success = true, uploadedItems = 0)
        }

        val pendingById = pendingUploads.associateBy { it.entityId }
        val creates = Batch()
        val deletes = Batch()
        collectBatches(pendingUploads, creates, deletes, errors)

        if (creates.associations.isNotEmpty()) {
            uploadedCount += uploadCreates(creates, pendingById, errors)
        }
        if (deletes.associations.isNotEmpty()) {
            uploadedCount += uploadDeletes(deletes, pendingById, errors)
        }

        return SyncResult(success = errors.isEmpty(), uploadedItems = uploadedCount, errors = errors)
    }

    private suspend fun collectBatches(
        pendingUploads: List<PendingUpload>,
        creates: Batch,
        deletes: Batch,
        errors: MutableList<SyncError>,
    ) {
        val blockedDestinations =
            journalRepository
                ?.pendingJournalMerges()
                ?.map {
                    journalRepository.resolveJournalId(it.sourceId)
                }?.toSet()
                .orEmpty()
        pendingUploads.forEach { pending ->
            if (!retryCoordinator.shouldAttempt(EntityType.ASSOCIATION, pending)) {
                return@forEach
            }
            val key = AssociationPendingKey.fromPendingId(pending.entityId)
            if (key == null) {
                errors.add(retryCoordinator.recordUnparsableOutboxEntry(EntityType.ASSOCIATION, pending, "association key"))
                return@forEach
            }
            val destination = journalRepository?.resolveJournalId(key.journalId) ?: key.journalId
            if (destination != key.journalId && pending.operation == PendingOperation.DELETE) {
                retryCoordinator.markUploadSettled(EntityType.ASSOCIATION, pending, Clock.System.now(), 0L)
                return@forEach
            }
            if (destination in blockedDestinations) return@forEach
            if (!retryCoordinator.beginAttempt(EntityType.ASSOCIATION, pending, errors)) return@forEach

            val association =
                JournalContentAssociation(
                    journalId = destination,
                    contentId = key.contentId,
                    createdAt = Clock.System.now(),
                )

            when (pending.operation) {
                PendingOperation.DELETE -> deletes.add(association, pending.entityId)

                PendingOperation.CREATE,
                PendingOperation.UPDATE,
                -> creates.add(association, pending.entityId)
            }
        }
    }

    /** Returns how many associations the server accepted. */
    private suspend fun uploadCreates(
        batch: Batch,
        pendingById: Map<String, PendingUpload>,
        errors: MutableList<SyncError>,
    ): Int {
        val result =
            tokenRefresher.withFreshToken(
                { token ->
                    if (createsIfAbsent) {
                        cloudAssociationDataSource.uploadAssociationsIfAbsent(token, batch.associations)
                    } else {
                        cloudAssociationDataSource.uploadAssociations(token, batch.associations)
                    }
                },
                "uploadAssociations(${batch.associations.size} items)",
                expectedScope = pendingById.getValue(batch.ids.first()).scope,
            )
        if (result.isFailure) {
            val error = result.exceptionOrNull() ?: Exception("Unknown upload error")
            recordBatchFailure(batch, pendingById, error, "Failed to upload associations", errors)
            Napier.w("Failed to upload associations")
            return 0
        }
        val uploadedAt = result.getOrThrow()
        batch.ids.forEach { id ->
            retryCoordinator.markUploadSettled(EntityType.ASSOCIATION, pendingById.getValue(id), uploadedAt, 0L)
        }
        recordProgress(batch.associations.size)
        Napier.d("Uploaded associations")
        return batch.associations.size
    }

    /** Returns how many association removals the server accepted. */
    private suspend fun uploadDeletes(
        batch: Batch,
        pendingById: Map<String, PendingUpload>,
        errors: MutableList<SyncError>,
    ): Int {
        val result =
            tokenRefresher.withFreshToken(
                { token -> cloudAssociationDataSource.deleteAssociations(token, batch.associations) },
                "deleteAssociations(${batch.associations.size} items)",
                expectedScope = pendingById.getValue(batch.ids.first()).scope,
            )
        if (result.isFailure) {
            val error = result.exceptionOrNull() ?: Exception("Unknown delete error")
            recordBatchFailure(batch, pendingById, error, "Failed to delete associations", errors)
            Napier.w("Failed to delete associations")
            return 0
        }
        val deletedAt = Clock.System.now()
        batch.ids.forEach { id ->
            retryCoordinator.markUploadSettled(EntityType.ASSOCIATION, pendingById.getValue(id), deletedAt, 0L)
        }
        recordProgress(batch.associations.size)
        Napier.d("Deleted associations")
        return batch.associations.size
    }

    private suspend fun recordBatchFailure(
        batch: Batch,
        pendingById: Map<String, PendingUpload>,
        error: Throwable,
        message: String,
        errors: MutableList<SyncError>,
    ) {
        var movedToDeadLetter = false
        batch.ids.forEach { id ->
            val pending = pendingById[id] ?: return@forEach
            if (retryCoordinator.handleRetryFailure(EntityType.ASSOCIATION, pending, error)) {
                movedToDeadLetter = true
            }
        }
        errors.add(
            SyncError(
                SyncErrorType.SERVER_ERROR,
                "$message: ${error.message}",
                error,
                retryable = !movedToDeadLetter,
            ),
        )
    }
}
