package app.logdate.server.billing

import java.util.UUID

/**
 * The part of Stripe's API LogDate Cloud uses, behind an interface so billing logic can be tested
 * without Stripe. [HttpStripeApi] is the real one.
 */
interface StripeApi {
    /** The active prices that carry any of [lookupKeys]. */
    suspend fun prices(lookupKeys: List<String>): List<StripePrice>

    /** Creates the Stripe customer for [accountId] and returns its identifier. */
    suspend fun createCustomer(accountId: UUID): String

    /** Starts a Checkout page for a new subscription and returns its address. */
    suspend fun createCheckoutSession(request: StripeCheckoutRequest): String

    /** The subscription [id], or null when Stripe has none by that identifier. */
    suspend fun subscription(id: String): StripeSubscription?

    /** Every subscription [customerId] has or had, newest first. */
    suspend fun subscriptions(customerId: String): List<StripeSubscription>

    /**
     * Moves the subscription item [itemId] to [priceId]. With [invoiceNow] the difference is
     * charged at once; otherwise it is settled on the next invoice.
     */
    suspend fun changePrice(
        subscriptionId: String,
        itemId: String,
        priceId: String,
        invoiceNow: Boolean,
    ): StripeSubscription

    /** Adds a usage-billed price to a subscription. */
    suspend fun addMeteredItem(
        subscriptionId: String,
        priceId: String,
    )

    suspend fun removeItem(itemId: String)

    /** Sets the subscription to stop at the end of what has been paid for. */
    suspend fun cancelAtPeriodEnd(subscriptionId: String): StripeSubscription

    /** Undoes [cancelAtPeriodEnd]. */
    suspend fun keepSubscription(subscriptionId: String): StripeSubscription

    /** Ends the subscription immediately. */
    suspend fun cancelNow(subscriptionId: String)

    /** Opens Stripe's page for payment methods and receipts and returns its address. */
    suspend fun createPortalSession(
        customerId: String,
        returnUrl: String,
    ): String

    /**
     * Reports [value] for the meter listening to [eventName]. [identifier] makes a retry of the
     * same report harmless.
     */
    suspend fun reportMeterEvent(
        eventName: String,
        customerId: String,
        value: Long,
        identifier: String,
        timestampEpochSeconds: Long,
    )
}

data class StripePrice(
    val id: String,
    val lookupKey: String?,
    /** In the currency's smallest unit. */
    val unitAmount: Long,
    val currency: String,
    /** `month` or `year`. */
    val interval: String,
    /** Billed by reported use rather than a fixed quantity. */
    val metered: Boolean,
)

data class StripeCheckoutRequest(
    val customerId: String,
    val priceId: String,
    val accountId: UUID,
    val planId: String,
    val successUrl: String,
    val cancelUrl: String,
)

data class StripeSubscription(
    val id: String,
    val customerId: String,
    /** Stripe's status: `active`, `past_due`, `canceled`, `incomplete`, and so on. */
    val status: String,
    val cancelAtEpochSeconds: Long?,
    /** The LogDate account recorded on the subscription when it was created. */
    val accountId: String?,
    val items: List<StripeSubscriptionItem>,
)

data class StripeSubscriptionItem(
    val id: String,
    val price: StripePrice,
    val currentPeriodEndEpochSeconds: Long?,
)

/** Stripe refused a request or could not be reached. [code] is Stripe's error code when it gave one. */
class StripeException(
    val status: Int,
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The names LogDate Cloud's products go by in Stripe. Prices are found by lookup key, never by identifier. */
object StripeCatalogNames {
    /** The event name the extra-storage meter listens to. */
    const val EXTRA_STORAGE_METER_EVENT: String = "logdate_cloud_extra_storage"

    fun monthlyLookupKey(planId: String): String = "logdate_${planId}_monthly"

    fun yearlyLookupKey(planId: String): String = "logdate_${planId}_yearly"

    fun extraStorageLookupKey(planId: String): String = "logdate_${planId}_extra_storage"

    /** The plan a fixed-price lookup key belongs to, or null when the key is not one of ours. */
    fun planIdOf(lookupKey: String?): String? {
        val rest = lookupKey?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val suffix = FIXED_PRICE_SUFFIXES.firstOrNull { rest.endsWith(it) } ?: return null
        return rest.removeSuffix(suffix)
    }

    private const val PREFIX = "logdate_"
    private val FIXED_PRICE_SUFFIXES = listOf("_monthly", "_yearly")
}
