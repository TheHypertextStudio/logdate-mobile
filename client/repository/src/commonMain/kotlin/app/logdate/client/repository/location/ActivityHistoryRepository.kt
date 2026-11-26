package app.logdate.client.repository.location

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** Movement evidence reported by the device; this does not identify a vehicle or transit mode. */
data class ActivityHistoryItem(
    val id: String,
    val userId: String,
    val deviceId: String,
    val timestamp: Instant,
    val recordedAt: Instant,
    val activityType: String,
    val transitionType: String,
    val timeZoneId: String?,
)

interface ActivityHistoryRepository {
    fun observeActivityHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<ActivityHistoryItem>>

    suspend fun getActivityHistoryPage(
        userId: String,
        deviceId: String,
        afterRecordedAt: Instant,
        afterId: String,
        limit: Int = 500,
    ): List<ActivityHistoryItem> = emptyList()

    suspend fun latestBefore(
        userId: String,
        deviceId: String,
        before: Instant,
    ): ActivityHistoryItem? = null

    suspend fun latestForDevice(
        deviceId: String,
        since: Instant,
    ): ActivityHistoryItem? = null

    suspend fun recordActivity(item: ActivityHistoryItem): Result<Unit>

    suspend fun deleteActivityHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    )
}
