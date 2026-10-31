package app.logdate.client.data.location

import androidx.room.Room
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.repository.location.ActivityHistoryItem
import app.logdate.client.repository.location.LocationLogRecord
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class LocationEvidenceRepositoryTest {
    @Test
    fun sampleAndIndependentActivitySurviveRepositoryRoundTrip() =
        runTest {
            val file = Files.createTempFile("location-evidence", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val samples = OfflineFirstLocationHistoryRepository(database.locationHistoryDao(), database.locationActivityDao())
                val activities = OfflineFirstActivityHistoryRepository(database.locationActivityDao())
                val start = Instant.fromEpochMilliseconds(1000)
                val end = Instant.fromEpochMilliseconds(3000)
                val record =
                    LocationLogRecord(
                        sampleId = "sample",
                        userId = "owner",
                        deviceId = "device",
                        timestamp = start,
                        loggedAt = end,
                        location = Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                        confidence = 0.7f,
                        isGenuine = true,
                        accuracyMeters = 12f,
                        speedMetersPerSecond = 4f,
                        bearingDegrees = 120f,
                        isMock = true,
                        activityType = "ON_BICYCLE",
                        timeZoneId = "America/Los_Angeles",
                    )
                samples.logLocation(record).getOrThrow()
                samples.logLocation(record.copy(sampleId = "boundary", timestamp = end)).getOrThrow()
                val stored = samples.observeLocationHistoryBetween(start, end).first().single()
                assertEquals(record.timestamp, stored.timestamp)
                assertEquals(record.loggedAt, stored.loggedAt)
                assertEquals(record.userId, stored.userId)
                assertEquals(record.activityType, stored.activityType)
                assertEquals(record.timeZoneId, stored.timeZoneId)
                assertEquals(record.accuracyMeters, stored.accuracyMeters)
                assertEquals(record.speedMetersPerSecond, stored.speedMetersPerSecond)
                assertEquals(record.bearingDegrees, stored.bearingDegrees)
                assertEquals(record.isMock, stored.isMock)
                val event = ActivityHistoryItem("event", "owner", "device", start, end, "ON_BICYCLE", "EXIT", "America/Los_Angeles")
                activities.recordActivity(event).getOrThrow()
                assertEquals(listOf(event), activities.observeActivityHistoryBetween(start, end).first())
                activities.deleteActivityHistoryBetween(start, end)
                assertEquals(emptyList(), activities.observeActivityHistoryBetween(start, end).first())
                assertEquals(2, samples.getLocationCount())
                activities.recordActivity(event).getOrThrow()
                samples.deleteLocationsBetween(start, end).getOrThrow()
                assertEquals(emptyList(), activities.observeActivityHistoryBetween(start, end).first())
                assertEquals(1, samples.getLocationCount())
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun batchedSamplesAreStoredTogetherWithTheirEvidence() =
        runTest {
            val file = Files.createTempFile("location-batch", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val samples = OfflineFirstLocationHistoryRepository(database.locationHistoryDao())
                val records =
                    (1..3).map { index ->
                        LocationLogRecord(
                            sampleId = "sample-$index",
                            userId = "owner",
                            deviceId = "device",
                            timestamp = Instant.fromEpochMilliseconds(index * 1000L),
                            loggedAt = Instant.fromEpochMilliseconds(index * 1000L + 5),
                            location = Location(36.0 + index, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                            confidence = 0.7f,
                            isGenuine = true,
                            accuracyMeters = index.toFloat(),
                            activityType = "WALKING",
                        )
                    }

                samples.logLocations(records).getOrThrow()

                val stored =
                    samples
                        .observeLocationHistoryBetween(
                            Instant.fromEpochMilliseconds(0),
                            Instant.fromEpochMilliseconds(10_000),
                        ).first()
                assertEquals(records.map { it.sampleId }, stored.sortedBy { it.timestamp }.map { it.sampleId })
                assertEquals(listOf(1f, 2f, 3f), stored.sortedBy { it.timestamp }.map { it.accuracyMeters })
                assertEquals(setOf("WALKING"), stored.map { it.activityType }.toSet())
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun exportPagesAreOwnerAndDeviceScopedAndContinueThroughTimestampTies() =
        runTest {
            val file = Files.createTempFile("location-pages", ".db")
            val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                val samples = OfflineFirstLocationHistoryRepository(database.locationHistoryDao())
                val activities = OfflineFirstActivityHistoryRepository(database.locationActivityDao())
                val instant = Instant.fromEpochMilliseconds(1000)
                val record =
                    LocationLogRecord(
                        sampleId = "a",
                        userId = "owner",
                        deviceId = "device",
                        timestamp = instant,
                        loggedAt = instant,
                        location = Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                        confidence = 1f,
                        isGenuine = true,
                    )
                val activity = ActivityHistoryItem("a", "owner", "device", instant, instant, "STILL", "ENTER", "UTC")
                for (id in listOf("a", "b", "c")) {
                    samples.logLocation(record.copy(sampleId = id)).getOrThrow()
                    activities.recordActivity(activity.copy(id = id)).getOrThrow()
                }
                samples.logLocation(record.copy(sampleId = "other-owner", userId = "other")).getOrThrow()
                samples.logLocation(record.copy(sampleId = "other-device", deviceId = "other")).getOrThrow()
                activities.recordActivity(activity.copy(id = "other-owner", userId = "other")).getOrThrow()
                activities.recordActivity(activity.copy(id = "other-device", deviceId = "other")).getOrThrow()
                val receivedLater = Instant.fromEpochMilliseconds(2000)
                samples
                    .logLocation(
                        record.copy(sampleId = "delayed", timestamp = Instant.fromEpochMilliseconds(1), loggedAt = receivedLater),
                    ).getOrThrow()
                activities
                    .recordActivity(
                        activity.copy(id = "delayed", timestamp = Instant.fromEpochMilliseconds(1), recordedAt = receivedLater),
                    ).getOrThrow()
                assertEquals(listOf("delayed"), samples.getLocationHistoryPage("owner", "device", instant, "c", 2).map { it.sampleId })
                assertEquals(listOf("delayed"), activities.getActivityHistoryPage("owner", "device", instant, "c", 2).map { it.id })
                assertEquals(listOf("a", "b"), samples.getLocationHistoryPage("owner", "device", instant, "", 2).map { it.sampleId })
                assertEquals(listOf("c", "delayed"), samples.getLocationHistoryPage("owner", "device", instant, "b", 2).map { it.sampleId })
                assertEquals(listOf("a", "b"), activities.getActivityHistoryPage("owner", "device", instant, "", 2).map { it.id })
                assertEquals(listOf("c", "delayed"), activities.getActivityHistoryPage("owner", "device", instant, "b", 2).map { it.id })
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }
}
