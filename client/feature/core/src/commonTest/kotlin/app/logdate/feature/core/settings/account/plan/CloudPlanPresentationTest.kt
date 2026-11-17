package app.logdate.feature.core.settings.account.plan

import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.PlanOption
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudPlanPresentationTest {
    private fun state(
        subscription: CloudSubscription,
        plans: List<PlanOption> = CloudPlanFixtures.plans(onSale = subscription.purchasesAvailable),
        interval: BillingInterval = subscription.interval ?: BillingInterval.MONTH,
    ) = CloudPlanUiState.Ready(subscription = subscription, plans = plans, interval = interval)

    private fun CloudPlanUiState.Ready.actions(): Map<String, PlanAction> = plans.associate { it.id to actionFor(it) }

    @Test
    fun `during the beta nothing is offered for sale`() {
        val actions = state(CloudPlanFixtures.betaOnBase(purchasesAvailable = false)).actions()

        assertEquals(mapOf("cloud" to PlanAction.None, "cloud_plus" to PlanAction.None), actions)
    }

    @Test
    fun `a beta member is offered Plus and never the plan they have at no charge`() {
        val actions = state(CloudPlanFixtures.betaOnBase(purchasesAvailable = true)).actions()

        assertEquals(mapOf("cloud" to PlanAction.None, "cloud_plus" to PlanAction.Buy), actions)
    }

    @Test
    fun `someone with no plan can buy either`() {
        val actions = state(CloudPlanFixtures.noPlan()).actions()

        assertEquals(mapOf("cloud" to PlanAction.Buy, "cloud_plus" to PlanAction.Buy), actions)
    }

    @Test
    fun `someone paying for the base plan can switch to Plus`() {
        val actions = state(CloudPlanFixtures.payingForBase()).actions()

        assertEquals(mapOf("cloud" to PlanAction.None, "cloud_plus" to PlanAction.Switch), actions)
    }

    @Test
    fun `a beta member paying for Plus goes back to the free plan by cancelling and not by switching`() {
        val actions = state(CloudPlanFixtures.payingForPlus()).actions()

        assertEquals(mapOf("cloud" to PlanAction.None, "cloud_plus" to PlanAction.None), actions)
    }

    @Test
    fun `choosing the other interval offers to bill the current plan that way`() {
        val actions = state(CloudPlanFixtures.payingForPlus(BillingInterval.MONTH), interval = BillingInterval.YEAR).actions()

        assertEquals(PlanAction.BillAt(BillingInterval.YEAR), actions["cloud_plus"])
    }

    @Test
    fun `a plan that is ending offers no change until it is kept`() {
        val ending =
            CloudPlanFixtures.payingForBase().copy(renewsAtEpochSeconds = null, endsAtEpochSeconds = CloudPlanFixtures.RENEWAL)

        val actions = state(ending, interval = BillingInterval.YEAR).actions()

        assertEquals(mapOf("cloud" to PlanAction.None, "cloud_plus" to PlanAction.None), actions)
    }

    @Test
    fun `a plan with no price for the chosen interval cannot be bought at it`() {
        val monthlyOnly =
            CloudPlanFixtures.plans().map { plan ->
                plan.copy(
                    prices =
                        plan.prices.filter {
                            it.interval ==
                                BillingInterval.MONTH
                        },
                )
            }

        val actions = state(CloudPlanFixtures.noPlan(), plans = monthlyOnly, interval = BillingInterval.YEAR).actions()

        assertEquals(mapOf("cloud" to PlanAction.None, "cloud_plus" to PlanAction.None), actions)
        assertEquals(false, monthlyOnly.offerBothIntervals())
        assertEquals(true, CloudPlanFixtures.plans().offerBothIntervals())
    }

    @Test
    fun `a plan the server is not selling has no button even when purchases are available`() {
        val plusNotOnSale = CloudPlanFixtures.plans().map { if (it.id == "cloud_plus") it.copy(onSale = false) else it }

        val actions = state(CloudPlanFixtures.noPlan(), plans = plusNotOnSale).actions()

        assertEquals(mapOf("cloud" to PlanAction.Buy, "cloud_plus" to PlanAction.None), actions)
    }

    @Test
    fun `money is written the way the amount reads`() {
        assertEquals("$3.99", formatMoney(399, "usd"))
        assertEquals("$1", formatMoney(100, "usd"))
        assertEquals("$79.99", formatMoney(7999, "USD"))
        assertEquals("$0.05", formatMoney(5, "usd"))
        assertEquals("€7.99", formatMoney(799, "eur"))
        assertEquals("¥500", formatMoney(500, "jpy"))
        assertEquals("12.50 CHF", formatMoney(1250, "chf"))
    }
}
