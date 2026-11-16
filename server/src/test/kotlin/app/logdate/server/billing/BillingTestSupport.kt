package app.logdate.server.billing

import app.logdate.server.database.AccountsTable
import app.logdate.server.entitlements.AccountEntitlementsTable
import app.logdate.server.entitlements.PlansTable
import app.logdate.server.entitlements.UsageCalculator
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

internal const val GIB = 1024L * 1024L * 1024L

private val billingTables =
    arrayOf(
        PlansTable,
        AccountEntitlementsTable,
        AccountsTable,
        BillingCustomersTable,
        StorageMeterReportsTable,
        BillingEventsTable,
    )

/** Runs [block] against an empty in-memory database holding the two launch plans. */
internal suspend fun withBillingDatabase(block: suspend (Database) -> Unit) {
    val database =
        Database.connect(
            url = "jdbc:h2:mem:billing_test_${System.nanoTime()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
            driver = "org.h2.Driver",
            user = "sa",
            password = "",
        )
    transaction(database) { SchemaUtils.create(*billingTables) }
    insertPlan(database, id = "cloud", tier = "standard", includedBytes = 50 * GIB, extraStorageStep = null)
    insertPlan(database, id = "cloud_plus", tier = "pro", includedBytes = 250 * GIB, extraStorageStep = 50 * GIB)
    try {
        block(database)
    } finally {
        transaction(database) { SchemaUtils.drop(*billingTables) }
    }
}

private fun insertPlan(
    database: Database,
    id: String,
    tier: String,
    includedBytes: Long,
    extraStorageStep: Long?,
) {
    transaction(database) {
        PlansTable.insert {
            it[PlansTable.id] = id
            it[name] = id
            it[PlansTable.tier] = tier
            it[monthlyBytesLimit] = includedBytes
            it[backupCountLimit] = null
            it[features] = "{}"
            it[stripePriceId] = null
            it[playProductId] = null
            it[extraStorageStepBytes] = extraStorageStep
            it[active] = true
        }
    }
}

