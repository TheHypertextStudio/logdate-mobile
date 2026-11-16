package app.logdate.server.billing

import app.logdate.server.logging.BillingNotices
import io.github.aakira.napier.Napier
import java.util.UUID
import kotlin.time.Instant

/**
 * Makes an account's entitlement match a subscription as Stripe reports it.
 *
 * Webhooks, plan changes and refreshes all end here, and each passes a subscription freshly read
 * from Stripe, so events that arrive late or out of order cannot leave a stale entitlement.
 *
 * It also keeps the subscription's usage-billed item in step with the plan: a plan that sells
 * extra storage has the metered price attached, and any other plan does not. Stripe Checkout
 * cannot create a subscription that mixes a yearly price with a monthly metered one, so the
 * metered item is always added here, after the subscription exists.
 */
class SubscriptionSync(
    private val store: BillingStore,
    private val stripe: StripeApi,
    private val prices: StripePlanPrices,
) {
    /** Applies [subscription] and returns the account it belongs to, or null when it is not one of ours. */
    suspend fun apply(subscription: StripeSubscription): UUID? {
        val accountId =
            subscription.accountId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: store.accountForStripeCustomer(subscription.customerId)
        if (accountId == null) {
            Napier.e(BillingNotices.SUBSCRIPTION_UNOWNED)
            return null
        }
        val status =
            when (subscription.status) {
                "active", "trialing" -> BillingStore.STATUS_ACTIVE

                "past_due" -> BillingStore.STATUS_PAST_DUE

                // Checkout was opened but the first payment has not succeeded. Nothing is granted.
                "incomplete" -> return accountId

                // canceled, unpaid, incomplete_expired, paused: the subscription gives nothing.
                else -> null
            }
        if (status == null) {
            store.clearSubscription(accountId, subscription.id)
            return accountId
        }
        val fixedItem = subscription.items.firstOrNull { !it.price.metered }
        val plan = StripeCatalogNames.planIdOf(fixedItem?.price?.lookupKey)?.let { store.plan(it) }
        if (fixedItem == null || plan == null) {
            Napier.e(BillingNotices.SUBSCRIPTION_NOT_A_PLAN)
            return accountId
        }
        alignMeteredItem(subscription, plan)
        store.saveSubscription(
            StoredSubscription(
                accountId = accountId,
                planId = plan.id,
                subscriptionId = subscription.id,
                status = status,
                currentPeriodEnd = fixedItem.currentPeriodEndEpochSeconds?.let(Instant::fromEpochSeconds),
                cancelAt = subscription.cancelAtEpochSeconds?.let(Instant::fromEpochSeconds),
                interval = fixedItem.price.interval,
            ),
        )
        return accountId
    }

    private suspend fun alignMeteredItem(
        subscription: StripeSubscription,
        plan: BillingPlan,
    ) {
        val meteredItem = subscription.items.firstOrNull { it.price.metered }
        if (plan.extraStorageStepBytes == null) {
            if (meteredItem != null) stripe.removeItem(meteredItem.id)
            return
        }
        if (meteredItem != null) return
        val price = prices.extraStoragePrice(plan.id)
        if (price == null) {
            Napier.e(BillingNotices.METERED_PRICE_MISSING)
            return
        }
        stripe.addMeteredItem(subscription.id, price.id)
    }
}
