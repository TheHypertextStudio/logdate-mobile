package app.logdate.server.billing

import app.logdate.server.database.AccountsTable
import app.logdate.server.database.dbQuery
import app.logdate.server.entitlements.AccountEntitlementsTable
import app.logdate.server.entitlements.PlansTable
import app.logdate.server.entitlements.StoredEntitlementService
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

/** A plan as billing needs it: what it includes and whether it sells storage past that. */
data class BillingPlan(
    val id: String,
    val includedBytes: Long?,
    val extraStorageStepBytes: Long?,
)

/** The subscription Stripe bills an account for, as last synced from Stripe. */
data class StoredSubscription(
    val accountId: UUID,
    val planId: String,
    val subscriptionId: String,
    /** `active`, `past_due` or `cancelled`. */
    val status: String,
    val currentPeriodEnd: Instant?,
    val cancelAt: Instant?,
    /** `month` or `year`. */
    val interval: String?,
)

/**
 * Billing's rows: Stripe customers, the subscription behind each paid entitlement, the extra
 * storage last reported for metering, and the webhook events already handled.
 */
class BillingStore(
    private val database: Database,
    private val clock: Clock = Clock.System,
) {
    suspend fun stripeCustomerId(accountId: UUID): String? =
        dbQuery(database) {
            BillingCustomersTable
                .selectAll()
                .where { BillingCustomersTable.accountId eq accountId }
                .singleOrNull()
                ?.get(BillingCustomersTable.stripeCustomerId)
        }

    suspend fun accountForStripeCustomer(customerId: String): UUID? =
        dbQuery(database) {
            BillingCustomersTable
                .selectAll()
                .where { BillingCustomersTable.stripeCustomerId eq customerId }
                .singleOrNull()
                ?.get(BillingCustomersTable.accountId)
        }

    /** Records [customerId] for [accountId] unless one is already recorded, and returns the one kept. */
    suspend fun saveStripeCustomer(
        accountId: UUID,
        customerId: String,
    ): String =
        dbQuery(database) {
            BillingCustomersTable.insertIgnore {
                it[BillingCustomersTable.accountId] = accountId
                it[stripeCustomerId] = customerId
                it[createdAt] = clock.now()
            }
            BillingCustomersTable
                .selectAll()
                .where { BillingCustomersTable.accountId eq accountId }
                .single()[BillingCustomersTable.stripeCustomerId]
        }

    suspend fun isBetaMember(accountId: UUID): Boolean =
        dbQuery(database) {
            AccountsTable
                .select(AccountsTable.cloudBetaMember)
                .where { AccountsTable.id eq accountId }
                .singleOrNull()
                ?.get(AccountsTable.cloudBetaMember) == true
        }

    suspend fun plan(planId: String): BillingPlan? =
        dbQuery(database) {
            PlansTable
                .selectAll()
                .where { (PlansTable.id eq planId) and (PlansTable.active eq true) }
                .singleOrNull()
                ?.let {
                    BillingPlan(
                        id = it[PlansTable.id],
                        includedBytes = it[PlansTable.monthlyBytesLimit],
                        extraStorageStepBytes = it[PlansTable.extraStorageStepBytes],
                    )
                }
        }

    /** The Stripe subscription recorded for [accountId], or null when Stripe bills it for nothing. */
    suspend fun subscription(accountId: UUID): StoredSubscription? =
        dbQuery(database) {
            AccountEntitlementsTable
                .selectAll()
                .where { stripeRowOf(accountId) }
                .singleOrNull()
                ?.toStoredSubscription()
        }

    /** Every subscription Stripe is billing that has not ended. */
    suspend fun liveSubscriptions(): List<StoredSubscription> =
        dbQuery(database) {
            AccountEntitlementsTable
                .selectAll()
                .where { AccountEntitlementsTable.entitlementSource eq StoredEntitlementService.SOURCE_STRIPE }
                .mapNotNull { it.toStoredSubscription() }
                .filter { it.status != STATUS_CANCELLED }
        }

    /** Makes [subscription] the account's entitlement, replacing whatever row it had. */
    suspend fun saveSubscription(subscription: StoredSubscription) {
        dbQuery(database) {
            AccountEntitlementsTable.upsert(AccountEntitlementsTable.accountId) {
                it[accountId] = subscription.accountId
                it[planId] = subscription.planId
                it[entitlementSource] = StoredEntitlementService.SOURCE_STRIPE
                it[externalSubscriptionId] = subscription.subscriptionId
                it[status] = subscription.status
                it[currentPeriodEnd] = subscription.currentPeriodEnd
                it[cancelAt] = subscription.cancelAt
                it[billingInterval] = subscription.interval
                it[updatedAt] = clock.now()
            }
        }
    }

    /**
     * Removes the account's entitlement when it is the Stripe subscription [subscriptionId]. A row
     * for a different subscription stays: an old subscription ending must not undo a newer one.
     */
    suspend fun clearSubscription(
        accountId: UUID,
        subscriptionId: String,
    ) {
        dbQuery(database) {
            AccountEntitlementsTable.deleteWhere {
                stripeRowOf(accountId) and (AccountEntitlementsTable.externalSubscriptionId eq subscriptionId)
            }
            StorageMeterReportsTable.deleteWhere { StorageMeterReportsTable.accountId eq accountId }
        }
    }

    suspend fun meterReport(accountId: UUID): MeterReport? =
        dbQuery(database) {
            StorageMeterReportsTable
                .selectAll()
                .where { StorageMeterReportsTable.accountId eq accountId }
                .singleOrNull()
                ?.let { MeterReport(units = it[StorageMeterReportsTable.reportedUnits], at = it[StorageMeterReportsTable.reportedAt]) }
        }

    suspend fun saveMeterReport(
        accountId: UUID,
        report: MeterReport,
    ) {
        dbQuery(database) {
            StorageMeterReportsTable.upsert(StorageMeterReportsTable.accountId) {
                it[StorageMeterReportsTable.accountId] = accountId
                it[reportedUnits] = report.units
                it[reportedAt] = report.at
            }
        }
    }

    suspend fun eventHandled(eventId: String): Boolean =
        dbQuery(database) {
            BillingEventsTable
                .selectAll()
                .where { (BillingEventsTable.provider eq PROVIDER_STRIPE) and (BillingEventsTable.externalEventId eq eventId) }
                .any()
        }

    /** Records a handled webhook event. Only its identifier and type are kept, never its body. */
    suspend fun recordEvent(
        eventId: String,
        eventType: String,
        accountId: UUID?,
    ) {
        dbQuery(database) {
            BillingEventsTable.insertIgnore {
                it[id] = UUID.randomUUID()
                it[BillingEventsTable.accountId] = accountId
                it[provider] = PROVIDER_STRIPE
                it[externalEventId] = eventId
                it[BillingEventsTable.eventType] = eventType
                it[payload] = "{}"
                it[processedAt] = clock.now()
            }
        }
    }

    private fun stripeRowOf(accountId: UUID) =
        (AccountEntitlementsTable.accountId eq accountId) and
            (AccountEntitlementsTable.entitlementSource eq StoredEntitlementService.SOURCE_STRIPE)

    private fun org.jetbrains.exposed.v1.core.ResultRow.toStoredSubscription(): StoredSubscription? {
        val subscriptionId = this[AccountEntitlementsTable.externalSubscriptionId] ?: return null
        return StoredSubscription(
            accountId = this[AccountEntitlementsTable.accountId],
            planId = this[AccountEntitlementsTable.planId],
            subscriptionId = subscriptionId,
            status = this[AccountEntitlementsTable.status],
            currentPeriodEnd = this[AccountEntitlementsTable.currentPeriodEnd],
            cancelAt = this[AccountEntitlementsTable.cancelAt],
            interval = this[AccountEntitlementsTable.billingInterval],
        )
    }

    companion object {
        const val STATUS_ACTIVE: String = "active"
        const val STATUS_PAST_DUE: String = "past_due"
        const val STATUS_CANCELLED: String = "cancelled"
        private const val PROVIDER_STRIPE = "stripe"
    }
}