/** Creates the account [accountId] as one that joined during the beta. */
internal fun joinBeta(
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

/** Storage use the test sets per account. */
internal class SettableUsage : UsageCalculator {
    private val bytes = mutableMapOf<UUID, Long>()

    operator fun set(
        accountId: UUID,
        usedBytes: Long,
    ) {
        bytes[accountId] = usedBytes
    }

    override suspend fun storageBytes(accountId: UUID): Long = bytes[accountId] ?: 0L

    override suspend fun storageBytesOutsideSpaces(accountId: UUID): Long = storageBytes(accountId)

    override suspend fun backupCount(accountId: UUID): Int = 0
}

internal class SettableClock(
    var current: Instant = Instant.fromEpochSeconds(1_800_000_000),
) : Clock {
    override fun now(): Instant = current

    fun advance(by: Duration) {
        current += by
    }
}

internal data class MeterEvent(
    val eventName: String,
    val customerId: String,
    val value: Long,
    val identifier: String,
)

/**
 * A stand-in for Stripe that keeps customers and subscriptions in memory and behaves as Stripe
 * does for the calls billing makes. [pay] plays the part of a person finishing Checkout.
 */
internal class FakeStripe : StripeApi {
    val priceList =
        mutableListOf(
            price("price_cloud_m", "logdate_cloud_monthly", 399, "month"),
            price("price_cloud_y", "logdate_cloud_yearly", 3999, "year"),
            price("price_plus_m", "logdate_cloud_plus_monthly", 799, "month"),
            price("price_plus_y", "logdate_cloud_plus_yearly", 7999, "year"),
            price("price_plus_extra", "logdate_cloud_plus_extra_storage", 100, "month", metered = true),
        )
    val customers = mutableMapOf<String, UUID>()
    val subscriptions = linkedMapOf<String, StripeSubscription>()
    val checkouts = mutableListOf<StripeCheckoutRequest>()
    val meterEvents = mutableListOf<MeterEvent>()
    val invoicedNow = mutableListOf<String>()
    val portalReturnUrls = mutableListOf<String>()
    var subscriptionReads = 0

    /** Every call fails while set, as when Stripe cannot be reached. */
    var unreachable = false

    /** Reports for these customers fail, as when Stripe refuses one. */
    val refusedMeterCustomers = mutableSetOf<String>()

    private var nextId = 1
    private val periodEnd = 1_802_592_000L

    /** Finishes the newest checkout the way a successful payment does: the subscription becomes active. */
    fun pay(status: String = "active"): StripeSubscription {
        val checkout = checkouts.last()
        val id = "sub_${nextId++}"
        val subscription =
            StripeSubscription(
                id = id,
                customerId = checkout.customerId,
                status = status,
                cancelAtEpochSeconds = null,
                accountId = checkout.accountId.toString(),
                items = listOf(StripeSubscriptionItem("si_${nextId++}", priceList.first { it.id == checkout.priceId }, periodEnd)),
            )
        subscriptions[id] = subscription
        return subscription
    }

    fun setStatus(
        subscriptionId: String,
        status: String,
    ) {
        subscriptions[subscriptionId] = subscriptions.getValue(subscriptionId).copy(status = status)
    }

    override suspend fun prices(lookupKeys: List<String>): List<StripePrice> {
        failIfUnreachable()
        return priceList.filter { it.lookupKey in lookupKeys }
    }

    override suspend fun createCustomer(accountId: UUID): String {
        failIfUnreachable()
        return "cus_${nextId++}".also { customers[it] = accountId }
    }

    override suspend fun createCheckoutSession(request: StripeCheckoutRequest): String {
        failIfUnreachable()
        checkouts += request
        return "https://checkout.stripe.test/${checkouts.size}"
    }

    override suspend fun subscription(id: String): StripeSubscription? {
        failIfUnreachable()
        subscriptionReads++
        return subscriptions[id]
    }

    override suspend fun subscriptions(customerId: String): List<StripeSubscription> {
        failIfUnreachable()
        return subscriptions.values.filter { it.customerId == customerId }.reversed()
    }

    override suspend fun changePrice(
        subscriptionId: String,
        itemId: String,
        priceId: String,
        invoiceNow: Boolean,
    ): StripeSubscription {
        failIfUnreachable()
        if (invoiceNow) invoicedNow += subscriptionId
        val current = subscriptions.getValue(subscriptionId)
        val price = priceList.first { it.id == priceId }
        return current
            .copy(items = current.items.map { if (it.id == itemId) it.copy(price = price) else it })
            .also { subscriptions[subscriptionId] = it }
    }

    override suspend fun addMeteredItem(
        subscriptionId: String,
        priceId: String,
    ) {
        failIfUnreachable()
        val current = subscriptions.getValue(subscriptionId)
        val item = StripeSubscriptionItem("si_${nextId++}", priceList.first { it.id == priceId }, periodEnd)
        subscriptions[subscriptionId] = current.copy(items = current.items + item)
    }

    override suspend fun removeItem(itemId: String) {
        failIfUnreachable()
        subscriptions.replaceAll { _, subscription -> subscription.copy(items = subscription.items.filter { it.id != itemId }) }
    }

    override suspend fun cancelAtPeriodEnd(subscriptionId: String): StripeSubscription {
        failIfUnreachable()
        return subscriptions.getValue(subscriptionId).copy(cancelAtEpochSeconds = periodEnd).also { subscriptions[subscriptionId] = it }
    }

    override suspend fun keepSubscription(subscriptionId: String): StripeSubscription {
        failIfUnreachable()
        return subscriptions.getValue(subscriptionId).copy(cancelAtEpochSeconds = null).also { subscriptions[subscriptionId] = it }
    }

    override suspend fun cancelNow(subscriptionId: String) {
        failIfUnreachable()
        setStatus(subscriptionId, "canceled")
    }

    override suspend fun createPortalSession(
        customerId: String,
        returnUrl: String,
    ): String {
        failIfUnreachable()
        portalReturnUrls += returnUrl
        return "https://billing.stripe.test/$customerId"
    }

    override suspend fun reportMeterEvent(
        eventName: String,
        customerId: String,
        value: Long,
        identifier: String,
        timestampEpochSeconds: Long,
    ) {
        failIfUnreachable()
        if (customerId in refusedMeterCustomers) throw StripeException(400, "invalid_request_error", "refused")
        meterEvents += MeterEvent(eventName, customerId, value, identifier)
    }

    private fun failIfUnreachable() {
        if (unreachable) throw StripeException(status = 0, code = null, message = "Stripe could not be reached")
    }

    private fun price(
        id: String,
        lookupKey: String,
        amount: Long,
        interval: String,
        metered: Boolean = false,
    ) = StripePrice(id = id, lookupKey = lookupKey, unitAmount = amount, currency = "usd", interval = interval, metered = metered)
}
