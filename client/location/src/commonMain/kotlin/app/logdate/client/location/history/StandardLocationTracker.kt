package app.logdate.client.location.history

import app.logdate.client.device.identity.CanonicalOwnerProvider
import app.logdate.client.location.ClientLocationProvider
import app.logdate.client.repository.location.LocationCapturePipeline
import app.logdate.client.repository.location.LocationCaptureSource
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.location.LocationLogRecord
import app.logdate.shared.model.Location
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Standard implementation of LocationTracker that combines a location provider
 * with a location history repository to track and manage location data.
 */
class StandardLocationTracker(
    private val locationProvider: ClientLocationProvider,
    private val locationHistoryRepository: LocationHistoryRepository,
    private val deviceId: String,
    private val userId: String = "default_user",
    private val canonicalOwnerProvider: CanonicalOwnerProvider? = null,
) : LocationTracker {
    override suspend fun getLastLocation(): LocationHistoryItem? = locationHistoryRepository.getLastLocation()

    override fun observeCurrentLocation(): Flow<LocationHistoryItem?> =
        locationHistoryRepository
            .observeLastLocation()
            .catch { e ->
                Napier.e("Error observing location", e)
                emit(null)
            }

    override suspend fun getLocationHistoryForDate(date: LocalDate): List<LocationHistoryItem> {
        val startTime = date.atStartOfDayIn(TimeZone.currentSystemDefault())
        val endTime =
            date
                .plus(kotlinx.datetime.DatePeriod(days = 1))
                .atStartOfDayIn(TimeZone.currentSystemDefault())

        val startInstant = startTime
        val endInstant = endTime
        return locationHistoryRepository.getLocationHistoryBetween(startInstant, endInstant)
    }

    override suspend fun getLocationHistoryBetween(
        start: Instant,
        end: Instant,
    ): List<LocationHistoryItem> = locationHistoryRepository.getLocationHistoryBetween(start, end)

    override suspend fun logCurrentLocation(): Result<LocationHistoryItem> =
        try {
            val fix = locationProvider.getCurrentFix()
            logLocation(fix.location, fix.observedAt, fix.evidence())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Napier.e("Failed to log current location", e)
            Result.failure(e)
        }

    override suspend fun logLocation(
        location: Location,
        timestamp: Instant,
        metadata: Map<String, Any>,
    ): Result<LocationHistoryItem> =
        try {
            val record = logRecord(location, timestamp, metadata, ownerId())
            val result = locationHistoryRepository.logLocation(record)

            if (result.isSuccess) {
                Result.success(record.toHistoryItem())
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Failed to log location"))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Napier.e("Failed to log location", e)
            Result.failure(e)
        }

    override suspend fun logLocations(readings: List<LocationReading>): Result<Unit> =
        try {
            val ownerId = ownerId()
            locationHistoryRepository.logLocations(readings.map { logRecord(it.location, it.timestamp, it.metadata, ownerId) })
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Napier.e("Failed to log ${readings.size} locations", e)
            Result.failure(e)
        }

    private suspend fun ownerId(): String = canonicalOwnerProvider?.getCanonicalOwnerId() ?: userId

    private fun logRecord(
        location: Location,
        timestamp: Instant,
        metadata: Map<String, Any>,
        ownerId: String,
    ): LocationLogRecord {
        val confidence = metadata["confidence"] as? Float ?: 1.0f
        val isGenuine = metadata["isGenuine"] as? Boolean ?: true
        val capturePipeline = metadata["capturePipeline"] as? LocationCapturePipeline ?: LocationCapturePipeline.LEGACY
        val captureSource = metadata["captureSource"] as? LocationCaptureSource ?: LocationCaptureSource.MANUAL
        val accuracyMeters = metadata["accuracyMeters"] as? Float
        val speedMetersPerSecond = metadata["speedMetersPerSecond"] as? Float
        val bearingDegrees = metadata["bearingDegrees"] as? Float
        val activityType = metadata["activityType"] as? String
        val timeZoneId = metadata["timeZoneId"] as? String
        val isMock = metadata["isMock"] as? Boolean ?: false
        val loggedAt = metadata["loggedAt"] as? Instant ?: Clock.System.now()
        return LocationLogRecord(
            userId = ownerId,
            deviceId = deviceId,
            timestamp = timestamp,
            loggedAt = loggedAt,
            location = location,
            confidence = confidence,
            isGenuine = isGenuine,
            capturePipeline = capturePipeline,
            captureSource = captureSource,
            accuracyMeters = accuracyMeters,
            speedMetersPerSecond = speedMetersPerSecond,
            bearingDegrees = bearingDegrees,
            isMock = isMock,
            activityType = activityType,
            timeZoneId = timeZoneId,
        )
    }

    override suspend fun deleteLocationEntry(historyItem: LocationHistoryItem): Result<Unit> =
        locationHistoryRepository.deleteLocationEntry(
            userId = historyItem.userId,
            deviceId = historyItem.deviceId,
            timestamp = historyItem.timestamp,
        )

    override suspend fun clearLocationHistory(
        start: Instant,
        end: Instant,
    ): Result<Unit> = locationHistoryRepository.deleteLocationsBetween(start, end)

    override suspend fun clearAllLocationHistory(): Result<Unit> =
        try {
            // Since the repository doesn't have a direct method for this,
            // we use a very wide time range
            val startTime = Instant.fromEpochMilliseconds(0) // Beginning of time
            val endTime = Instant.fromEpochMilliseconds(Long.MAX_VALUE) // Far future
            locationHistoryRepository.deleteLocationsBetween(startTime, endTime)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Napier.e("Failed to clear all location history", e)
            Result.failure(e)
        }
}

private fun LocationLogRecord.toHistoryItem(): LocationHistoryItem =
    LocationHistoryItem(
        sampleId = sampleId,
        userId = userId,
        deviceId = deviceId,
        timestamp = timestamp,
        loggedAt = loggedAt,
        location = location,
        confidence = confidence,
        isGenuine = isGenuine,
        capturePipeline = capturePipeline,
        captureSource = captureSource,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        bearingDegrees = bearingDegrees,
        isMock = isMock,
        activityType = activityType,
        timeZoneId = timeZoneId,
    )
