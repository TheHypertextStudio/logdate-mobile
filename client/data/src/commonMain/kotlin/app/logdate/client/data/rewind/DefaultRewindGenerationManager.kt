package app.logdate.client.data.rewind

import app.logdate.client.database.dao.rewind.RewindGenerationRequestDao
import app.logdate.client.database.entities.rewind.RewindGenerationRequestEntity
import app.logdate.client.repository.rewind.RewindGenerationManager
import app.logdate.shared.model.RewindGenerationRequest
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Default implementation of [RewindGenerationManager] that persists generation requests in a database.
 *
 * This class manages the lifecycle of rewind generation requests, including creating, tracking,
 * and cancelling requests.
 */
class DefaultRewindGenerationManager(
    private val requestDao: RewindGenerationRequestDao,
) : RewindGenerationManager {
    override suspend fun requestGeneration(
        startTime: Instant,
        endTime: Instant,
    ): RewindGenerationRequest {
        // Check if a request already exists for this time period
        if (requestDao.requestExistsForPeriod(startTime, endTime)) {
            Napier.d("Rewind generation request already exists for period $startTime to $endTime")
            val existingRequest = requestDao.getRequestForPeriod(startTime, endTime)?.toModel()
            if (existingRequest != null) {
                return existingRequest
            }
        }

        try {
            val requestId = Uuid.random()
            val now = Clock.System.now()

            // Create and insert the request entity
            val requestEntity =
                RewindGenerationRequestEntity(
                    id = requestId,
                    startTime = startTime,
                    endTime = endTime,
                    requestTime = now,
                    status = RewindGenerationRequestEntity.Status.PENDING,
                )
            requestDao.insertRequest(requestEntity)

            return RewindGenerationRequest(
                id = requestId,
                startTime = startTime,
                endTime = endTime,
                requestTime = now,
                status = RewindGenerationRequest.Status.PENDING,
            )
        } catch (e: Exception) {
            Napier.e("Failed to create rewind generation request", e)
            throw IllegalStateException("Failed to create rewind generation request", e)
        }
    }

    override suspend fun getGenerationRequest(requestId: Uuid): RewindGenerationRequest? {
        // Use a collector to get the first element instead of firstOrNull extension
        var result: RewindGenerationRequestEntity? = null
        requestDao.getRequestById(requestId).collect { entity ->
            if (result == null) {
                result = entity
            }
        }
        return result?.toModel()
    }

    override fun observeGenerationStatus(requestId: Uuid): Flow<RewindGenerationRequest?> =
        requestDao.getRequestById(requestId).map { entity ->
            entity?.toModel()
        }

    override suspend fun isGenerationInProgress(
        startTime: Instant,
        endTime: Instant,
    ): Boolean {
        val request = requestDao.getRequestForPeriod(startTime, endTime) ?: return false
        return request.status == RewindGenerationRequestEntity.Status.PENDING ||
            request.status == RewindGenerationRequestEntity.Status.PROCESSING
    }

    override suspend fun cancelGeneration(requestId: Uuid): Boolean =
        try {
            val updated =
                requestDao.updateRequestStatus(
                    id = requestId,
                    status = RewindGenerationRequestEntity.Status.CANCELLED,
                    details = "Cancelled by user",
                )
            updated > 0
        } catch (e: Exception) {
            Napier.e("Failed to cancel rewind generation request", e)
            false
        }

    /**
     * Updates the status of a rewind generation request.
     *
     * This method is not part of the interface but is used internally and by the rewind
     * generation service to update the status of requests.
     *
     * @param id The unique identifier of the request
     * @param status The new status
     * @param details Optional details about the status change
     * @return True if the update was successful, false otherwise
     */
    override suspend fun updateRequestStatus(
        id: Uuid,
        status: RewindGenerationRequest.Status,
        details: String?,
    ): Boolean =
        try {
            // Convert from model status to entity status
            val entityStatus =
                when (status) {
                    RewindGenerationRequest.Status.PENDING -> RewindGenerationRequestEntity.Status.PENDING
                    RewindGenerationRequest.Status.PROCESSING -> RewindGenerationRequestEntity.Status.PROCESSING
                    RewindGenerationRequest.Status.COMPLETED -> RewindGenerationRequestEntity.Status.COMPLETED
                    RewindGenerationRequest.Status.FAILED -> RewindGenerationRequestEntity.Status.FAILED
                    RewindGenerationRequest.Status.CANCELLED -> RewindGenerationRequestEntity.Status.CANCELLED
                }
            val updated = requestDao.updateRequestStatus(id, entityStatus, details)
            updated > 0
        } catch (e: Exception) {
            Napier.e("Failed to update rewind generation request status", e)
            false
        }

    // Helper extension function to convert entity to model
    private fun RewindGenerationRequestEntity.toModel(): RewindGenerationRequest =
        RewindGenerationRequest(
            id = id,
            startTime = startTime,
            endTime = endTime,
            requestTime = requestTime,
            status =
                when (status) {
                    RewindGenerationRequestEntity.Status.PENDING -> RewindGenerationRequest.Status.PENDING
                    RewindGenerationRequestEntity.Status.PROCESSING -> RewindGenerationRequest.Status.PROCESSING
                    RewindGenerationRequestEntity.Status.COMPLETED -> RewindGenerationRequest.Status.COMPLETED
                    RewindGenerationRequestEntity.Status.FAILED -> RewindGenerationRequest.Status.FAILED
                    RewindGenerationRequestEntity.Status.CANCELLED -> RewindGenerationRequest.Status.CANCELLED
                },
            details = details,
        )
}
