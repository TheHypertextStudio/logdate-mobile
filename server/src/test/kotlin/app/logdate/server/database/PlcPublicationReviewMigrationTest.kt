package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withOwnPostgresDatabase
import org.flywaydb.core.Flyway
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PlcPublicationReviewMigrationTest {
    @Test
    fun `only one reviewed publication can have authority for an account at a time`() {
        withOwnPostgresDatabase("plc_publication_review_") { url ->
            localTestPostgresDataSource(url).use { source ->
                Flyway
                    .configure()
                    .dataSource(source)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
                source.connection.use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { statement ->
                        val account = Uuid.random()
                        val first = Uuid.random()
                        val second = Uuid.random()
                        val did = "did:plc:abcdefghijklmnopqrstuvwx"
                        statement.execute(
                            "INSERT INTO accounts(id, username, display_name, created_at) " +
                                "VALUES ('$account', 'reviewer', 'Reviewer', now())",
                        )
                        for (id in listOf(first, second)) {
                            statement.execute(
                                "INSERT INTO plc_publication_reviews " +
                                    "(id, account_id, did, handle, operations, expires_at) VALUES " +
                                    "('$id', '$account', '$did', 'reviewer.logdate.app', '[{}]', now() + interval '5 minutes')",
                            )
                        }
                        assertFailsWith<SQLException> {
                            statement.execute("UPDATE plc_publication_reviews SET handle = 'other.logdate.app' WHERE id = '$first'")
                        }
                        assertFailsWith<SQLException> {
                            statement.execute("UPDATE plc_publication_reviews SET state = 'authorized' WHERE id = '$first'")
                        }
                        assertEquals(
                            1,
                            statement.executeUpdate(
                                "UPDATE plc_publication_reviews SET state = 'authorized', authorized_at = now() WHERE id = '$first'",
                            ),
                        )
                        assertFailsWith<SQLException> {
                            statement.execute(
                                "UPDATE plc_publication_reviews SET state = 'authorized', authorized_at = now() WHERE id = '$second'",
                            )
                        }
                        statement.execute(
                            "UPDATE plc_publication_reviews SET state = 'uncertain', first_attempt_at = now() WHERE id = '$first'",
                        )
                        assertFailsWith<SQLException> {
                            statement.execute("UPDATE plc_publication_reviews SET state = 'confirmed' WHERE id = '$first'")
                        }
                        statement.executeQuery("SELECT public_identity_did FROM accounts WHERE id = '$account'").use {
                            assertTrue(it.next())
                            assertEquals(null, it.getString(1))
                        }
                    }
                }
            }
        }
    }
}
