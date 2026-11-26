package app.logdate.client.data.location

import app.logdate.client.database.dao.LocationActivityDao
import app.logdate.client.database.entities.LocationActivityEntity
import app.logdate.client.repository.location.ActivityHistoryItem
import app.logdate.client.repository.location.ActivityHistoryRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

class OfflineFirstActivityHistoryRepository(
    private val dao: LocationActivityDao,
) : ActivityHistoryRepository {
    override fun observeActivityHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<ActivityHistoryItem>> =
        dao.observeBetween(startTime, endTime).map { rows ->
            rows.map { row ->
                ActivityHistoryItem(
                    row.id,
                    row.userId,
                    row.deviceId,
                    row.timestamp,
                    row.recordedAt,
                    row.activityType,
                    row.transitionType,
                    row.timeZoneId,
                )
            }
        }

    override suspend fun getActivityHistoryPage(
        userId: String,
        deviceId: String,
        afterRecordedAt: Instant,
        afterId: String,
        limit: Int,
    ): List<ActivityHistoryItem> =
        dao.getPage(userId, deviceId, afterRecordedAt, afterId, limit.coerceIn(1, 1000)).map { row ->
            ActivityHistoryItem(
                row.id,
                row.userId,
                row.deviceId,
                row.timestamp,
                row.recordedAt,
                row.activityType,
                row.transitionType,
                row.timeZoneId,
            )
        }

    override suspend fun latestBefore(
        userId: String,
        deviceId: String,
        before: Instant,
    ): ActivityHistoryItem? =
        dao.latestBefore(userId, deviceId, before)?.let { row ->
            ActivityHistoryItem(
                row.id,
                row.userId,
                row.deviceId,
                row.timestamp,
                row.recordedAt,
                row.activityType,
                row.transitionType,
                row.timeZoneId,
            )
        }

    override suspend fun latestForDevice(
        deviceId: String,
        since: Instant,
    ): ActivityHistoryItem? =
        dao.latestForDevice(deviceId, since)?.let { row ->
            ActivityHistoryItem(
                row.id,
                row.userId,
                row.deviceId,
                row.timestamp,
                row.recordedAt,
                row.activityType,
                row.transitionType,
                row.timeZoneId,
            )
        }

    override suspend fun recordActivity(item: ActivityHistoryItem): Result<Unit> =
        try {
            dao.insert(
                LocationActivityEntity(
                    item.id,
                    item.userId,
                    item.deviceId,
                    item.timestamp,
                    item.recordedAt,
                    item.activityType,
                    item.transitionType,
                    item.timeZoneId,
                ),
            )
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Napier.w("Failed to save movement evidence", error)
            Result.failure(error)
        }

    override suspend fun deleteActivityHistoryBetween(
        startTime: Instant,
        endTime: Instant,
    ) = dao.deleteBetween(startTime, endTime)
}
