package app.logdate.client.location.history

import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant

/** One location reading to store, with the capture details [LocationTracker.logLocation] accepts. */
data class LocationReading(
    val location: Location,
    val timestamp: Instant,
    val metadata: Map<String, Any> = emptyMap(),
)

/**
 * Interface for user location tracking and history management.
 *
 * This interface provides methods for tracking, logging, retrieving,
 * and managing the user's location history across time.
 */
interface LocationTracker {
    /**
     * Gets the most recent location entry.
     */
    suspend fun getLastLocation(): LocationHistoryItem?

    /**
     * Observes the most recent location as a flow.
     */
    fun observeCurrentLocation(): Flow<LocationHistoryItem?>

    /**
     * Gets all location history entries for a specific date.
     */
    suspend fun getLocationHistoryForDate(date: LocalDate): List<LocationHistoryItem>

    /**
     * Gets all location history entries between two timestamps.
     */
    suspend fun getLocationHistoryBetween(
        start: Instant,
        end: Instant,
    ): List<LocationHistoryItem>

    /**
     * Logs the current location from the location provider.
     */
    suspend fun logCurrentLocation(): Result<LocationHistoryItem>

    /**
     * Logs a specific location with optional metadata.
     */
    suspend fun logLocation(
        location: Location,
        timestamp: Instant = Clock.System.now(),
        metadata: Map<String, Any> = emptyMap(),
    ): Result<LocationHistoryItem>

    /**
     * Logs several readings in arrival order. Trackers that can store them in one write override this.
     */
    suspend fun logLocations(readings: List<LocationReading>): Result<Unit> {
        readings.forEach { reading ->
            logLocation(reading.location, reading.timestamp, reading.metadata).onFailure { return Result.failure(it) }
        }
        return Result.success(Unit)
    }

    /**
     * Deletes a specific location history entry.
     */
    suspend fun deleteLocationEntry(historyItem: LocationHistoryItem): Result<Unit>

    /**
     * Clears location history between two timestamps.
     */
    suspend fun clearLocationHistory(
        start: Instant,
        end: Instant,
    ): Result<Unit>

    /**
     * Clears all location history.
     */
    suspend fun clearAllLocationHistory(): Result<Unit>
}
