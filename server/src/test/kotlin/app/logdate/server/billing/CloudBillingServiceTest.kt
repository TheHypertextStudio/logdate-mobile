package app.logdate.server.billing

import app.logdate.server.entitlements.StoredEntitlementService
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.EntitlementStatusWire
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Buying, changing and cancelling a plan, against an in-memory database and a stand-in for Stripe.
 * The plan an account ends up on is always read through the same entitlement service the sync
 * endpoints use, so these tests see what an upload would see.
 */
class CloudBillingServiceTest {
    private val accountId = UUID.randomUUID()
    private val plusMonthly = CloudPlanSelection("cloud_plus", BillingInterval.MONTH)
    private val plusYearly = CloudPlanSelection("cloud_plus", BillingInterval.YEAR)
    private val baseMonthly = CloudPlanSelection("cloud", BillingInterval.MONTH)

    private class Billing(
        database: Database,
        launched: Boolean = true,
        withStripe: Boolean = true,
    ) {
        val stripe = FakeStripe()
        val usage = SettableUsage()
        val store = BillingStore(database)
        private val prices = StripePlanPrices(stripe)
        private val sync = SubscriptionSync(store, stripe, prices)
        val service =
            CloudBillingService(
                store = store,
                entitlements = StoredEntitlementService(database),
                usage = usage,
                launch = CloudLaunch(launched = launched),
                stripe = stripe.takeIf { withStripe },
                prices = prices.takeIf { withStripe },
                sync = sync.takeIf { withStripe },
                returnUrl = "https://cloud.logdate.test/api/v1/billing/return",
            )

        /** Starts a checkout, pays it, and reads the result back as the app does on return. */
        suspend fun buy(
            accountId: UUID,
            selection: CloudPlanSelection,
        ) {
            service.startCheckout(accountId, selection)
            stripe.pay()
            service.refresh(accountId)
        }
    }

    private suspend fun refusalOf(block: suspend () -> Unit): BillingRefusal = assertFailsWith<BillingRefused> { block() }.refusal

