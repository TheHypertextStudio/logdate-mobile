package app.logdate.client.domain.location

import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Use case to observe location history changes.
 */
class ObserveLocationHistoryUseCase(
    private val locationHistoryRepository: LocationHistoryRepository,
) {
    operator fun invoke(): Flow<List<LocationHistoryItem>> = locationHistoryRepository.observeLocationHistory()

    /** Observes only the samples in [startTime, endTime), read by time from storage. */
    fun between(
        startTime: Instant,
        endTime: Instant,
    ): Flow<List<LocationHistoryItem>> = locationHistoryRepository.observeLocationHistoryBetween(startTime, endTime)
}
