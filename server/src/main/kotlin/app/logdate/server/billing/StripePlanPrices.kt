package app.logdate.server.billing

import app.logdate.server.logging.BillingNotices
import app.logdate.shared.model.BillingInterval
import io.github.aakira.napier.Napier
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What each plan costs, read from Stripe by lookup key and remembered for [ttl].
 *
 * Stripe is the only place a price is written, so the catalog shows exactly what Stripe will
 * charge. When Stripe cannot be reached, the last prices read keep being used; with none ever
 * read, plans have no price and the catalog lists them as not purchasable.
 */
class StripePlanPrices(
    private val stripe: StripeApi,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 10.minutes,
) {
    private val lock = Mutex()
    private var cached: Map<String, StripePrice> = emptyMap()
    private var cachedPlans: Set<String> = emptySet()
    private var fetchedAt: Instant? = null

    /** The price of [planId] billed every [interval], or null when Stripe has none. */
    suspend fun fixedPrice(
        planId: String,
        interval: BillingInterval,
    ): StripePrice? {
        val key =
            when (interval) {
                BillingInterval.MONTH -> StripeCatalogNames.monthlyLookupKey(planId)
                BillingInterval.YEAR -> StripeCatalogNames.yearlyLookupKey(planId)
            }
        return pricesFor(setOf(planId))[key]?.takeIf { !it.metered }
    }

    /** The usage-billed price of one step of extra storage on [planId], or null when Stripe has none. */
    suspend fun extraStoragePrice(planId: String): StripePrice? =
        pricesFor(setOf(planId))[StripeCatalogNames.extraStorageLookupKey(planId)]?.takeIf { it.metered }

    /** Every price Stripe has for [planIds], by lookup key. */
    suspend fun pricesFor(planIds: Set<String>): Map<String, StripePrice> =
        lock.withLock {
            val now = clock.now()
            val fresh = fetchedAt?.let { now - it < ttl } == true && cachedPlans.containsAll(planIds)
            if (fresh) return@withLock cached
            val plans = cachedPlans + planIds
            val lookupKeys =
                plans.flatMap {
                    listOf(
                        StripeCatalogNames.monthlyLookupKey(it),
                        StripeCatalogNames.yearlyLookupKey(it),
                        StripeCatalogNames.extraStorageLookupKey(it),
                    )
                }
            try {
                cached = stripe.prices(lookupKeys).mapNotNull { price -> price.lookupKey?.let { it to price } }.toMap()
                cachedPlans = plans
                fetchedAt = now
            } catch (e: StripeException) {
                Napier.e(BillingNotices.PRICES_UNREADABLE, e)
            }
            cached
        }
}