    @Test
    fun `before launch Plus can be bought and the base plan cannot`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database, launched = false)

                val refused = refusalOf { billing.service.startCheckout(accountId, baseMonthly) }
                billing.buy(accountId, plusMonthly)

                assertEquals(BillingRefusal.NotOnSale, refused, "the base plan is free during the beta and is not sold")
                assertEquals("cloud_plus", billing.service.subscription(accountId).planId)
                assertTrue(billing.service.subscription(accountId).purchasesAvailable)
            }
        }

    @Test
    fun `before launch a Plus subscriber cannot move down to the base plan by paying for it`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database, launched = false)
                billing.buy(accountId, plusMonthly)

                assertEquals(BillingRefusal.NotOnSale, refusalOf { billing.service.changePlan(accountId, baseMonthly) })
            }
        }

    @Test
    fun `nothing can be bought on a server without Stripe, and the plan is still readable`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database, withStripe = false)
                joinBeta(database, accountId)

                assertEquals(BillingRefusal.NotOnSale, refusalOf { billing.service.startCheckout(accountId, plusMonthly) })
                val subscription = billing.service.subscription(accountId)
                assertEquals("cloud", subscription.planId)
                assertTrue(subscription.includedWithBeta)
                assertFalse(subscription.purchasesAvailable)
                assertFalse(subscription.paymentDetailsAvailable)
            }
        }

    @Test
    fun `a beta member is on the base plan at no charge and cannot be sold it`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                joinBeta(database, accountId)

                val subscription = billing.service.subscription(accountId)
                assertEquals("cloud", subscription.planId)
                assertTrue(subscription.includedWithBeta)
                assertTrue(subscription.betaMember)
                assertEquals("cloud", subscription.freePlanId)
                assertNull(subscription.interval)
                assertNull(subscription.renewsAtEpochSeconds)
                assertEquals(50 * GIB, subscription.storageIncludedBytes)

                assertEquals(BillingRefusal.IncludedWithBeta, refusalOf { billing.service.startCheckout(accountId, baseMonthly) })
                assertTrue(billing.stripe.checkouts.isEmpty())
            }
        }

    @Test
    fun `opening a checkout grants nothing until Stripe reports a paid subscription`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)

                val page = billing.service.startCheckout(accountId, plusMonthly)

                assertEquals("https://checkout.stripe.test/1", page.url)
                val checkout = billing.stripe.checkouts.single()
                assertEquals("price_plus_m", checkout.priceId)
                assertEquals(accountId, checkout.accountId)
                assertEquals("https://cloud.logdate.test/api/v1/billing/return?result=done", checkout.successUrl)
                assertEquals("https://cloud.logdate.test/api/v1/billing/return?result=cancelled", checkout.cancelUrl)
                assertNull(billing.service.refresh(accountId).planId, "an abandoned checkout leaves the account with no plan")

                billing.stripe.pay(status = "incomplete")
                assertNull(billing.service.refresh(accountId).planId, "a first payment that has not succeeded grants nothing")
            }
        }

    @Test
    fun `paying for Plus puts the account on Plus and attaches metered extra storage`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)

                billing.buy(accountId, plusYearly)

                val subscription = billing.service.subscription(accountId)
                assertEquals("cloud_plus", subscription.planId)
                assertEquals(EntitlementStatusWire.ACTIVE, subscription.status)
                assertEquals(BillingInterval.YEAR, subscription.interval)
                assertEquals(1_802_592_000L, subscription.renewsAtEpochSeconds)
                assertNull(subscription.endsAtEpochSeconds)
                assertFalse(subscription.includedWithBeta)
                assertTrue(subscription.paymentDetailsAvailable)
                val items =
                    billing.stripe.subscriptions.values
                        .single()
                        .items
                assertEquals(
                    listOf("price_plus_y", "price_plus_extra"),
                    items.map { it.price.id },
                    "a yearly Plus subscription carries the monthly metered price for extra storage",
                )
            }
        }

    @Test
    fun `the base plan is sold without a metered item`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)

                billing.buy(accountId, baseMonthly)

                assertEquals("cloud", billing.service.subscription(accountId).planId)
                assertEquals(
                    listOf("price_cloud_m"),
                    billing.stripe.subscriptions.values
                        .single()
                        .items
                        .map { it.price.id },
                )
            }
        }

    @Test
    fun `an account that already pays cannot open a second checkout`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, baseMonthly)

                assertEquals(BillingRefusal.AlreadySubscribed, refusalOf { billing.service.startCheckout(accountId, plusMonthly) })
                assertEquals(1, billing.stripe.checkouts.size)
            }
        }

    @Test
    fun `a second checkout reuses the account's Stripe customer`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)

                billing.service.startCheckout(accountId, baseMonthly)
                billing.service.startCheckout(accountId, plusMonthly)

                assertEquals(1, billing.stripe.customers.size)
                assertEquals(
                    1,
                    billing.stripe.checkouts
                        .map { it.customerId }
                        .toSet()
                        .size,
                )
            }
        }

    @Test
    fun `moving up to Plus charges the difference now and starts metering`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, baseMonthly)

                val changed = billing.service.changePlan(accountId, plusMonthly)

                assertEquals("cloud_plus", changed.planId)
                val subscription =
                    billing.stripe.subscriptions.values
                        .single()
                assertEquals(listOf("price_plus_m", "price_plus_extra"), subscription.items.map { it.price.id })
                assertEquals(listOf(subscription.id), billing.stripe.invoicedNow)
            }
        }

    @Test
    fun `moving down to the base plan stops metering and is not charged now`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)
                billing.usage[accountId] = 20 * GIB

                val changed = billing.service.changePlan(accountId, baseMonthly)

                assertEquals("cloud", changed.planId)
                assertEquals(
                    listOf("price_cloud_m"),
                    billing.stripe.subscriptions.values
                        .single()
                        .items
                        .map { it.price.id },
                )
                assertTrue(billing.stripe.invoicedNow.isEmpty())
            }
        }

    @Test
    fun `moving to a plan that holds less than the account stores is refused with the numbers`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)
                billing.usage[accountId] = 70 * GIB

                val refused = refusalOf { billing.service.changePlan(accountId, baseMonthly) }

                assertEquals(BillingRefusal.StorageExceedsPlan(usedBytes = 70 * GIB, includedBytes = 50 * GIB), refused)
                assertEquals("cloud_plus", billing.service.subscription(accountId).planId)
                assertEquals(
                    "price_plus_m",
                    billing.stripe.subscriptions.values
                        .single()
                        .items
                        .first()
                        .price.id,
                )
            }
        }

    @Test
    fun `switching Plus from monthly to yearly keeps the plan and its metering`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)

                val changed = billing.service.changePlan(accountId, plusYearly)

                assertEquals("cloud_plus", changed.planId)
                assertEquals(BillingInterval.YEAR, changed.interval)
                assertEquals(
                    listOf("price_plus_y", "price_plus_extra"),
                    billing.stripe.subscriptions.values
                        .single()
                        .items
                        .map { it.price.id },
                )
            }
        }

    @Test
    fun `cancelling keeps the plan until the paid period ends and can be undone`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)

                val cancelled = billing.service.cancel(accountId)

                assertEquals("cloud_plus", cancelled.planId, "the plan is paid for until it ends")
                assertEquals(1_802_592_000L, cancelled.endsAtEpochSeconds)
                assertNull(cancelled.renewsAtEpochSeconds)

                val kept = billing.service.keep(accountId)

                assertNull(kept.endsAtEpochSeconds)
                assertEquals(1_802_592_000L, kept.renewsAtEpochSeconds)
            }
        }

    @Test
    fun `when a cancelled Plus ends a beta member is back on the base plan at no charge`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                joinBeta(database, accountId)
                billing.buy(accountId, plusMonthly)
                billing.service.cancel(accountId)

                billing.stripe.setStatus(
                    billing.stripe.subscriptions.keys
                        .single(),
                    "canceled",
                )
                val ended = billing.service.refresh(accountId)

                assertEquals("cloud", ended.planId)
                assertTrue(ended.includedWithBeta)
                assertNull(ended.endsAtEpochSeconds)
            }
        }

    @Test
    fun `when a subscription ends an account that joined after launch has no plan`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, baseMonthly)

                billing.stripe.setStatus(
                    billing.stripe.subscriptions.keys
                        .single(),
                    "canceled",
                )
                val ended = billing.service.refresh(accountId)

                assertNull(ended.planId)
                assertEquals(EntitlementStatusWire.CANCELLED, ended.status)
                assertEquals(0L, ended.storageIncludedBytes)
                assertFalse(ended.betaMember)
                assertNull(ended.freePlanId)
            }
        }

    @Test
    fun `a failing payment shows as past due and keeps the plan`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)

                billing.stripe.setStatus(
                    billing.stripe.subscriptions.keys
                        .single(),
                    "past_due",
                )
                val pastDue = billing.service.refresh(accountId)

                assertEquals("cloud_plus", pastDue.planId)
                assertEquals(EntitlementStatusWire.PAST_DUE, pastDue.status)
            }
        }

    @Test
    fun `the subscription reports storage in use and the extra steps it takes`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)

                billing.usage[accountId] = 250 * GIB
                assertEquals(0, billing.service.subscription(accountId).extraStorageSteps)
                billing.usage[accountId] = 250 * GIB + 1
                assertEquals(1, billing.service.subscription(accountId).extraStorageSteps)
                billing.usage[accountId] = 381 * GIB
                val subscription = billing.service.subscription(accountId)
                assertEquals(3, subscription.extraStorageSteps)
                assertEquals(381 * GIB, subscription.storageUsedBytes)
                assertEquals(250 * GIB, subscription.storageIncludedBytes)
            }
        }

    @Test
    fun `payment details open at Stripe and come back to the return page`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, baseMonthly)

                val page = billing.service.paymentDetails(accountId)

                assertTrue(page.url.startsWith("https://billing.stripe.test/cus_"))
                assertEquals(listOf("https://cloud.logdate.test/api/v1/billing/return"), billing.stripe.portalReturnUrls)
            }
        }

    @Test
    fun `an account with no paid plan has nothing to cancel, change or manage`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                joinBeta(database, accountId)

                assertEquals(BillingRefusal.NoSubscription, refusalOf { billing.service.cancel(accountId) })
                assertEquals(BillingRefusal.NoSubscription, refusalOf { billing.service.changePlan(accountId, plusMonthly) })
                assertEquals(BillingRefusal.NoSubscription, refusalOf { billing.service.paymentDetails(accountId) })
            }
        }

    @Test
    fun `deleting an account ends what Stripe bills it for at once`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)

                billing.service.endForDeletedAccount(accountId)

                assertEquals(
                    "canceled",
                    billing.stripe.subscriptions.values
                        .single()
                        .status,
                )
                assertNull(billing.store.subscription(accountId))
            }
        }

    @Test
    fun `an account is not deleted quietly while Stripe cannot be told to stop charging`() =
        runTest {
            withBillingDatabase { database ->
                val billing = Billing(database)
                billing.buy(accountId, plusMonthly)
                billing.stripe.unreachable = true

                assertFailsWith<StripeException> { billing.service.endForDeletedAccount(accountId) }
                assertEquals(
                    "active",
                    billing.stripe.subscriptions.values
                        .single()
                        .status,
                )
            }
        }
}
