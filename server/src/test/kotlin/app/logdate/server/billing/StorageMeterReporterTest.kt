package app.logdate.server.billing

import app.logdate.server.entitlements.StoredEntitlementService
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudPlanSelection
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** What the storage meter tells Stripe, and when. */
class StorageMeterReporterTest {
    private class Metering(
        database: Database,
    ) {
        val stripe = FakeStripe()
        val usage = SettableUsage()
        val clock = SettableClock()
        val store = BillingStore(database, clock)
        private val prices = StripePlanPrices(stripe, clock)
        val service =
            CloudBillingService(
                store = store,
                entitlements = StoredEntitlementService(database),
                usage = usage,
                launch = CloudLaunch(launched = true),
                stripe = stripe,
                prices = prices,
                sync = SubscriptionSync(store, stripe, prices),
                returnUrl = "https://cloud.logdate.test/return",
            )
        val reporter = StorageMeterReporter(store, stripe, usage, clock, heartbeat = 6.hours)

        suspend fun subscribe(
            planId: String,
            usedBytes: Long,
        ): UUID {
            val accountId = UUID.randomUUID()
            service.startCheckout(accountId, CloudPlanSelection(planId, BillingInterval.MONTH))
            stripe.pay()
            service.refresh(accountId)
            usage[accountId] = usedBytes
            return accountId
        }

        fun reportedValues(): List<Long> = stripe.meterEvents.map { it.value }
    }

    @Test
    fun `Plus past its included storage is reported in whole 50 GB steps`() =
        runTest {
            withBillingDatabase { database ->
                val metering = Metering(database)
                metering.subscribe("cloud_plus", usedBytes = 250 * GIB + 1)
                metering.subscribe("cloud_plus", usedBytes = 381 * GIB)

                assertEquals(2, metering.reporter.reportDue())

                assertEquals(listOf(1L, 3L), metering.reportedValues())
                val event = metering.stripe.meterEvents.first()
                assertEquals("logdate_cloud_extra_storage", event.eventName)
                assertTrue(event.customerId.startsWith("cus_"))
            }
        }

    @Test
    fun `Plus within its included storage is reported as zero, so an earlier value does not stand`() =
        runTest {
            withBillingDatabase { database ->
                val metering = Metering(database)
                val accountId = metering.subscribe("cloud_plus", usedBytes = 320 * GIB)
                metering.reporter.reportDue()

                metering.usage[accountId] = 100 * GIB
                metering.clock.advance(15.minutes)
                metering.reporter.reportDue()

                assertEquals(listOf(2L, 0L), metering.reportedValues(), "freeing the space stops the charge")
            }
        }

    @Test
    fun `an unchanged amount is reported again only once the heartbeat has passed`() =
        runTest {
            withBillingDatabase { database ->
                val metering = Metering(database)
                metering.subscribe("cloud_plus", usedBytes = 260 * GIB)

                metering.reporter.reportDue()
                metering.clock.advance(15.minutes)
                assertEquals(0, metering.reporter.reportDue())
                metering.clock.advance(6.hours)
                assertEquals(1, metering.reporter.reportDue())

                assertEquals(listOf(1L, 1L), metering.reportedValues())
                assertEquals(
                    2,
                    metering.stripe.meterEvents
                        .map { it.identifier }
                        .toSet()
                        .size,
                    "each report has its own identifier",
                )
            }
        }

    @Test
    fun `the base plan is never metered`() =
        runTest {
            withBillingDatabase { database ->
                val metering = Metering(database)
                metering.subscribe("cloud", usedBytes = 49 * GIB)

                assertEquals(0, metering.reporter.reportDue())
                assertTrue(metering.stripe.meterEvents.isEmpty())
            }
        }

    @Test
    fun `a subscription whose payment is failing is still reported`() =
        runTest {
            withBillingDatabase { database ->
                val metering = Metering(database)
                val accountId = metering.subscribe("cloud_plus", usedBytes = 300 * GIB)
                metering.stripe.setStatus(
                    metering.stripe.subscriptions.keys
                        .single(),
                    "past_due",
                )
                metering.service.refresh(accountId)

                metering.reporter.reportDue()

                assertEquals(listOf(1L), metering.reportedValues())
            }
        }

    @Test
    fun `a subscription that has ended is no longer reported`() =
        runTest {
            withBillingDatabase { database ->
                val metering = Metering(database)
                val accountId = metering.subscribe("cloud_plus", usedBytes = 300 * GIB)
                metering.stripe.setStatus(
                    metering.stripe.subscriptions.keys
                        .single(),
                    "canceled",
                )
                metering.service.refresh(accountId)

                assertEquals(0, metering.reporter.reportDue())
            }
        }

    @Test
    fun `one account Stripe refuses does not stop the others, and is tried again next time`() =
        runTest {
            withBillingDatabase { database ->
                val metering = Metering(database)
                metering.subscribe("cloud_plus", usedBytes = 300 * GIB)
                metering.subscribe("cloud_plus", usedBytes = 360 * GIB)
                val refused =
                    metering.stripe.customers.keys
                        .first()
                metering.stripe.refusedMeterCustomers += refused

                assertEquals(1, metering.reporter.reportDue())
                metering.stripe.refusedMeterCustomers.clear()
                metering.clock.advance(15.minutes)
                assertEquals(1, metering.reporter.reportDue(), "the refused account is reported on the next pass")

                assertEquals(setOf(1L, 3L), metering.reportedValues().toSet())
            }
        }
}
