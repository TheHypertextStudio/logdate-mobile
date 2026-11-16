package app.logdate.server.billing

import app.logdate.server.entitlements.StoredEntitlementService
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudPlanSelection
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Stripe's notifications: only signed ones count, and each brings the account in line with Stripe. */
class StripeWebhookHandlerTest {
    private val accountId = UUID.randomUUID()
    private val secret = "whsec_test_secret"

    private inner class Webhooks(
        database: Database,
    ) {
        val stripe = FakeStripe()
        val clock = SettableClock()
        val store = BillingStore(database, clock)
        private val prices = StripePlanPrices(stripe, clock)
        private val sync = SubscriptionSync(store, stripe, prices)
        val entitlements = StoredEntitlementService(database)
        val service =
            CloudBillingService(
                store = store,
                entitlements = entitlements,
                usage = SettableUsage(),
                launch = CloudLaunch(launched = true),
                stripe = stripe,
                prices = prices,
                sync = sync,
                returnUrl = "https://cloud.logdate.test/return",
            )
        val handler = StripeWebhookHandler(StripeSignatureVerifier(secret), stripe, sync, store, clock)

        /** Opens a checkout and pays it, without the app reading the result back. */
        suspend fun paidCheckout(planId: String = "cloud_plus"): StripeSubscription {
            service.startCheckout(accountId, CloudPlanSelection(planId, BillingInterval.MONTH))
            return stripe.pay()
        }

        suspend fun deliver(
            body: String,
            signedWith: String = secret,
        ) = handler.handle(body, signature(body, signedWith, clock.now().epochSeconds))
    }

    private fun signature(
        body: String,
        secret: String,
        timestamp: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val digest = mac.doFinal("$timestamp.$body".toByteArray()).joinToString("") { "%02x".format(it) }
        return "t=$timestamp,v1=$digest"
    }

    private fun subscriptionEvent(
        eventId: String,
        type: String,
        subscriptionId: String,
        statusInEvent: String = "active",
    ) = """{"id":"$eventId","type":"$type","data":{"object":{"id":"$subscriptionId","object":"subscription","status":"$statusInEvent"}}}"""

    @Test
    fun `a finished checkout puts the account on its plan without the app asking`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)
                val subscription = webhooks.paidCheckout()
                val event =
                    """{"id":"evt_1","type":"checkout.session.completed","data":{"object":{"id":"cs_1","subscription":"${subscription.id}"}}}"""

                val outcome = webhooks.deliver(event)

                assertIs<StripeWebhookHandler.Outcome.Handled>(outcome)
                assertEquals("cloud_plus", webhooks.entitlements.resolve(accountId).planId)
            }
        }

    @Test
    fun `an event with a wrong signature changes nothing`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)
                val subscription = webhooks.paidCheckout()

                val outcome =
                    webhooks.deliver(
                        subscriptionEvent("evt_forged", "customer.subscription.created", subscription.id),
                        signedWith = "someone-elses-secret",
                    )

                assertIs<StripeWebhookHandler.Outcome.Rejected>(outcome)
                assertEquals("none", webhooks.entitlements.resolve(accountId).planId)
                assertEquals(0, webhooks.stripe.subscriptionReads)
            }
        }

    @Test
    fun `the account follows what Stripe says now, not what a late event says`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)
                val subscription = webhooks.paidCheckout()
                webhooks.stripe.setStatus(subscription.id, "canceled")

                // An older "active" event arrives after the subscription has already ended.
                webhooks.deliver(subscriptionEvent("evt_late", "customer.subscription.updated", subscription.id, statusInEvent = "active"))

                assertEquals("none", webhooks.entitlements.resolve(accountId).planId)
            }
        }

    @Test
    fun `a subscription Stripe has ended leaves a beta member on the base plan`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)
                joinBeta(database, accountId)
                val subscription = webhooks.paidCheckout()
                webhooks.deliver(subscriptionEvent("evt_1", "customer.subscription.created", subscription.id))
                assertEquals("cloud_plus", webhooks.entitlements.resolve(accountId).planId)

                webhooks.stripe.setStatus(subscription.id, "canceled")
                webhooks.deliver(subscriptionEvent("evt_2", "customer.subscription.deleted", subscription.id, statusInEvent = "canceled"))

                val entitlement = webhooks.entitlements.resolve(accountId)
                assertEquals("cloud", entitlement.planId)
                assertEquals(true, entitlement.includedWithBeta)
            }
        }

    @Test
    fun `an event delivered twice is applied once`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)
                val subscription = webhooks.paidCheckout()
                val event = subscriptionEvent("evt_repeat", "customer.subscription.created", subscription.id)

                webhooks.deliver(event)
                val reads = webhooks.stripe.subscriptionReads
                val again = webhooks.deliver(event)

                assertIs<StripeWebhookHandler.Outcome.Handled>(again)
                assertEquals(reads, webhooks.stripe.subscriptionReads, "the repeat is answered without asking Stripe again")
            }
        }

    @Test
    fun `an event about something else is accepted and changes nothing`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)

                val outcome = webhooks.deliver("""{"id":"evt_other","type":"customer.created","data":{"object":{"id":"cus_9"}}}""")

                assertIs<StripeWebhookHandler.Outcome.Handled>(outcome)
                assertEquals(0, webhooks.stripe.subscriptionReads)
            }
        }

    @Test
    fun `when Stripe cannot be read the event is left for Stripe to send again`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)
                val subscription = webhooks.paidCheckout()
                val event = subscriptionEvent("evt_retry", "customer.subscription.created", subscription.id)
                webhooks.stripe.unreachable = true

                assertFailsWith<StripeException> { webhooks.deliver(event) }
                webhooks.stripe.unreachable = false
                webhooks.deliver(event)

                assertEquals("cloud_plus", webhooks.entitlements.resolve(accountId).planId)
            }
        }

    @Test
    fun `a subscription that belongs to no account is left alone`() =
        runTest {
            withBillingDatabase { database ->
                val webhooks = Webhooks(database)
                webhooks.stripe.subscriptions["sub_stray"] =
                    StripeSubscription(
                        id = "sub_stray",
                        customerId = "cus_unknown",
                        status = "active",
                        cancelAtEpochSeconds = null,
                        accountId = null,
                        items = emptyList(),
                    )

                val outcome = webhooks.deliver(subscriptionEvent("evt_stray", "customer.subscription.created", "sub_stray"))

                assertIs<StripeWebhookHandler.Outcome.Handled>(outcome)
                assertNull(webhooks.store.subscription(accountId))
            }
        }
}
