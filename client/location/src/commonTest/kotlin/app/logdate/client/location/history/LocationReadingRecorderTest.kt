package app.logdate.client.location.history

import app.logdate.client.location.settings.LocationTrackingSettings
import app.logdate.client.location.settings.LocationTrackingSettingsRepository
import app.logdate.client.repository.location.LocationHistoryItem
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class LocationReadingRecorderTest {
    @Test
    fun `fixes that arrive during a write are stored together in the next write`() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = RecordingTracker()
            val firstWrite = CompletableDeferred<Unit>()
            tracker.beforeWrite = { if (tracker.batches.size == 1) firstWrite.await() }
            val recorder = LocationReadingRecorder(backgroundScope, Settings(enabled = true), tracker)

            recorder.record(listOf(fix(1)))
            recorder.record(listOf(fix(2), fix(3)))
            recorder.record(listOf(fix(4)))
            firstWrite.complete(Unit)
            runCurrent()

            assertEquals(
                listOf(listOf(1L), listOf(2L, 3L, 4L)),
                tracker.batches.map { batch -> batch.map { it.timestamp.toEpochMilliseconds() } },
            )
        }

    @Test
    fun `the recording setting is observed once instead of read for every fix`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = Settings(enabled = true)
            val recorder = LocationReadingRecorder(backgroundScope, settings, RecordingTracker())

            repeat(5) { recorder.record(listOf(fix(it.toLong()))) }
            runCurrent()

            assertEquals(0, settings.reads)
            assertEquals(1, settings.subscriptions)
        }

    @Test
    fun `fixes are dropped while recording is off`() =
        runTest(UnconfinedTestDispatcher()) {
            val settings = Settings(enabled = false)
            val tracker = RecordingTracker()
            val recorder = LocationReadingRecorder(backgroundScope, settings, tracker)

            recorder.record(listOf(fix(1)))
            runCurrent()
            settings.state.value = LocationTrackingSettings(backgroundTrackingEnabled = true)
            recorder.record(listOf(fix(2)))
            runCurrent()

            assertEquals(listOf(listOf(2L)), tracker.batches.map { batch -> batch.map { it.timestamp.toEpochMilliseconds() } })
        }

    private fun fix(millis: Long) =
        LocationReading(
            location = Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
            timestamp = Instant.fromEpochMilliseconds(millis),
        )

    private class Settings(
        enabled: Boolean,
    ) : LocationTrackingSettingsRepository {
        val state = MutableStateFlow(LocationTrackingSettings(backgroundTrackingEnabled = enabled))
        var reads = 0
        var subscriptions = 0

        override suspend fun getSettings(): LocationTrackingSettings {
            reads++
            return state.value
        }

        override fun observeSettings(): Flow<LocationTrackingSettings> = state.onStart { subscriptions++ }

        override suspend fun updateSettings(settings: LocationTrackingSettings) {
            state.value = settings
        }

        override suspend fun setBackgroundTrackingEnabled(enabled: Boolean) {
            state.value = state.value.copy(backgroundTrackingEnabled = enabled)
        }
    }

    private class RecordingTracker : LocationTracker {
        val batches = mutableListOf<List<LocationReading>>()
        var beforeWrite: suspend () -> Unit = {}

        override suspend fun logLocations(readings: List<LocationReading>): Result<Unit> {
            batches += readings
            beforeWrite()
            return Result.success(Unit)
        }

        override suspend fun getLastLocation(): LocationHistoryItem? = null

        override fun observeCurrentLocation(): Flow<LocationHistoryItem?> = emptyFlow()

        override suspend fun getLocationHistoryForDate(date: LocalDate): List<LocationHistoryItem> = emptyList()

        override suspend fun getLocationHistoryBetween(
            start: Instant,
            end: Instant,
        ): List<LocationHistoryItem> = emptyList()

        override suspend fun logCurrentLocation(): Result<LocationHistoryItem> = error("Not used")

        override suspend fun logLocation(
            location: Location,
            timestamp: Instant,
            metadata: Map<String, Any>,
        ): Result<LocationHistoryItem> = error("Fixes must be stored in batches")

        override suspend fun deleteLocationEntry(historyItem: LocationHistoryItem): Result<Unit> = Result.success(Unit)

        override suspend fun clearLocationHistory(
            start: Instant,
            end: Instant,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun clearAllLocationHistory(): Result<Unit> = Result.success(Unit)
    }
}
