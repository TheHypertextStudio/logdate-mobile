package app.logdate.server.billing

import app.logdate.server.entitlements.Entitlement
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.entitlements.EntitlementStatus
import app.logdate.server.entitlements.StoredEntitlementService
import app.logdate.server.entitlements.UsageCalculator
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.BillingPage
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.EntitlementStatusWire
import java.util.UUID

/**
 * Buying, changing and cancelling a LogDate Cloud plan, and reading what an account pays.
 *
 * Nothing here grants access by itself. Access follows the subscription as Stripe reports it:
 * every change is made in Stripe first and then read back through [SubscriptionSync], the same
 * path webhooks take, so the entitlement never runs ahead of a payment.
 *
 * [stripe] and [prices] are null on a deployment that has no Stripe keys. Reading a subscription
 * still works there; every purchase is refused with [BillingRefusal.NotOnSale]. With Stripe, Plus
 * can be bought at once, and the base plan from launch.
 */
class CloudBillingService(
    private val store: BillingStore,
    private val entitlements: EntitlementService,
    private val usage: UsageCalculator,
    private val launch: CloudLaunch,
    private val stripe: StripeApi?,
    private val prices: StripePlanPrices?,
    private val sync: SubscriptionSync?,
    private val returnUrl: String,
) {
    /**
     * Whether a plan can be bought, changed or cancelled here right now: the server is connected to
     * Stripe. Which plans are on sale is a separate matter; before launch that is Plus alone.
     */
    val purchasesAvailable: Boolean
        get() = stripe != null && prices != null && sync != null

    suspend fun subscription(accountId: UUID): CloudSubscription {
        val entitlement = entitlements.resolve(accountId)
        val stored = store.subscription(accountId)?.takeIf { it.status != BillingStore.STATUS_CANCELLED }
        val used = usage.storageBytes(accountId)
        val included = entitlement.limits.storageBytes
        val betaMember = store.isBetaMember(accountId)
        return CloudSubscription(
            planId = entitlement.planId.takeUnless { it == Entitlement.NO_PLAN_ID },
            status = entitlement.status.toWire(),
            includedWithBeta = entitlement.includedWithBeta,
            betaMember = betaMember,
            freePlanId = StoredEntitlementService.BASE_PLAN_ID.takeIf { betaMember },
            interval = stored?.interval?.toBillingInterval(),
            renewsAtEpochSeconds = stored?.takeIf { it.cancelAt == null }?.currentPeriodEnd?.epochSeconds,
            endsAtEpochSeconds = stored?.cancelAt?.epochSeconds,
            storageUsedBytes = used,
            storageIncludedBytes = included,
            extraStorageSteps = extraStorageSteps(used, included, entitlement.limits.extraStorage?.stepBytes),
            purchasesAvailable = purchasesAvailable,
            paymentDetailsAvailable = stripe != null && store.stripeCustomerId(accountId) != null,
        )
    }

    /** Opens a Stripe Checkout page for an account that Stripe bills for nothing yet. */
    suspend fun startCheckout(
        accountId: UUID,
        selection: CloudPlanSelection,
    ): BillingPage {
        val (stripe, prices) = requireStripeAndPrices()
        val plan = store.plan(selection.planId) ?: throw BillingRefused(BillingRefusal.UnknownPlan)
        if (store.subscription(accountId)?.status?.let { it != BillingStore.STATUS_CANCELLED } == true) {
            throw BillingRefused(BillingRefusal.AlreadySubscribed)
        }
        refuseBasePlanWhereItIsNotSold(accountId, plan)
        val price = prices.fixedPrice(plan.id, selection.interval) ?: throw BillingRefused(BillingRefusal.NotOnSale)
        val customerId =
            store.stripeCustomerId(accountId)
                ?: store.saveStripeCustomer(accountId, stripe.createCustomer(accountId))
        val url =
            stripe.createCheckoutSession(
                StripeCheckoutRequest(
                    customerId = customerId,
                    priceId = price.id,
                    accountId = accountId,
                    planId = plan.id,
                    successUrl = "$returnUrl?result=done",
                    cancelUrl = "$returnUrl?result=cancelled",
                ),
            )
        return BillingPage(url)
    }

    /** Moves a paid subscription to another plan or billing interval. */
    suspend fun changePlan(
        accountId: UUID,
        selection: CloudPlanSelection,
    ): CloudSubscription {
        val (stripe, prices) = requireStripeAndPrices()
        val stored = liveSubscription(accountId)
        val target = store.plan(selection.planId) ?: throw BillingRefused(BillingRefusal.UnknownPlan)
        refuseBasePlanWhereItIsNotSold(accountId, target)
        val used = usage.storageBytes(accountId)
        val included = target.includedBytes
        if (target.extraStorageStepBytes == null && included != null && used > included) {
            throw BillingRefused(BillingRefusal.StorageExceedsPlan(usedBytes = used, includedBytes = included))
        }
        val current = stripe.subscription(stored.subscriptionId) ?: throw BillingRefused(BillingRefusal.NoSubscription)
        val fixedItem = current.items.firstOrNull { !it.price.metered } ?: throw BillingRefused(BillingRefusal.NoSubscription)
        val price = prices.fixedPrice(target.id, selection.interval) ?: throw BillingRefused(BillingRefusal.NotOnSale)
        if (fixedItem.price.id != price.id) {
            val updated =
                stripe.changePrice(
                    subscriptionId = current.id,
                    itemId = fixedItem.id,
                    priceId = price.id,
                    // Moving up is charged now, so the larger plan is paid for when it starts. Moving
                    // down leaves a credit that the next invoice uses.
                    invoiceNow = price.monthlyAmount() > fixedItem.price.monthlyAmount(),
                )
            requireSync().apply(updated)
        }
        return subscription(accountId)
    }

    /** Stops the subscription at the end of what has been paid for. Access stays until then. */
    suspend fun cancel(accountId: UUID): CloudSubscription {
        val stripe = requireStripe()
        val stored = liveSubscription(accountId)
        requireSync().apply(stripe.cancelAtPeriodEnd(stored.subscriptionId))
        return subscription(accountId)
    }

    /** Undoes [cancel] while the subscription has not yet stopped. */
    suspend fun keep(accountId: UUID): CloudSubscription {
        val stripe = requireStripe()
        val stored = liveSubscription(accountId)
        requireSync().apply(stripe.keepSubscription(stored.subscriptionId))
        return subscription(accountId)
    }

    /** Opens Stripe's page for the account's payment methods and receipts. */
    suspend fun paymentDetails(accountId: UUID): BillingPage {
        val stripe = requireStripe()
        val customerId = store.stripeCustomerId(accountId) ?: throw BillingRefused(BillingRefusal.NoSubscription)
        return BillingPage(stripe.createPortalSession(customerId, returnUrl))
    }

    /**
     * Reads the account's subscriptions back from Stripe. This is how the app picks up a finished
     * checkout without depending on the webhook having arrived first.
     */
    suspend fun refresh(accountId: UUID): CloudSubscription {
        val stripe = stripe
        val sync = sync
        val customerId = store.stripeCustomerId(accountId)
        if (stripe != null && sync != null && customerId != null) {
            // Oldest first, so when Stripe holds more than one, the newest decides what is stored.
            stripe.subscriptions(customerId).asReversed().forEach { sync.apply(it) }
        }
        return subscription(accountId)
    }

    /** Ends whatever Stripe bills the account for, at once. For an account being deleted. */
    suspend fun endForDeletedAccount(accountId: UUID) {
        val stripe = stripe ?: return
        val stored = store.subscription(accountId) ?: return
        if (stored.status == BillingStore.STATUS_CANCELLED) return
        stripe.cancelNow(stored.subscriptionId)
        store.clearSubscription(accountId, stored.subscriptionId)
    }

    private suspend fun liveSubscription(accountId: UUID): StoredSubscription =
        store.subscription(accountId)?.takeIf { it.status != BillingStore.STATUS_CANCELLED }
            ?: throw BillingRefused(BillingRefusal.NoSubscription)

    /**
     * The base plan is never sold to a beta member, who has it at no charge, and is not sold to
     * anyone before launch.
     */
    private suspend fun refuseBasePlanWhereItIsNotSold(
        accountId: UUID,
        plan: BillingPlan,
    ) {
        if (plan.id != StoredEntitlementService.BASE_PLAN_ID) return
        if (store.isBetaMember(accountId)) throw BillingRefused(BillingRefusal.IncludedWithBeta)
        if (!launch.launched) throw BillingRefused(BillingRefusal.NotOnSale)
    }

    private fun requireStripeAndPrices(): Pair<StripeApi, StripePlanPrices> =
        (stripe ?: throw BillingRefused(BillingRefusal.NotOnSale)) to (prices ?: throw BillingRefused(BillingRefusal.NotOnSale))

    private fun requireStripe(): StripeApi = stripe ?: throw BillingRefused(BillingRefusal.NotOnSale)

    private fun requireSync(): SubscriptionSync = sync ?: throw BillingRefused(BillingRefusal.NotOnSale)
}

