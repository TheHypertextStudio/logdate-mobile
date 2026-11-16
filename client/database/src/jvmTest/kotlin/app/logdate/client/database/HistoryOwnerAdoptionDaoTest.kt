package app.logdate.client.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.logdate.client.database.entities.Coordinates
import app.logdate.client.database.entities.HistoryRecordEntity
import app.logdate.client.database.entities.LocationActivityEntity
import app.logdate.client.database.entities.LocationLogEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HistoryOwnerAdoptionDaoTest {
    @Test
    fun `adoption retains foreign scopes and aborts collision without overwriting target`() =
        runTest {
            val database = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(BundledSQLiteDriver()).build()
            try {
                val records = database.historyRecordDao()
                val adoption = database.historyOwnerAdoptionDao()
                database.locationHistoryDao().addLocationLog(location("owned", "old", "device"))
                database.locationHistoryDao().addLocationLog(location("other-device", "old", "other-device"))
                database.locationHistoryDao().addLocationLog(location("foreign", "foreign", "device"))
                database.locationActivityDao().insert(
                    LocationActivityEntity(
                        "activity",
                        "old",
                        "device",
                        Instant.fromEpochMilliseconds(1),
                        Instant.fromEpochMilliseconds(1),
                        "STILL",
                        "ENTER",
                        null,
                    ),
                )
                records.upsert(row("old", "origin", "one"))
                records.upsert(row("old", "elsewhere", "outside"))
                records.upsert(row("foreign", "origin", "foreign"))
                adoption.adopt("old", "new", "origin", "device") { it.copy(ownerId = "new", payload = "rewritten") }
                val owners = database.locationHistoryDao().getAllLocationHistory().associate { it.sampleId to it.userId }
                assertEquals("new", owners["owned"])
                assertEquals("old", owners["other-device"])
                assertEquals("foreign", owners["foreign"])
                assertEquals(1, database.locationActivityDao().getPage("new", "device", Instant.DISTANT_PAST, "", 10).size)
                assertNull(records.record("old", "origin", "one"))
                assertEquals("rewritten", records.record("new", "origin", "one")?.payload)
                assertTrue(records.record("old", "elsewhere", "outside") != null)
                assertTrue(records.record("foreign", "origin", "foreign") != null)
                records.upsert(row("old", "origin", "before"))
                records.upsert(row("old", "origin", "conflict"))
                records.upsert(row("new", "origin", "conflict").copy(payload = "target"))
                assertFailsWith<IllegalStateException> {
                    adoption.adopt("old", "new", "origin", "device") { it.copy(ownerId = "new") }
                }
                assertNull(records.record("new", "origin", "before"))
                assertTrue(records.record("old", "origin", "before") != null)
                assertEquals("target", records.record("new", "origin", "conflict")?.payload)
                assertTrue(records.record("old", "origin", "conflict") != null)
            } finally {
                database.close()
            }
        }

    @Test
    fun `adoption moves every page of history and rewrites each record once`() =
        runTest {
            val database = Room.inMemoryDatabaseBuilder<LogDateDatabase>().setDriver(BundledSQLiteDriver()).build()
            try {
                val records = database.historyRecordDao()
                val ids = (1..250).map { "record-$it" }
                ids.forEach { records.upsert(row("old", "origin", it)) }
                val rewritten = mutableListOf<String>()

                database.historyOwnerAdoptionDao().adopt("old", "new", "origin", "device") { source ->
                    rewritten += source.id
                    source.copy(ownerId = "new", payload = "rewritten")
                }

                assertEquals(ids.sorted(), rewritten.sorted())
                assertTrue(ids.all { records.record("old", "origin", it) == null })
                assertTrue(ids.all { records.record("new", "origin", it)?.payload == "rewritten" })
            } finally {
                database.close()
            }
        }

    private fun location(
        id: String,
        owner: String,
        device: String,
    ) = LocationLogEntity(
        id,
        owner,
        device,
        Instant.fromEpochMilliseconds(1),
        Instant.fromEpochMilliseconds(1),
        Coordinates(1.0, 2.0, 0.0),
        1f,
        true,
        "HIGH_DETAIL",
        "FOREGROUND_STREAM",
    )

    private fun row(
        owner: String,
        origin: String,
        id: String,
    ) = HistoryRecordEntity(
        owner,
        origin,
        id,
        "place",
        "private",
        "device",
        1,
        0,
        false,
        true,
    )
}
