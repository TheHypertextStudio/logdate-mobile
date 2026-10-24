package app.logdate.client.domain.location

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.device.identity.DeviceIdProvider
import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.location.LocationLogRecord
import kotlin.time.Clock

/**
 * Use case to log the current location to location history with automatic
 * retry functionality using exponential backoff for failed attempts.
 *
 * This is typically called when significant events occur (like creating notes)
 * to maintain a timeline of where the user was when they performed actions.
 */
class LogCurrentLocationUseCase(
    private val locationProvider: ClientLocationProvider,
    private val locationHistoryRepository: LocationHistoryRepository,
    private val locationRetryWorker: LocationRetryWorker,
    private val canonicalOwnerProvider: CanonicalOwnerProvider,
    private val deviceIdProvider: DeviceIdProvider,
) {
    /**
     * Handles location logging operations based on the request type
     */
    suspend operator fun invoke(request: LocationLogRequest): LocationLogResult =
        when (request) {
            is LocationLogRequest.LogLocation -> {
                try {
                    val fix = locationProvider.getCurrentFix()
                    val record =
                        LocationLogRecord(
                            userId = canonicalOwnerProvider.getCanonicalOwnerId(),
                            deviceId = deviceIdProvider.getDeviceId().value.toString(),
                            timestamp = fix.observedAt,
                            loggedAt = Clock.System.now(),
                            location = fix.location,
                            confidence = 1.0f,
                            isGenuine = true,
                            capturePipeline = request.capturePipeline,
                            captureSource = request.captureSource,
                            accuracyMeters = fix.accuracyMeters,
                            speedMetersPerSecond = fix.speedMetersPerSecond,
                            bearingDegrees = fix.bearingDegrees,
                            isMock = fix.isMock,
                        )
                    val result =
                        locationHistoryRepository.logLocation(record)

                    if (result.isSuccess) {
                        LocationLogResult.LogSuccess
                    } else {
                        scheduleBackgroundRetry(record)
                        LocationLogResult.LogSuccess
                    }
                } catch (e: Exception) {
                    LocationLogResult.LogFailure(e)
                }
            }

            is LocationLogRequest.GetRetryStatus -> {
                val status = locationRetryWorker.getRetryStatus()
                LocationLogResult.RetryStatusInfo(status)
            }

            is LocationLogRequest.CancelAllRetries -> {
                locationRetryWorker.cancelAllRetries()
                LocationLogResult.RetriesCancelled
            }
        }

    private fun scheduleBackgroundRetry(record: LocationLogRecord) {
        locationRetryWorker.scheduleRetry(
            record = record,
            attemptNumber = 1,
        )
    }

    sealed class LocationLogRequest {
        data class LogLocation(
            val capturePipeline: LocationCapturePipeline = LocationCapturePipeline.LEGACY,
            val captureSource: LocationCaptureSource = LocationCaptureSource.MANUAL,
        ) : LocationLogRequest()

        object GetRetryStatus : LocationLogRequest()

        object CancelAllRetries : LocationLogRequest()
    }

    sealed class LocationLogResult {
        object LogSuccess : LocationLogResult()

        data class LogFailure(
            val error: Throwable,
        ) : LocationLogResult()

        data class RetryStatusInfo(
            val status: RetryStatus,
        ) : LocationLogResult()

        object RetriesCancelled : LocationLogResult()
    }
}
