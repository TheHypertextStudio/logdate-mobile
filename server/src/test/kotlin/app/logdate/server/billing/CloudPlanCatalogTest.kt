package app.logdate.server.billing

import app.logdate.server.entitlements.StoredPlanCatalogService
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.ExtraStorageOffer
import app.logdate.shared.model.PlanPrice
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The public plan catalog: storage from the database, prices from Stripe, and when a plan is on sale. */
class CloudPlanCatalogTest {
    @Test
    fun `the catalog lists both plans with their storage and what Stripe charges`() =
        runTest {
            withBillingDatabase { database ->
                val catalog = StoredPlanCatalogService(database, StripePlanPrices(FakeStripe()), CloudLaunch(launched = true))

                val (base, plus) = catalog.plans()

                assertEquals("cloud", base.id)
                assertEquals(50 * GIB, base.storageBytesLimit)
                assertEquals(
                    listOf(PlanPrice(BillingInterval.MONTH, 399, "usd"), PlanPrice(BillingInterval.YEAR, 3999, "usd")),
                    base.prices,
                )
                assertEquals(null, base.extraStorage)
                assertTrue(base.isPurchasable)

                assertEquals("cloud_plus", plus.id)
                assertEquals(250 * GIB, plus.storageBytesLimit)
                assertEquals(
                    listOf(PlanPrice(BillingInterval.MONTH, 799, "usd"), PlanPrice(BillingInterval.YEAR, 7999, "usd")),
                    plus.prices,
                )
                assertEquals(
                    ExtraStorageOffer(stepBytes = 50 * GIB, monthlyPrice = PlanPrice(BillingInterval.MONTH, 100, "usd")),
                    plus.extraStorage,
                )
                assertTrue(plus.isPurchasable)
            }
        }

    @Test
    fun `before launch Plus is on sale and the base plan is listed with its price but not sold`() =
        runTest {
            withBillingDatabase { database ->
                val catalog = StoredPlanCatalogService(database, StripePlanPrices(FakeStripe()), CloudLaunch(launched = false))

                val (base, plus) = catalog.plans()

                assertEquals(399L, base.prices.first().amountMinor)
                assertFalse(base.onSale)
                assertFalse(base.isPurchasable)
                assertTrue(plus.isPurchasable)
            }
        }

    @Test
    fun `without Stripe the plans carry their storage and nothing to buy`() =
        runTest {
            withBillingDatabase { database ->
                val catalog = StoredPlanCatalogService(database, prices = null, launch = CloudLaunch(launched = true))

                val plans = catalog.plans()

                assertEquals(listOf(50 * GIB, 250 * GIB), plans.map { it.storageBytesLimit })
                assertEquals(ExtraStorageOffer(stepBytes = 50 * GIB, monthlyPrice = null), plans.last().extraStorage)
                assertTrue(plans.all { it.prices.isEmpty() && !it.isPurchasable })
            }
        }

    @Test
    fun `a plan Stripe has no price for is not on sale`() =
        runTest {
            withBillingDatabase { database ->
                val stripe = FakeStripe().apply { priceList.removeAll { it.lookupKey?.startsWith("logdate_cloud_plus") == true } }
                val catalog = StoredPlanCatalogService(database, StripePlanPrices(stripe), CloudLaunch(launched = true))

                val (base, plus) = catalog.plans()

                assertTrue(base.isPurchasable)
                assertFalse(plus.onSale)
            }
        }

    @Test
    fun `when Stripe cannot be reached the last prices read keep being served`() =
        runTest {
            withBillingDatabase { database ->
                val stripe = FakeStripe()
                val clock = SettableClock()
                val catalog =
                    StoredPlanCatalogService(database, StripePlanPrices(stripe, clock, ttl = 10.minutes), CloudLaunch(launched = true))
                catalog.plans()

                stripe.unreachable = true
                clock.advance(11.minutes)
                val plans = catalog.plans()

                assertEquals(listOf(399L, 799L), plans.map { it.prices.first().amountMinor })
            }
        }

    @Test
    fun `when Stripe has never been reached the plans have no price and cannot be bought`() =
        runTest {
            withBillingDatabase { database ->
                val stripe = FakeStripe().apply { unreachable = true }
                val catalog = StoredPlanCatalogService(database, StripePlanPrices(stripe), CloudLaunch(launched = true))

                assertTrue(catalog.plans().none { it.isPurchasable })
            }
        }

    @Test
    fun `lookup keys name the plan they price`() {
        assertEquals("cloud", StripeCatalogNames.planIdOf("logdate_cloud_monthly"))
        assertEquals("cloud_plus", StripeCatalogNames.planIdOf("logdate_cloud_plus_yearly"))
        assertEquals(null, StripeCatalogNames.planIdOf("logdate_cloud_plus_extra_storage"))
        assertEquals(null, StripeCatalogNames.planIdOf("someone_elses_monthly"))
        assertEquals(null, StripeCatalogNames.planIdOf(null))
    }
}
