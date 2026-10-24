package app.logdate.client.domain.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Use case to observe the status of location logging retry operations.
 *
 * This can be used by UI components to show users when location logging
 * is being retried in the background, or for debugging purposes.
 */
class ObserveLocationRetryStatusUseCase(
    private val locationRetryWorker: LocationRetryWorker,
) {
    /**
     * Observes location retry status based on the requested observation type
     */
    operator fun invoke(request: RetryStatusRequest): Flow<RetryStatusResult> =
        when (request) {
            is RetryStatusRequest.FullStatus -> {
                locationRetryWorker.pendingRetries.map { pendingLogs ->
                    RetryStatusResult.FullStatus(
                        RetryStatus(
                            totalPendingRetries = pendingLogs.size,
                            nextRetryTime = pendingLogs.minByOrNull { it.scheduledTime }?.scheduledTime,
                            failedAttempts = pendingLogs.sumOf { it.attemptNumber - 1 },
                        ),
                    )
                }
            }

            is RetryStatusRequest.PendingCount -> {
                locationRetryWorker.pendingRetries.map { pendingLogs ->
                    RetryStatusResult.PendingCount(pendingLogs.size)
                }
            }

            is RetryStatusRequest.HasActiveRetries -> {
                locationRetryWorker.pendingRetries.map { pendingLogs ->
                    RetryStatusResult.HasActiveRetries(pendingLogs.isNotEmpty())
                }
            }
        }

    sealed class RetryStatusRequest {
        object FullStatus : RetryStatusRequest()

        object PendingCount : RetryStatusRequest()

        object HasActiveRetries : RetryStatusRequest()
    }

    sealed class RetryStatusResult {
        data class FullStatus(
            val status: RetryStatus,
        ) : RetryStatusResult()

        data class PendingCount(
            val count: Int,
        ) : RetryStatusResult()

        data class HasActiveRetries(
            val hasRetries: Boolean,
        ) : RetryStatusResult()
    }
}
