package app.logdate.client.domain.location

import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.client.repository.location.LocationHistoryRepository
import app.logdate.client.repository.location.LocationLogRecord
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Unit tests for [ObserveLocationStopsUseCase].
 *
 * Validates the algorithm for identifying significant "stops" from a stream
 * of location history points. It ensures that points are correctly grouped
 * by proximity and time, while appropriately handling gaps in data or
 * specific capture sources.
 */
class ObserveLocationStopsUseCaseTest {
    @Test
    fun `current device snapshot excludes imported locations and retains its newest movement`() =
        runTest {
            val start = Instant.parse("2026-10-12T12:00:00Z")
            val local = listOf(0, 5, 10).map { minute -> historyItem(start + minute.minutes, 37.0, -122.0) }
            val moved = historyItem(start + 11.minutes, 38.0, -122.0)
            val foreign = historyItem(start + 12.minutes, 39.0, -122.0).copy(deviceId = "foreign")
            val repository = FakeLocationHistoryRepository(local + moved + foreign)
            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(repository))
            val snapshot = useCase.currentDevice("device").first()
            assertEquals(moved, snapshot.latest)
            assertTrue(snapshot.stops.all { it.location.latitude < 39.0 })
            assertTrue(snapshot.stops.any { it.startTime == start && it.hasReliableDuration })
        }

    @Test
    fun `manual journal and reviewed locations cannot be a current arrival fix`() =
        runTest {
            val now = Instant.parse("2026-10-12T12:00:00Z")
            val samples =
                listOf(
                    app.logdate.client.repository.location.LocationCaptureSource.MANUAL,
                    app.logdate.client.repository.location.LocationCaptureSource.JOURNAL_ENTRY,
                    app.logdate.client.repository.location.LocationCaptureSource.TIMELINE_REVIEW,
                ).map { source -> historyItem(now, 37.0, -122.0, source) }
            val snapshot =
                ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(FakeLocationHistoryRepository(samples)))
                    .currentDevice("device")
                    .first()
            assertEquals(null, snapshot.latest)
            assertTrue(snapshot.stops.isEmpty())
        }

    @Test
    fun `recent stops reconstruct only the window and keep a stay's real start`() =
        runTest {
            val cutoff = Instant.parse("2026-10-12T12:00:00Z")
            val oldStay = listOf(0, 5, 10).map { minute -> historyItem(cutoff - 3.days + minute.minutes, 40.0, -120.0) }
            val straddling = listOf(-20, -10, 5, 15).map { minute -> historyItem(cutoff + minute.minutes, 37.0, -122.0) }
            val repository = FakeLocationHistoryRepository(oldStay + straddling)

            val stops = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(repository)).since(cutoff).first()

            assertEquals(0, repository.fullHistoryObservations, "recent stops read the whole history")
            assertEquals(listOf(cutoff - 20.minutes), stops.map { it.startTime })
        }

    @Test
    fun `invoke groups nearby consecutive points into one stop`() =
        runTest {
            val baseTime = Instant.fromEpochMilliseconds(1_000)
            val repository =
                FakeLocationHistoryRepository(
                    listOf(
                        historyItem(timestamp = baseTime, latitude = 37.7749, longitude = -122.4194),
                        historyItem(timestamp = baseTime + 5.minutes, latitude = 37.7750, longitude = -122.4195),
                        historyItem(timestamp = baseTime + 10.minutes, latitude = 37.7751, longitude = -122.4196),
                    ),
                )

            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(repository))

            val result = useCase().first()

            assertEquals(1, result.size)
            assertEquals(3, result.first().sampleCount)
            assertEquals(baseTime, result.first().startTime)
            assertEquals(baseTime + 10.minutes, result.first().endTime)
            assertEquals(LocationStopEvidenceKind.STAY, result.first().evidenceKind)
            assertEquals(true, result.first().hasReliableDuration)
        }

    @Test
    fun `invoke splits stops when time gap exceeds threshold`() =
        runTest {
            val baseTime = Instant.fromEpochMilliseconds(1_000)
            val repository =
                FakeLocationHistoryRepository(
                    listOf(
                        historyItem(timestamp = baseTime, latitude = 37.7749, longitude = -122.4194),
                        historyItem(timestamp = baseTime + 5.minutes, latitude = 37.7750, longitude = -122.4195),
                        historyItem(timestamp = baseTime + 45.minutes, latitude = 37.7750, longitude = -122.4195),
                    ),
                )

            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(repository))

            val result = useCase().first()

            assertEquals(2, result.size)
            assertEquals(baseTime + 45.minutes, result.first().startTime)
            assertEquals(2, result.last().sampleCount)
            assertEquals(LocationStopEvidenceKind.OBSERVATION, result.first().evidenceKind)
        }

    @Test
    fun `invoke keeps stays at two places as separate stops`() =
        runTest {
            val baseTime = Instant.fromEpochMilliseconds(1_000)
            val repository =
                FakeLocationHistoryRepository(
                    listOf(0, 5, 10).map { historyItem(timestamp = baseTime + it.minutes, latitude = 37.7749, longitude = -122.4194) } +
                        listOf(
                            20,
                            25,
                            30,
                        ).map { historyItem(timestamp = baseTime + it.minutes, latitude = 37.8044, longitude = -122.2711) },
                )

            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(repository))

            val result = useCase().first()

            assertEquals(2, result.size)
            assertEquals(listOf(3, 3), result.map { it.sampleCount })
            assertTrue(result.all { it.evidenceKind == LocationStopEvidenceKind.STAY })
        }

    @Test
    fun `invoke does not turn a drive into stops`() =
        runTest {
            val baseTime = Instant.fromEpochMilliseconds(1_000)
            val drive =
                (0..90).map {
                    historyItem(timestamp = baseTime + (it * 10).seconds, latitude = 37.7749 + it * 0.0009, longitude = -122.4194)
                        .copy(accuracyMeters = 8f, speedMetersPerSecond = 10f, activityType = "IN_VEHICLE")
                }
            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(FakeLocationHistoryRepository(drive)))

            assertTrue(useCase().first().isEmpty())
        }

    @Test
    fun `invoke keeps a stay with noisy fixes as one stop`() =
        runTest {
            val baseTime = Instant.fromEpochMilliseconds(1_000)
            val offsets = listOf(0.0, 0.0006, -0.0004, 0.0035, 0.0002, -0.0007, 0.0001, 0.0005, -0.0003, 0.0)
            val stay =
                offsets.mapIndexed { index, offset ->
                    historyItem(timestamp = baseTime + (index * 3).minutes, latitude = 37.7749 + offset, longitude = -122.4194)
                        .copy(accuracyMeters = if (index == 3) 30f else 60f)
                }
            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(FakeLocationHistoryRepository(stay)))

            val result = useCase().first()

            assertEquals(1, result.size)
            assertEquals(offsets.size, result.single().sampleCount)
            assertEquals(LocationStopEvidenceKind.STAY, result.single().evidenceKind)
        }

    @Test
    fun `invoke joins samples recorded before the owner was known`() =
        runTest {
            val baseTime = Instant.fromEpochMilliseconds(1_000)
            val history =
                (0..10).map {
                    historyItem(timestamp = baseTime + (it * 3).minutes, latitude = 37.7749, longitude = -122.4194)
                        .copy(userId = if (it % 2 == 0) "default_user" else "user")
                }
            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(FakeLocationHistoryRepository(history)))

            val result = useCase().first()

            assertEquals(1, result.size)
            assertEquals(history.size, result.single().sampleCount)
        }

    @Test
    fun `invoke excludes timeline review captures from activity stops`() =
        runTest {
            val baseTime = Instant.fromEpochMilliseconds(1_000)
            val repository =
                FakeLocationHistoryRepository(
                    listOf(
                        historyItem(
                            timestamp = baseTime,
                            latitude = 37.7749,
                            longitude = -122.4194,
                            captureSource = app.logdate.client.repository.location.LocationCaptureSource.TIMELINE_REVIEW,
                        ),
                        historyItem(
                            timestamp = baseTime + 2.minutes,
                            latitude = 37.7750,
                            longitude = -122.4195,
                        ),
                    ),
                )

            val useCase = ObserveLocationStopsUseCase(ObserveLocationHistoryUseCase(repository))

            val result = useCase().first()

            assertEquals(1, result.size)
            assertEquals(1, result.first().sampleCount)
            assertEquals(LocationStopEvidenceKind.OBSERVATION, result.first().evidenceKind)
        }

    private fun historyItem(
        timestamp: Instant,
        latitude: Double,
        longitude: Double,
        captureSource: app.logdate.client.repository.location.LocationCaptureSource =
            app.logdate.client.repository.location.LocationCaptureSource.BACKGROUND_PERIODIC,
    ) = LocationHistoryItem(
        userId = "user",
        deviceId = "device",
        timestamp = timestamp,
        location =
            Location(
                latitude = latitude,
                longitude = longitude,
                altitude = LocationAltitude(0.0, AltitudeUnit.METERS),
            ),
        confidence = 1.0f,
        isGenuine = true,
        captureSource = captureSource,
    )

    private class FakeLocationHistoryRepository(
        history: List<LocationHistoryItem>,
    ) : LocationHistoryRepository {
        private val state = MutableStateFlow(history)

        override suspend fun getAllLocationHistory(): List<LocationHistoryItem> = state.value

        var fullHistoryObservations = 0
            private set

        override fun observeLocationHistory(): Flow<List<LocationHistoryItem>> {
            fullHistoryObservations++
            return state
        }

        override fun observeLocationHistoryBetween(
            startTime: Instant,
            endTime: Instant,
        ): Flow<List<LocationHistoryItem>> = state.map { items -> items.filter { it.timestamp >= startTime && it.timestamp < endTime } }

        override suspend fun getRecentLocationHistory(limit: Int): List<LocationHistoryItem> = state.value.take(limit)

        override suspend fun getLocationHistoryBetween(
            startTime: Instant,
            endTime: Instant,
        ): List<LocationHistoryItem> = state.value.filter { it.timestamp in startTime..endTime }

        override suspend fun getLastLocation(): LocationHistoryItem? = state.value.maxByOrNull { it.timestamp }

        override fun observeLastLocation(): Flow<LocationHistoryItem?> = flowOf(state.value.maxByOrNull { it.timestamp })

        override suspend fun logLocation(
            location: Location,
            userId: String,
            deviceId: String,
            confidence: Float,
            isGenuine: Boolean,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun logLocation(record: LocationLogRecord): Result<Unit> = Result.success(Unit)

        override suspend fun deleteLocationEntry(
            userId: String,
            deviceId: String,
            timestamp: Instant,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun deleteLocationsBetween(
            startTime: Instant,
            endTime: Instant,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getLocationCount(): Int = state.value.size
    }
}
