package app.logdate.server.database

import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.localTestPostgresUrl
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replacing the placeholder catalog with the two LogDate Cloud launch plans, against a real
 * PostgreSQL. It runs in its own schema, migrated to the version before the launch plans, seeded
 * with accounts on each of the old plans, and then migrated to the latest version.
 */
class CloudLaunchPlansMigrationPostgresTest {
    @Test
    fun `the catalog becomes the two launch plans and every existing account joins the beta`() {
        val url = localTestPostgresUrl()
        val host = java.net.URI(url.removePrefix("jdbc:")).host
        assumeTrue(
            "DATABASE_URL points at $host; this test only migrates a database on this machine",
            host in setOf("localhost", "127.0.0.1"),
        )
        val schema = "cloud_plans_${UUID.randomUUID().toString().replace("-", "")}"
        val source = localTestPostgresDataSource(url)
        try {
            migrate(source, schema, target = versionBeforeLaunchPlans())
            val neverSubscribed = UUID.randomUUID()
            val onFree = UUID.randomUUID()
            val onStandard = UUID.randomUUID()
            val onPro = UUID.randomUUID()
            sql(source, schema) {
                listOf(neverSubscribed, onFree, onStandard, onPro).forEach { execute(insertAccount(it)) }
                execute(insertEntitlement(onFree, plan = "free", status = "active"))
                execute(insertEntitlement(onStandard, plan = "standard", status = "active"))
                execute(insertEntitlement(onPro, plan = "pro", status = "past_due"))
            }

            migrate(source, schema, target = null)

            sql(source, schema) {
                executeQuery(
                    "SELECT id, name, tier, monthly_bytes_limit, backup_count_limit, extra_storage_step_bytes, features " +
                        "FROM plans WHERE active ORDER BY monthly_bytes_limit",
                ).use { rows ->
                    val plans =
                        buildList {
                            while (rows.next()) {
                                add(
                                    listOf(
                                        rows.getString(1),
                                        rows.getString(2),
                                        rows.getString(3),
                                        rows.getLong(4),
                                        rows.getObject(5),
                                        rows.getObject(6),
                                        rows.getString(7),
                                    ),
                                )
                            }
                        }
                    assertEquals(
                        listOf(
                            listOf("cloud", "LogDate Cloud", "standard", 50 * GIB, null, null, """{"email_verification":true}"""),
                            listOf("cloud_plus", "LogDate Cloud Plus", "pro", 250 * GIB, null, 50 * GIB, """{"email_verification":true}"""),
                        ),
                        plans,
                        "the catalog holds exactly the two launch plans, and only Plus sells storage past what it includes",
                    )
                }
                // The server version still running while the migration is applied creates accounts
                // without naming the new column. Those are created during the beta as well.
                val createdByOlderServer = UUID.randomUUID()
                execute(insertAccount(createdByOlderServer))
                executeQuery("SELECT id::text FROM accounts WHERE cloud_beta_member").use { rows ->
                    val members = buildSet { while (rows.next()) add(rows.getString(1)) }
                    assertEquals(
                        setOf(neverSubscribed, onFree, onStandard, onPro, createdByOlderServer).map { it.toString() }.toSet(),
                        members,
                        "every account that existed before the launch plans is a beta member, and so is one an older server adds",
                    )
                }
                executeQuery("SELECT account_id::text, plan_id, source, status FROM account_entitlements").use { rows ->
                    val entitlements =
                        buildMap {
                            while (rows.next()) {
                                put(
                                    rows.getString(1),
                                    listOf(rows.getString(2), rows.getString(3), rows.getString(4)),
                                )
                            }
                        }
                    assertEquals(
                        mapOf(
                            neverSubscribed.toString() to listOf("cloud", "beta", "active"),
                            onFree.toString() to listOf("cloud", "beta", "active"),
                            onStandard.toString() to listOf("cloud", "grant", "active"),
                            onPro.toString() to listOf("cloud_plus", "grant", "past_due"),
                        ),
                        entitlements,
                        "no row names a retired plan: grants move to the nearest launch plan with their status, and every " +
                            "other account is on the base plan through the beta",
                    )
                }
                executeQuery("SELECT count(*) FROM plans WHERE id IN ('free', 'standard', 'pro')").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1), "the placeholder plans are gone")
                }
            }
        } finally {
            sql(source, schema = null) { execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            source.close()
        }
    }

    /** The newest migration older than the one adding the launch plans, whatever number each has. */
    private fun versionBeforeLaunchPlans(): String {
        val folder = java.io.File(requireNotNull(javaClass.classLoader.getResource("db/migration")).toURI())
        val versions =
            folder.listFiles().orEmpty().mapNotNull { file ->
                VERSIONED_MIGRATION.matchEntire(file.name)?.let { it.groupValues[1].toInt() to it.groupValues[2] }
            }
        val launchPlans = versions.single { (_, description) -> description == "LogDate_Cloud_launch_plans" }.first
        return versions
            .map { it.first }
            .filter { it < launchPlans }
            .max()
            .toString()
    }

    private fun insertAccount(id: UUID): String =
        "INSERT INTO accounts(id, username, display_name, created_at) " +
            "VALUES ('$id', 'u${id.toString().replace("-", "").take(16)}', 'Person', now())"

    private fun insertEntitlement(
        accountId: UUID,
        plan: String,
        status: String,
    ): String =
        "INSERT INTO account_entitlements(account_id, plan_id, source, status) " +
            "VALUES ('$accountId', '$plan', 'grant', '$status')"

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
        const val GIB = 1024L * 1024L * 1024L
    }
}
