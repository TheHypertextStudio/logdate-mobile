package app.logdate.client.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.logdate.client.database.migrations.MIGRATION_62_63
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Migration62To63JvmTest {
    @Test
    fun `migration 62 to 63 preserves existing location logs and places while adding new columns and cache`() {
        val file = Files.createTempFile("location-migration-62-63", ".db")
        val helper =
            MigrationTestHelper(
                Path.of("schemas"),
                file,
                BundledSQLiteDriver(),
                LogDateDatabase::class,
                { LogDateDatabaseConstructor.initialize() },
                emptyList(),
            )
        try {
            helper.createDatabase(62).use { connection ->
                connection.execSQL(
                    "INSERT INTO location_logs (sample_id,user_id,device_id,timestamp,logged_at,latitude,longitude,altitude," +
                        "confidence,is_genuine,capture_pipeline,capture_source,is_mock) " +
                        "VALUES ('s-old','user-1','dev-1',1000,2000,37.77,-122.41,10.0,1.0,1,'HIGH_DETAIL','FOREGROUND_STREAM',0)",
                )
                connection.execSQL(
                    "INSERT INTO user_places (id,name,latitude,longitude,radius_meters,description,created_at,updated_at) " +
                        "VALUES ('p-old','Cafe',37.77,-122.41,75.0,NULL,1000,2000)",
                )
            }
            helper.runMigrationsAndValidate(63, listOf(MIGRATION_62_63)).use { connection ->
                connection
                    .prepare(
                        "SELECT sample_id,provider,speed_accuracy_mps,vertical_accuracy_meters,elapsed_realtime_nanos,ingestion_seq " +
                            "FROM location_logs",
                    ).use { row ->
                        assertTrue(row.step())
                        assertEquals("s-old", row.getText(0))
                        assertTrue(row.isNull(1))
                        assertTrue(row.isNull(2))
                        assertTrue(row.isNull(3))
                        assertTrue(row.isNull(4))
                        assertEquals(0L, row.getLong(5))
                    }
                connection.prepare("SELECT id,category FROM user_places").use { row ->
                    assertTrue(row.step())
                    assertEquals("p-old", row.getText(0))
                    assertTrue(row.isNull(1))
                }
                connection.execSQL(
                    "INSERT INTO place_resolution_cache (cell_x,cell_y,result_kind,payload_json,resolved_at_ms,expires_at_ms) " +
                        "VALUES (75549,-244839,'COARSE','{}',1000,999999)",
                )
                connection.prepare("SELECT result_kind FROM place_resolution_cache WHERE cell_x = 75549").use { row ->
                    assertTrue(row.step())
                    assertEquals("COARSE", row.getText(0))
                }
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