/** The extra storage last reported to Stripe for an account: how many whole steps, and when. */
data class MeterReport(
    val units: Int,
    val at: Instant,
)

internal object BillingCustomersTable : Table("billing_customers") {
    val accountId: Column<UUID> = javaUUID("account_id")
    val stripeCustomerId: Column<String> = text("stripe_customer_id").uniqueIndex()
    val createdAt: Column<Instant> = timestamp("created_at")

    override val primaryKey = PrimaryKey(accountId)
}

internal object StorageMeterReportsTable : Table("storage_meter_reports") {
    val accountId: Column<UUID> = javaUUID("account_id")
    val reportedUnits: Column<Int> = integer("reported_units")
    val reportedAt: Column<Instant> = timestamp("reported_at")

    override val primaryKey = PrimaryKey(accountId)
}

internal object BillingEventsTable : Table("billing_events") {
    val id: Column<UUID> = javaUUID("id")
    val accountId: Column<UUID?> = javaUUID("account_id").nullable()
    val provider: Column<String> = text("provider")
    val externalEventId: Column<String> = text("external_event_id")
    val eventType: Column<String> = text("event_type")
    val payload: Column<String> = text("payload")
    val processedAt: Column<Instant> = timestamp("processed_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(provider, externalEventId)
    }
}
