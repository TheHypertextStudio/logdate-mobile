package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.database.support.localTestPostgresDataSource
import app.logdate.server.database.support.withOwnPostgresDatabase
import app.logdate.server.entitlements.Entitlement
import app.logdate.server.entitlements.StoredEntitlementService
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * Who joins the LogDate Cloud beta, in a real PostgreSQL through the real migrations: an account
 * created before plans go on sale keeps the base plan at no charge, and one created afterwards has
 * no plan until it buys one.
 */
@OptIn(ExperimentalUuidApi::class)
class CloudBetaEnrolmentPostgresTest {
    private fun onPostgres(test: suspend (Database) -> Unit) =
        withOwnPostgresDatabase("cloud_beta_") { url ->
            localTestPostgresDataSource(url).use { source ->
                val previous = TransactionManager.defaultDatabase
                try {
                    val database = DatabaseConfig.initializeDatabase(source, autoMigrate = true)
                    TransactionManager.defaultDatabase = database
                    runBlocking { test(database) }
                } finally {
                    TransactionManager.defaultDatabase = previous
                }
            }
        }

    private fun account(name: String) = Account(id = Uuid.random(), username = name, displayName = name, createdAt = Clock.System.now())

    @Test
    fun `an account created during the beta has the base plan at no charge however it was created`() =
        onPostgres { database ->
            val duringBeta = PostgreSQLAccountRepository(newAccountsJoinCloudBeta = true)
            val created = account("created").also { assertTrue(duringBeta.create(it)) }
            val saved = account("saved").also { duringBeta.save(it) }
            val createdOnly = account("createdonly").also { assertTrue(duringBeta.createOnly(it)) }
            val entitlements = StoredEntitlementService(database)

            listOf(created, saved, createdOnly).forEach { account ->
                val entitlement = entitlements.resolve(account.id.toJavaUuid())
                assertEquals("cloud", entitlement.planId, "${account.username} is on the base plan")
                assertTrue(entitlement.includedWithBeta)
                assertEquals(50L * 1024 * 1024 * 1024, entitlement.limits.storageBytes)
            }
        }

    @Test
    fun `saving an existing account again does not enrol it twice or fail`() =
        onPostgres { database ->
            val duringBeta = PostgreSQLAccountRepository(newAccountsJoinCloudBeta = true)
            val member = account("member")
            duringBeta.save(member)

            duringBeta.save(member.copy(displayName = "Renamed"))
            assertFalse(duringBeta.create(member), "an account that already exists is not created again")

            assertEquals("cloud", StoredEntitlementService(database).resolve(member.id.toJavaUuid()).planId)
        }

    @Test
    fun `an account created after plans go on sale has no plan, and an earlier member keeps theirs`() =
        onPostgres { database ->
            val member = account("member").also { PostgreSQLAccountRepository(newAccountsJoinCloudBeta = true).create(it) }
            val afterLaunch = PostgreSQLAccountRepository(newAccountsJoinCloudBeta = false)
            val newcomer = account("newcomer").also { afterLaunch.create(it) }
            // The member signs in again after launch; saving their account must not drop the membership.
            afterLaunch.save(member.copy(displayName = "Still here"))
            val entitlements = StoredEntitlementService(database)

            assertEquals(Entitlement.NO_PLAN_ID, entitlements.resolve(newcomer.id.toJavaUuid()).planId)
            assertEquals(0L, entitlements.resolve(newcomer.id.toJavaUuid()).limits.storageBytes)
            assertEquals("cloud", entitlements.resolve(member.id.toJavaUuid()).planId)
        }
}