/** How many whole steps of extra storage [usedBytes] takes past [includedBytes]. */
fun extraStorageSteps(
    usedBytes: Long,
    includedBytes: Long?,
    stepBytes: Long?,
): Int {
    if (includedBytes == null || stepBytes == null || stepBytes <= 0 || usedBytes <= includedBytes) return 0
    val extra = usedBytes - includedBytes
    return ((extra + stepBytes - 1) / stepBytes).toInt()
}

/** A price as a monthly amount, so a yearly price and a monthly one can be compared. */
private fun StripePrice.monthlyAmount(): Long = if (interval == "year") unitAmount / MONTHS_PER_YEAR else unitAmount

private const val MONTHS_PER_YEAR = 12

internal fun String.toBillingInterval(): BillingInterval? =
    when (this) {
        "month" -> BillingInterval.MONTH
        "year" -> BillingInterval.YEAR
        else -> null
    }

private fun EntitlementStatus.toWire(): EntitlementStatusWire =
    when (this) {
        EntitlementStatus.ACTIVE -> EntitlementStatusWire.ACTIVE
        EntitlementStatus.PAST_DUE -> EntitlementStatusWire.PAST_DUE
        EntitlementStatus.GRACE -> EntitlementStatusWire.GRACE
        EntitlementStatus.CANCELLED -> EntitlementStatusWire.CANCELLED
        EntitlementStatus.SELF_HOST -> EntitlementStatusWire.SELF_HOST
    }

/** Why a billing request was refused. Each has a stable [code] a client can act on. */
sealed class BillingRefusal(
    val code: String,
    val message: String,
) {
    /** That plan is not being sold here: the server has no Stripe keys, or it is the base plan before launch. */
    data object NotOnSale : BillingRefusal("PLANS_NOT_ON_SALE", "That plan is not on sale yet.")

    data object UnknownPlan : BillingRefusal("UNKNOWN_PLAN", "That plan does not exist.")

    /** The account already pays for a plan; it changes that one rather than buying a second. */
    data object AlreadySubscribed : BillingRefusal("ALREADY_SUBSCRIBED", "This account already has a plan. Change it instead.")

    data object NoSubscription : BillingRefusal("NO_SUBSCRIPTION", "This account has no paid plan.")

    data object IncludedWithBeta :
        BillingRefusal("INCLUDED_WITH_BETA", "LogDate Cloud is free for this account, so there is nothing to buy.")

    data class StorageExceedsPlan(
        val usedBytes: Long,
        val includedBytes: Long,
    ) : BillingRefusal("STORAGE_EXCEEDS_PLAN", "This account stores more than that plan holds.")
}

class BillingRefused(
    val refusal: BillingRefusal,
) : Exception(refusal.message)
