package app.logdate.client.repository.location

import app.logdate.client.repository.journals.JournalMergeScope
import app.logdate.shared.model.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Repository for managing location history and logging.
 */
interface LocationHistoryRepository {
    suspend fun restoreLocationForArchive(
        record: LocationLogRecord,
        scope: JournalMergeScope?,
        recoveryId: String,
    ): Result<Unit> = logLocation(record)

    /**
     * Gets all location history, ordered by most recent first.
     */
    suspend fun getAllLocationHistory(): List<LocationHistoryItem>

    /**
     * Observes all location history.
     */
    fun observeLocationHistory(): Flow<List<LocationHistoryItem>>

    /** Observes samples in [startTime, endTime), without including the following day. */
    fun observeLocationHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<LocationHistoryItem>> =
        observeLocationHistory().map { items -> items.filter { it.timestamp >= startTime && it.timestamp < endTime } }

    suspend fun getLocationHistoryBefore(
        userId: String,
        deviceId: String,
        before: Instant,
        beforeSampleId: String,
        limit: Int = 256,
    ): List<LocationHistoryItem> = emptyList()

    /** Reads one owner/device page strictly after the ingestion time and sample-id cursor. */
    suspend fun getLocationHistoryPage(
        userId: String,
        deviceId: String,
        afterLoggedAt: Instant,
        afterSampleId: String,
        limit: Int = 500,
    ): List<LocationHistoryItem> = emptyList()

    /**
     * Gets recent location history with a limit.
     */
    suspend fun getRecentLocationHistory(limit: Int = 50): List<LocationHistoryItem>

    /**
     * Gets location history between two timestamps.
     */
    suspend fun getLocationHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): List<LocationHistoryItem>

    /**
     * Gets the most recent location.
     */
    suspend fun getLastLocation(): LocationHistoryItem?

    /**
     * Observes the most recent location.
     */
    fun observeLastLocation(): Flow<LocationHistoryItem?>

    /**
     * Logs a new location entry.
     */
    suspend fun logLocation(
        location: Location,
        userId: String,
        deviceId: String,
        confidence: Float = 1.0f,
        isGenuine: Boolean = true,
    ): Result<Unit>

    /**
     * Logs several samples in order. Stores that can write them in one statement override this.
     */
    suspend fun logLocations(records: List<LocationLogRecord>): Result<Unit> {
        records.forEach { record -> logLocation(record).onFailure { return Result.failure(it) } }
        return Result.success(Unit)
    }

    /**
     * Logs a rich location sample with capture metadata.
     */
    suspend fun logLocation(record: LocationLogRecord): Result<Unit> =
        logLocation(
            location = record.location,
            userId = record.userId,
            deviceId = record.deviceId,
            confidence = record.confidence,
            isGenuine = record.isGenuine,
        )

    /**
     * Deletes a specific location entry.
     */
    suspend fun deleteLocationEntry(
        userId: String,
        deviceId: String,
        timestamp: Instant,
    ): Result<Unit>

    /**
     * Deletes location entries within a time range.
     */
    suspend fun deleteLocationsBetween(
        startTime: Instant,
        endTime: Instant,
    ): Result<Unit>

    /**
     * Gets total count of location entries.
     */
    suspend fun getLocationCount(): Int
}

enum class LocationCapturePipeline {
    LEGACY,
    OPTIMIZED_BACKGROUND,
    HIGH_DETAIL,
}

enum class LocationCaptureSource {
    BACKGROUND_PERIODIC,
    PASSIVE_UPDATE,
    ACTIVITY_TRANSITION,
    GEOFENCE_TRANSITION,
    SERVER_NUDGE,
    FOREGROUND_STREAM,
    JOURNAL_ENTRY,
    TIMELINE_REVIEW,
    MANUAL,
    RETRY,
}

data class LocationLogRecord(
    val sampleId: String = Uuid.random().toString(),
    val userId: String,
    val deviceId: String,
    val timestamp: Instant,
    val loggedAt: Instant,
    val location: Location,
    val confidence: Float,
    val isGenuine: Boolean,
    val capturePipeline: LocationCapturePipeline = LocationCapturePipeline.LEGACY,
    val captureSource: LocationCaptureSource = LocationCaptureSource.BACKGROUND_PERIODIC,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val isMock: Boolean = false,
    val activityType: String? = null,
    val timeZoneId: String? = null,
)

/**
 * Domain model for location history item.
 */
data class LocationHistoryItem(
    val sampleId: String = Uuid.random().toString(),
    val userId: String,
    val deviceId: String,
    val timestamp: Instant,
    val loggedAt: Instant = timestamp,
    val location: Location,
    val confidence: Float,
    val isGenuine: Boolean,
    val capturePipeline: LocationCapturePipeline = LocationCapturePipeline.LEGACY,
    val captureSource: LocationCaptureSource = LocationCaptureSource.BACKGROUND_PERIODIC,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
    val isMock: Boolean = false,
    val activityType: String? = null,
    val timeZoneId: String? = null,
)
