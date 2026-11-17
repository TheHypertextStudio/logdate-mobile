package app.logdate.server.entitlements

import app.logdate.server.database.AccountStorageUsageTable
import app.logdate.server.database.AccountsTable
import app.logdate.server.database.LogDateBackupsTable
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class EntitlementPersistenceTest {
    /** Every table [DatabaseUsageCalculator] reads; usage rows refer to accounts. */
    private val usageTables =
        arrayOf<Table>(LogDateBackupsTable, AccountsTable, AccountStorageUsageTable)

    /** Every table [StoredEntitlementService] reads. */
    private val entitlementTables =
        arrayOf<Table>(PlansTable, AccountEntitlementsTable, AccountsTable)

    @Test
    fun `a beta member with no subscription has the base plan at no charge`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                insertLaunchPlans(database)
                insertBetaMember(database, accountId)

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals("cloud", entitlement.planId)
                assertEquals(EntitlementTier.STANDARD, entitlement.tier)
                assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
                assertEquals(50 * GIB, entitlement.limits.storageBytes)
                assertNull(entitlement.limits.extraStorage)
                assertTrue(entitlement.includedWithBeta)
            }
        }

    @Test
    fun `an account carrying the beta row from the launch migration is on the base plan at no charge`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                insertLaunchPlans(database)
                insertBetaMember(database, accountId)
                insertAccountEntitlement(database, accountId, planId = "cloud", status = "active", source = "beta")

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals("cloud", entitlement.planId)
                assertTrue(entitlement.includedWithBeta)
                assertNull(entitlement.limits.extraStorage)
            }
        }

    @Test
    fun `an account that is not a beta member and has no subscription has no plan`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val joinedAfterLaunch = UUID.randomUUID()
                insertLaunchPlans(database)
                transaction(database) {
                    AccountsTable.insert {
                        it[id] = joinedAfterLaunch
                        it[username] = "newcomer"
                        it[displayName] = "Newcomer"
                        it[createdAt] = Clock.System.now()
                        it[cloudBetaMember] = false
                    }
                }

                val entitlement = StoredEntitlementService(database).resolve(joinedAfterLaunch)

                assertEquals(Entitlement.NO_PLAN_ID, entitlement.planId)
                assertEquals(EntitlementStatus.CANCELLED, entitlement.status)
                assertEquals(0L, entitlement.limits.storageBytes)
                assertFalse(entitlement.includedWithBeta)
            }
        }

    @Test
    fun `a beta member whose paid subscription ended is back on the base plan at no charge`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                insertLaunchPlans(database)
                insertBetaMember(database, accountId)
                insertAccountEntitlement(database, accountId, planId = "cloud_plus", status = "cancelled", source = "stripe")

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals("cloud", entitlement.planId)
                assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
                assertTrue(entitlement.includedWithBeta)
            }
        }

    @Test
    fun `a beta member paying for Plus is on Plus, not the plan the beta includes`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                insertLaunchPlans(database)
                insertBetaMember(database, accountId)
                insertAccountEntitlement(database, accountId, planId = "cloud_plus", status = "active", source = "stripe")

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals("cloud_plus", entitlement.planId)
                assertEquals(250 * GIB, entitlement.limits.storageBytes)
                assertFalse(entitlement.includedWithBeta)
            }
        }

    @Test
    fun `extra storage on Plus is billable only for a Stripe subscription whose payments are current`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val paying = UUID.randomUUID()
                val pastDue = UUID.randomUUID()
                val granted = UUID.randomUUID()
                insertLaunchPlans(database)
                insertAccountEntitlement(database, paying, planId = "cloud_plus", status = "active", source = "stripe")
                insertAccountEntitlement(database, pastDue, planId = "cloud_plus", status = "past_due", source = "stripe")
                insertAccountEntitlement(database, granted, planId = "cloud_plus", status = "active", source = "grant")
                val service = StoredEntitlementService(database)

                assertEquals(ExtraStorage(stepBytes = 50 * GIB, billable = true), service.resolve(paying).limits.extraStorage)
                assertEquals(ExtraStorage(stepBytes = 50 * GIB, billable = false), service.resolve(pastDue).limits.extraStorage)
                assertEquals(ExtraStorage(stepBytes = 50 * GIB, billable = false), service.resolve(granted).limits.extraStorage)
            }
        }

    @Test
    fun `stored service maps account plan tier status and nullable unlimited limits`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                insertPlan(database, id = "pro", tier = "PRO", storageBytes = null, backupCount = null)
                insertAccountEntitlement(database, accountId = accountId, planId = "pro", status = "past_due")

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals("pro", entitlement.planId)
                assertEquals(EntitlementTier.PRO, entitlement.tier)
                assertEquals(EntitlementStatus.PAST_DUE, entitlement.status)
                assertNull(entitlement.limits.storageBytes)
                assertNull(entitlement.limits.backupCount)
            }
        }

    @Test
    fun `a grace period keeps the plan and a cancelled subscription leaves no plan`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val standardAccountId = UUID.randomUUID()
                val unlimitedAccountId = UUID.randomUUID()
                insertPlan(database, id = "standard", tier = "standard", storageBytes = 100L, backupCount = 3)
                insertPlan(database, id = "unlimited", tier = "unlimited", storageBytes = null, backupCount = null)
                insertAccountEntitlement(database, accountId = standardAccountId, planId = "standard", status = "grace")
                insertAccountEntitlement(database, accountId = unlimitedAccountId, planId = "unlimited", status = "cancelled")

                val standard = StoredEntitlementService(database).resolve(standardAccountId)
                val unlimited = StoredEntitlementService(database).resolve(unlimitedAccountId)

                assertEquals(EntitlementTier.STANDARD, standard.tier)
                assertEquals(EntitlementStatus.GRACE, standard.status)
                assertEquals(Entitlement.NO_PLAN_ID, unlimited.planId)
                assertEquals(EntitlementStatus.CANCELLED, unlimited.status)
                assertEquals(0L, unlimited.limits.storageBytes)
            }
        }

    @Test
    fun `stored service surfaces plan features for the resolved entitlement`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                insertPlan(
                    database,
                    id = "standard",
                    tier = "standard",
                    storageBytes = 100L,
                    backupCount = 3,
                    features = mapOf("email_verification" to true, "future_flag" to false),
                )
                insertAccountEntitlement(database, accountId = accountId, planId = "standard", status = "active")

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals(true, entitlement.features["email_verification"])
                assertEquals(false, entitlement.features["future_flag"])
            }
        }

    @Test
    fun `stored service falls back to empty features when plan column is malformed`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                // Insert directly so we can poison the features column with non-JSON text.
                transaction(database) {
                    PlansTable.insert {
                        it[PlansTable.id] = "broken"
                        it[name] = "broken plan"
                        it[tier] = "free"
                        it[monthlyBytesLimit] = 10L
                        it[backupCountLimit] = 1
                        it[playProductId] = null
                        it[active] = true
                        it[features] = "not-json"
                    }
                }
                insertAccountEntitlement(database, accountId = accountId, planId = "broken", status = "active")

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals(emptyMap(), entitlement.features)
            }
        }

    @Test
    fun `stored service defaults unknown tier and status values conservatively`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val accountId = UUID.randomUUID()
                insertPlan(database, id = "custom", tier = "custom", storageBytes = 10L, backupCount = 1)
                insertAccountEntitlement(database, accountId = accountId, planId = "custom", status = "unexpected")

                val entitlement = StoredEntitlementService(database).resolve(accountId)

                assertEquals(EntitlementTier.FREE, entitlement.tier)
                assertEquals(EntitlementStatus.ACTIVE, entitlement.status)
            }
        }

    @Test
    fun `stored service denies by default when selected plan is inactive or missing`() =
        runTest {
            withDatabase(*entitlementTables) { database ->
                val inactiveAccountId = UUID.randomUUID()
                val missingAccountId = UUID.randomUUID()
                insertPlan(database, id = "standard", tier = "standard", storageBytes = 100L, backupCount = 1, active = false)
                insertAccountEntitlement(database, accountId = inactiveAccountId, planId = "standard", status = "active")
                insertAccountEntitlement(database, accountId = missingAccountId, planId = "missing", status = "active")

                val inactivePlanEntitlement = StoredEntitlementService(database).resolve(inactiveAccountId)
                val missingPlanEntitlement = StoredEntitlementService(database).resolve(missingAccountId)

                assertEquals(EntitlementStatus.CANCELLED, inactivePlanEntitlement.status)
                assertEquals(0L, inactivePlanEntitlement.limits.storageBytes)
                assertEquals(0, inactivePlanEntitlement.limits.backupCount)
                assertEquals("missing", missingPlanEntitlement.planId)
                assertEquals(EntitlementStatus.CANCELLED, missingPlanEntitlement.status)
            }
        }

    @Test
    fun `database usage calculator reads one account's running totals and counts its backup rows`() =
        runTest {
            withDatabase(*usageTables) { database ->
                val accountId = UUID.randomUUID()
                val otherAccountId = UUID.randomUUID()
                insertAccount(database, accountId)
                insertAccount(database, otherAccountId)
                insertTotals(database, accountId, space = 3_000_000_000L, media = 30L, backup = 70L)
                insertTotals(database, otherAccountId, space = 7_000L, media = 70L, backup = 90L)
                insertBackup(database, accountId = accountId, bytes = 50L)
                insertBackup(database, accountId = accountId, bytes = 20L)
                insertBackup(database, accountId = otherAccountId, bytes = 90L)

                val calculator = DatabaseUsageCalculator(database)

                assertEquals(3_000_000_100L, calculator.storageBytes(accountId))
                assertEquals(100L, calculator.storageBytesOutsideSpaces(accountId))
                assertEquals(2, calculator.backupCount(accountId))
            }
        }

    @Test
    fun `database usage calculator returns zeroes when account has no rows`() =
        runTest {
            withDatabase(*usageTables) { database ->
                val calculator = DatabaseUsageCalculator(database)

                assertEquals(0L, calculator.storageBytes(UUID.randomUUID()))
                assertEquals(0, calculator.backupCount(UUID.randomUUID()))
            }
        }

    private suspend fun withDatabase(
        vararg tables: Table,
        block: suspend (Database) -> Unit,
    ) {
        val database =
            Database.connect(
                url = "jdbc:h2:mem:entitlement_test_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
                driver = "org.h2.Driver",
                user = "sa",
                password = "",
            )

        transaction(database) {
            SchemaUtils.create(*tables)
        }

        try {
            block(database)
        } finally {
            transaction(database) {
                SchemaUtils.drop(*tables)
            }
        }
    }

    private fun insertPlan(
        database: Database,
        id: String,
        tier: String,
        storageBytes: Long?,
        backupCount: Int?,
        active: Boolean = true,
        features: Map<String, Boolean> = emptyMap(),
        extraStorageStep: Long? = null,
    ) {
        transaction(database) {
            PlansTable.insert {
                it[PlansTable.id] = id
                it[name] = "$id plan"
                it[PlansTable.tier] = tier
                it[monthlyBytesLimit] = storageBytes
                it[backupCountLimit] = backupCount
                it[extraStorageStepBytes] = extraStorageStep
                it[playProductId] = null
                it[PlansTable.active] = active
                it[PlansTable.features] =
                    StoredEntitlementService.FEATURES_JSON.encodeToString(
                        StoredEntitlementService.FEATURE_MAP_SERIALIZER,
                        features,
                    )
            }
        }
    }

    private fun insertLaunchPlans(database: Database) {
        insertPlan(database, id = "cloud", tier = "standard", storageBytes = 50 * GIB, backupCount = null)
        insertPlan(database, id = "cloud_plus", tier = "pro", storageBytes = 250 * GIB, backupCount = null, extraStorageStep = 50 * GIB)
    }

    private fun insertBetaMember(
        database: Database,
        accountId: UUID,
    ) {
        transaction(database) {
            AccountsTable.insert {
                it[id] = accountId
                it[username] = "u${accountId.toString().take(8)}"
                it[displayName] = "Beta member"
                it[createdAt] = Clock.System.now()
                it[cloudBetaMember] = true
            }
        }
    }

    private fun insertAccountEntitlement(
        database: Database,
        accountId: UUID,
        planId: String,
        status: String,
        source: String = "test",
    ) {
        transaction(database) {
            AccountEntitlementsTable.insert {
                it[AccountEntitlementsTable.accountId] = accountId
                it[AccountEntitlementsTable.planId] = planId
                it[entitlementSource] = source
                it[externalSubscriptionId] = null
                it[AccountEntitlementsTable.status] = status
                it[currentPeriodEnd] = null
                it[updatedAt] = Clock.System.now()
            }
        }
    }

    private fun insertAccount(
        database: Database,
        accountId: UUID,
    ) {
        transaction(database) {
            AccountsTable.insert {
                it[id] = accountId
                it[username] = "u${accountId.toString().take(8)}"
                it[displayName] = "Usage"
                it[createdAt] = Clock.System.now()
            }
        }
    }

    private fun insertTotals(
        database: Database,
        accountId: UUID,
        space: Long,
        media: Long,
        backup: Long,
    ) {
        transaction(database) {
            AccountStorageUsageTable.insert {
                it[AccountStorageUsageTable.accountId] = accountId
                it[spaceBytes] = space
                it[mediaBytes] = media
                it[backupBytes] = backup
            }
        }
    }

    private fun insertBackup(
        database: Database,
        accountId: UUID,
        bytes: Long,
    ) {
        transaction(database) {
            LogDateBackupsTable.insert {
                it[id] = UUID.randomUUID()
                it[userId] = accountId
                it[deviceId] = "device"
                it[manifest] = "{}"
                it[storagePath] = "backups/${UUID.randomUUID()}"
                it[createdAt] = 1L
                it[sizeBytes] = bytes
            }
        }
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
