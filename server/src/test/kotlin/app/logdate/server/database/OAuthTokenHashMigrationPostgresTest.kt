package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.localTestPostgresUrl
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.Assume.assumeTrue
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Storing OAuth codes and refresh tokens by hash rewrites the rows that already exist, against a
 * real PostgreSQL: each value becomes the base64url SHA-256 the server now looks it up by. It runs in
 * its own schema, migrated to the version before the change, seeded, and then migrated to the
 * latest version, on the safe local PostgreSQL test fixture only.
 */
class OAuthTokenHashMigrationPostgresTest {
    @Test
    fun `existing codes and refresh tokens are rewritten to the hash they are looked up by`() {
        val url = localTestPostgresUrl()
        val host = java.net.URI(url.removePrefix("jdbc:")).host
        assumeTrue(
            "DATABASE_URL points at $host; this test only migrates a database on this machine",
            host in setOf("localhost", "127.0.0.1"),
        )
        val schema = "oauth_hash_${UUID.randomUUID().toString().replace("-", "")}"
        val source = localTestPostgresDataSource(url)
        val code = "raw-code-Zp3nO8bU2m"
        val token = "raw-refresh-Qx7vW1kLs0"
        try {
            migrate(source, schema, target = versionBefore("Store_oauth_token_hashes"))
            sql(source, schema) {
                execute(
                    "INSERT INTO oauth_authorization_codes(code, client_id, redirect_uri, subject_did, subject_handle, scope, " +
                        "code_challenge, dpop_key_thumbprint, expires_at) VALUES ('$code', 'https://app.example/client.json', " +
                        "'https://app.example/callback', 'did:plc:alice', 'alice.example', 'atproto', 'challenge', 'thumb', " +
                        "now() + interval '1 minute')",
                )
                execute(
                    "INSERT INTO oauth_refresh_tokens(token, client_id, subject_did, scope, dpop_key_thumbprint, expires_at) " +
                        "VALUES ('$token', 'https://app.example/client.json', 'did:plc:alice', 'atproto', 'thumb', now() + interval '14 days')",
                )
            }

            migrate(source, schema, target = null)

            sql(source, schema) {
                executeQuery("SELECT code FROM oauth_authorization_codes").use { rows ->
                    rows.next()
                    assertEquals(sha256Base64Url(code), rows.getString(1))
                }
                executeQuery("SELECT token FROM oauth_refresh_tokens").use { rows ->
                    rows.next()
                    assertEquals(sha256Base64Url(token), rows.getString(1))
                }
            }
        } finally {
            sql(source, schema = null) { execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            source.close()
        }
    }

    private fun sha256Base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    /** The newest migration older than the one named [description], whatever number each has. */
    private fun versionBefore(description: String): String {
        val folder = java.io.File(requireNotNull(javaClass.classLoader.getResource("db/migration")).toURI())
        val versions =
            folder.listFiles().orEmpty().mapNotNull { file ->
                VERSIONED_MIGRATION.matchEntire(file.name)?.let { it.groupValues[1].toInt() to it.groupValues[2] }
            }
        val named = versions.single { (_, name) -> name == description }.first
        return versions
            .map { it.first }
            .filter { it < named }
            .max()
            .toString()
    }

    private fun sql(
        source: HikariDataSource,
        schema: String?,
        block: java.sql.Statement.() -> Unit,
    ) {
        source.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { statement ->
                if (schema != null) statement.execute("SET search_path TO $schema")
                statement.block()
            }
        }
    }

    private fun migrate(
        source: HikariDataSource,
        schema: String,
        target: String?,
    ) {
        Flyway
            .configure()
            .dataSource(source)
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration")
            .apply { if (target != null) target(target) }
            .load()
            .migrate()
    }

    private companion object {
        val VERSIONED_MIGRATION = Regex("""V(\d+)__(\w+)\.sql""")
    }
}
