package app.logdate.client.data.location

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.HistoryRecordEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.repository.location.HistoryRecord
import app.logdate.client.repository.location.LocationLogRecord
import app.logdate.shared.model.AltitudeUnit
import app.logdate.shared.model.Location
import app.logdate.shared.model.LocationAltitude
import app.logdate.shared.model.location.HistoryPayload
import app.logdate.shared.model.location.LocationObservation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryRecordStoreTest {
    private val owner = "owner"
    private val origin = "https://example.test"

    @Test
    fun captureCursorPagesUseTimestampAndIdAndExcludeOtherOwnersDevicesAndTombstones() =
        runTest {
            withStore { database, store ->
                val samples = OfflineFirstLocationHistoryRepository(database.locationHistoryDao())
                val time = Instant.fromEpochMilliseconds(1000)
                for (id in listOf("a", "b", "c", "deleted")) {
                    val observation = LocationObservation(id, owner, "phone", time, 36.0, -115.0)
                    store.put(
                        owner,
                        origin,
                        HistoryRecord(
                            "observation:$id",
                            "observation",
                            Json.encodeToString(HistoryPayload.serializer(), HistoryPayload.Observation(observation)),
                            "phone",
                            deleted =
                                id == "deleted",
                        ),
                    )
                    samples
                        .logLocation(
                            LocationLogRecord(
                                id,
                                owner,
                                "phone",
                                time,
                                time,
                                Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                                1f,
                                true,
                            ),
                        ).getOrThrow()
                }
                val other = HistoryPayload.Observation(LocationObservation("other", owner, "another-device", time, 36.0, -115.0))
                store.put(
                    owner,
                    origin,
                    HistoryRecord(
                        "observation:other",
                        "observation",
                        Json.encodeToString(HistoryPayload.serializer(), other),
                        "another-device",
                    ),
                )
                assertEquals(
                    listOf("observation:c", "observation:b"),
                    store.observationsBefore(owner, origin, "phone", 1001, "", 2).map { it.id },
                )
                assertEquals(listOf("observation:a"), store.observationsBefore(owner, origin, "phone", 1000, "b", 2).map { it.id })
                assertTrue(store.observationsBefore("another-owner", origin, "phone", 1001, "", 2).isEmpty())
                assertEquals(listOf("c", "b"), samples.getLocationHistoryBefore(owner, "phone", time, "deleted", 2).map { it.sampleId })
                assertEquals(listOf("a"), samples.getLocationHistoryBefore(owner, "phone", time, "b", 2).map { it.sampleId })
                assertTrue(samples.getLocationHistoryBefore(owner, "another-device", time, "deleted", 2).isEmpty())
            }
        }

    @Test
    fun failedLocalBatchRollsBackEarlierTombstonesCorrectionsAndRawCleanup() =
        runTest {
            val file = Files.createTempFile("history-batch", ".db")
            var database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
            try {
                var store: RoomHistoryRecordStore
                var samples = OfflineFirstLocationHistoryRepository(database.locationHistoryDao())
                val point =
                    LocationLogRecord(
                        "first",
                        owner,
                        "phone",
                        Instant.fromEpochMilliseconds(1),
                        Instant.fromEpochMilliseconds(1),
                        Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                        1f,
                        true,
                    )
                samples.logLocation(point).getOrThrow()
                database.close()
                BundledSQLiteDriver().open(file.toString()).use { connection ->
                    connection.execSQL(
                        "CREATE TRIGGER fail_second BEFORE INSERT ON history_records " +
                            "WHEN NEW.id = 'observation:second' BEGIN SELECT RAISE(ABORT, 'test failure'); END",
                    )
                }
                database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
                store = RoomHistoryRecordStore(database.historyRecordDao())
                samples = OfflineFirstLocationHistoryRepository(database.locationHistoryDao())
                val deletion = HistoryRecord("observation:first", "observation", null, "phone", deleted = true)
                assertFailsWith<Exception> {
                    store.putBatch(
                        owner,
                        origin,
                        listOf(
                            HistoryRecord("correction", "correction", "suppression", "phone"),
                            deletion,
                            deletion.copy(id = "observation:second"),
                        ),
                    )
                }
                assertTrue(store.records(owner, origin).isEmpty())
                assertEquals(listOf("first"), samples.getAllLocationHistory().map { it.sampleId })
            } finally {
                database.close()
                Files.deleteIfExists(file)
            }
        }

    @Test
    fun localAndDownloadedTombstonesDeleteOnlyTheirOwnersOriginalDeviceRawSample() =
        runTest {
            withStore { database, store ->
                val samples = OfflineFirstLocationHistoryRepository(database.locationHistoryDao())
                val point =
                    LocationLogRecord(
                        "sample",
                        owner,
                        "phone",
                        Instant.fromEpochMilliseconds(1),
                        Instant.fromEpochMilliseconds(1),
                        Location(36.0, -115.0, LocationAltitude(0.0, AltitudeUnit.METERS)),
                        1f,
                        true,
                    )
                samples.logLocation(point).getOrThrow()
                val tombstone = HistoryRecord("observation:sample", "observation", null, "phone", deleted = true)
                store.put(owner, origin, tombstone)
                assertTrue(samples.getAllLocationHistory().isEmpty())
                samples.logLocation(point.copy(userId = "default_user")).getOrThrow()
                store.applyPage(owner, origin, listOf(tombstone.copy(serverVersion = 2)), 2)
                assertTrue(samples.getAllLocationHistory().isEmpty())
                samples.logLocation(point.copy(userId = "unrelated")).getOrThrow()
                store.applyPage(owner, origin, listOf(tombstone.copy(serverVersion = 3)), 3)
                assertEquals("unrelated", samples.getAllLocationHistory().single().userId)
                samples.deleteLocationEntry("unrelated", "phone", point.timestamp)
                samples.logLocation(point.copy(userId = "default_user", deviceId = "another-device")).getOrThrow()
                store.put(owner, origin, tombstone.copy(serverVersion = 3))
                assertEquals("another-device", samples.getAllLocationHistory().single().deviceId)
            }
        }

    @Test
    fun acknowledgementOnlyClearsTheUploadedVersionAndNeverRegressesServerVersion() =
        runTest {
            withStore { _, store ->
                store.put(owner, origin, record(version = 2, server = 10))
                store.acknowledge(owner, origin, "id", 1, 9)
                assertTrue(store.record(owner, origin, "id")!!.dirty)
                assertEquals(10L, store.record(owner, origin, "id")!!.serverVersion)
                store.acknowledge(owner, origin, "id", 2, 9)
                assertTrue(store.record(owner, origin, "id")!!.dirty)
                store.acknowledge(owner, origin, "id", 2, 11)
                assertFalse(store.record(owner, origin, "id")!!.dirty)
                store.acknowledge(owner, origin, "id", 1, 8)
                assertEquals(11L, store.record(owner, origin, "id")!!.serverVersion)
            }
        }

    @Test
    fun incomingPagePreservesANewerUnsyncedEditAndAdvancesItsCursor() =
        runTest {
            withStore { _, store ->
                val local = record(version = 2, server = 5).copy(payload = "local edit")
                store.put(owner, origin, local)
                store.applyPage(owner, origin, listOf(record(version = 1, server = 6).copy(payload = "remote")), 6)
                assertEquals(local.copy(serverVersion = 6), store.record(owner, origin, "id"))
                assertEquals(6L, store.cursor(owner, origin))
            }
        }

    @Test
    fun deletionCannotBeRevivedByALocalPutOrANewerRemotePage() =
        runTest {
            withStore { _, store ->
                val tombstone = record(version = 2, server = 4).copy(deleted = true, payload = null)
                store.put(owner, origin, tombstone)
                store.put(owner, origin, record(version = 3, server = 4))
                store.applyPage(owner, origin, listOf(record(version = 4, server = 99)), 99)
                assertEquals(tombstone.copy(serverVersion = 99), store.record(owner, origin, "id"))
                assertEquals(99L, store.cursor(owner, origin))
            }
        }

    @Test
    fun pendingDeletionRebasesOnNewerRemoteEditsAndCanThenBeAcknowledged() =
        runTest {
            withStore { _, store ->
                val tombstone = record(version = 2, server = 4).copy(deleted = true, payload = null)
                store.put(owner, origin, tombstone)
                store.applyPage(owner, origin, listOf(record(version = 8, server = 5)), 5)
                assertEquals(tombstone.copy(serverVersion = 5), store.pending(owner, origin, 10).single())
                store.acknowledge(owner, origin, "id", 2, 6)
                assertTrue(store.pending(owner, origin, 10).isEmpty())
                assertEquals(tombstone.copy(serverVersion = 6, dirty = false), store.record(owner, origin, "id"))
            }
        }

    @Test
    fun incomingDeletionAcknowledgesAnExistingPendingTombstone() =
        runTest {
            withStore { _, store ->
                val tombstone = record(version = 2, server = 4).copy(deleted = true, payload = null)
                store.put(owner, origin, tombstone)
                store.applyPage(owner, origin, listOf(record(version = 8, server = 5).copy(deleted = true, payload = null)), 5)
                assertEquals(tombstone.copy(serverVersion = 5, dirty = false), store.record(owner, origin, "id"))
                assertTrue(store.pending(owner, origin, 10).isEmpty())
            }
        }

    @Test
    fun staleIncomingPagesNeverRegressRecordsOrTheCursor() =
        runTest {
            withStore { _, store ->
                store.applyPage(owner, origin, listOf(record(version = 2, server = 10)), 20)
                store.applyPage(owner, origin, listOf(record(version = 1, server = 9).copy(payload = "stale")), 15)
                assertEquals(record(version = 2, server = 10).copy(dirty = false), store.record(owner, origin, "id"))
                assertEquals(20L, store.cursor(owner, origin))
            }
        }

    @Test
    fun failedPageRollsBackItsRowsAndCursorTogether() =
        runTest {
            withStore { database, store ->
                store.applyPage(owner, origin, emptyList(), 4)
                val rows =
                    object : AbstractList<HistoryRecordEntity>() {
                        override val size = 2

                        override fun get(index: Int): HistoryRecordEntity {
                            check(index == 0) { "Interrupted page" }
                            return HistoryRecordEntity(owner, origin, "first", "place", "payload", "phone", 1, 5, false, false)
                        }
                    }
                assertFailsWith<IllegalStateException> { database.historyRecordDao().applyPage(owner, origin, rows, 5) }
                assertNull(store.record(owner, origin, "first"))
                assertEquals(4L, store.cursor(owner, origin))
            }
        }

    @Test
    fun acknowledgementsAndIncomingPagesStayWithinTheirOwnerAndOrigin() =
        runTest {
            withStore { _, store ->
                store.put(owner, origin, record(version = 1, server = 1))
                store.put("another", origin, record(version = 1, server = 1))
                store.put(owner, "https://another.test", record(version = 1, server = 1))
                store.acknowledge(owner, origin, "id", 1, 2)
                store.applyPage(owner, origin, emptyList(), 2)
                assertTrue(store.record("another", origin, "id")!!.dirty)
                assertTrue(store.record(owner, "https://another.test", "id")!!.dirty)
                assertEquals(0L, store.cursor("another", origin))
                assertEquals(0L, store.cursor(owner, "https://another.test"))
            }
        }

    @Test
    fun deletedSamplesStayFindableByTheHourTheyWereTakenIn() =
        runTest {
            withStore { _, store ->
                val at = Instant.parse("2026-10-19T14:12:03Z")
                val observation = LocationObservation("a", owner, "phone", at, 36.0, -115.0)
                val payload = Json.encodeToString(HistoryPayload.serializer(), HistoryPayload.Observation(observation))
                store.put(owner, origin, HistoryRecord("observation:a", "observation", payload, "phone"))
                store.put(owner, origin, HistoryRecord("observation:b", "observation", payload, "other"))
                val start = Instant.parse("2026-10-19T14:00:00Z").toEpochMilliseconds()
                val end = start + 3_600_000

                store.put(owner, origin, HistoryRecord("observation:a", "observation", null, "phone", deviceVersion = 2, deleted = true))

                val found = store.recordsBetween(owner, origin, "observation", "phone", start, end)
                assertEquals(listOf("observation:a"), found.map { it.id })
                assertTrue(found.single().deleted)
                assertEquals(at.toEpochMilliseconds(), found.single().observedAtMillis)
                assertEquals(emptyList(), store.recordsBetween(owner, origin, "observation", "phone", end, end + 3_600_000))
                assertEquals(listOf("observation:b"), store.recordsBetween(owner, origin, "observation", "other", start, end).map { it.id })
            }
        }

    @Test
    fun aDeletionThatArrivesFromAnotherDeviceKeepsTheTimeTheRecordHad() =
        runTest {
            withStore { _, store ->
                val at = Instant.parse("2026-10-19T14:12:03Z")
                val observation = LocationObservation("a", owner, "phone", at, 36.0, -115.0)
                val payload = Json.encodeToString(HistoryPayload.serializer(), HistoryPayload.Observation(observation))
                store.put(owner, origin, HistoryRecord("observation:a", "observation", payload, "phone", dirty = false))

                store.applyPage(
                    owner,
                    origin,
                    listOf(HistoryRecord("observation:a", "observation", null, "phone", 2, 5, deleted = true)),
                    5,
                )

                assertEquals(at.toEpochMilliseconds(), store.record(owner, origin, "observation:a")!!.observedAtMillis)
            }
        }

    @Test
    fun aTombstoneWrittenWithATimeKeepsThatTime() =
        runTest {
            withStore { _, store ->
                val at = Instant.parse("2026-10-19T14:12:03Z").toEpochMilliseconds()

                store.put(
                    owner,
                    origin,
                    HistoryRecord("observation:z", "observation", null, "phone", deleted = true, observedAtMillis = at),
                )

                assertEquals(at, store.record(owner, origin, "observation:z")!!.observedAtMillis)
            }
        }

    @Test
    fun recordsOfATypeIncludeDeletedOnesAndNoOtherType() =
        runTest {
            withStore { _, store ->
                store.put(owner, origin, HistoryRecord("one", "correction", "p", "phone"))
                store.put(owner, origin, HistoryRecord("two", "correction", null, "phone", deleted = true))
                store.put(owner, origin, HistoryRecord("three", "place", "p", "phone"))

                assertEquals(setOf("one", "two"), store.recordsOfType(owner, origin, "correction").map { it.id }.toSet())
            }
        }

    private fun record(
        version: Long,
        server: Long,
    ) = HistoryRecord("id", "place", "payload", "phone", deviceVersion = version, serverVersion = server)

    private suspend fun withStore(block: suspend (LogDateDatabase, RoomHistoryRecordStore) -> Unit) {
        val file = Files.createTempFile("history-record-store", ".db")
        val database = getRoomDatabase(Room.databaseBuilder<LogDateDatabase>(file.toString()))
        try {
            block(database, RoomHistoryRecordStore(database.historyRecordDao()))
        } finally {
            database.close()
            Files.deleteIfExists(file)
        }
    }
}
