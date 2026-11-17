package app.logdate.feature.core.settings.account.plan

import app.logdate.client.domain.account.CloudPlanBilling
import app.logdate.client.domain.account.CloudPlanOverview
import app.logdate.client.domain.account.CloudPlanProblem
import app.logdate.client.domain.account.CloudPlanResult
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CloudPlanViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun ready(viewModel: CloudPlanViewModel) = assertIs<CloudPlanUiState.Ready>(viewModel.state.value)

    @Test
    fun `the screen opens on the account's plan with monthly prices`() =
        runTest(dispatcher) {
            val viewModel = CloudPlanViewModel(FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase()))
            assertEquals(CloudPlanUiState.Loading, viewModel.state.value)
            advanceUntilIdle()

            val state = ready(viewModel)
            assertEquals("cloud", state.subscription.planId)
            assertEquals(BillingInterval.MONTH, state.interval)
            assertFalse(state.isPaying)
            assertEquals(2, state.plans.size)
        }

    @Test
    fun `a yearly subscriber sees yearly prices first`() =
        runTest(dispatcher) {
            val viewModel = CloudPlanViewModel(FakeCloudPlanBilling(CloudPlanFixtures.payingForPlus(BillingInterval.YEAR)))
            advanceUntilIdle()

            assertEquals(BillingInterval.YEAR, ready(viewModel).interval)
            assertTrue(ready(viewModel).isPaying)
        }

    @Test
    fun `a plan that cannot be read says why and can be read again`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase()).apply { overviewProblem = CloudPlanProblem.Offline }
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()
            assertEquals(CloudPlanUiState.Unavailable(CloudPlanProblem.Offline), viewModel.state.value)

            billing.overviewProblem = null
            viewModel.load()
            advanceUntilIdle()

            ready(viewModel)
        }

    @Test
    fun `buying opens the checkout in the browser at the chosen interval and waits for the payment`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase(purchasesAvailable = true))
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            viewModel.selectInterval(BillingInterval.YEAR)
            viewModel.buy("cloud_plus")
            advanceUntilIdle()

            assertEquals("https://checkout.example/cloud_plus-YEAR", viewModel.pages.first())
            assertEquals(listOf(CloudPlanSelection("cloud_plus", BillingInterval.YEAR)), billing.checkouts)
            assertTrue(ready(viewModel).awaitingPayment)
            assertEquals("cloud", ready(viewModel).subscription.planId, "opening a checkout changes no plan")
        }

    @Test
    fun `coming back from the browser reads the plan from the server`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase(purchasesAvailable = true))
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()
            viewModel.buy("cloud_plus")
            advanceUntilIdle()

            billing.current = CloudPlanFixtures.payingForPlus()
            viewModel.checkPayment()
            advanceUntilIdle()

            assertEquals(1, billing.refreshes)
            assertEquals("cloud_plus", ready(viewModel).subscription.planId)
            assertFalse(ready(viewModel).awaitingPayment)
        }

    @Test
    fun `coming back without paying leaves the plan as it was and stops waiting`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase(purchasesAvailable = true))
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()
            viewModel.buy("cloud_plus")
            advanceUntilIdle()

            viewModel.checkPayment()
            advanceUntilIdle()

            assertEquals("cloud", ready(viewModel).subscription.planId)
            assertFalse(ready(viewModel).awaitingPayment)
        }

    @Test
    fun `returning to the screen with no checkout open asks the server nothing`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase())
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            viewModel.checkPayment()
            advanceUntilIdle()

            assertEquals(0, billing.refreshes)
        }

    @Test
    fun `a refused checkout shows the reason and opens nothing`() =
        runTest(dispatcher) {
            val billing =
                FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase(purchasesAvailable = true)).apply {
                    actionProblem =
                        CloudPlanProblem.NotOnSale
                }
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            viewModel.buy("cloud_plus")
            advanceUntilIdle()

            assertEquals(CloudPlanProblem.NotOnSale, ready(viewModel).problem)
            assertFalse(ready(viewModel).awaitingPayment)
            viewModel.dismissProblem()
            assertNull(ready(viewModel).problem)
        }

    @Test
    fun `changing plan shows the new plan the server reports`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.payingForPlus())
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            viewModel.change("cloud")
            assertTrue(ready(viewModel).working)
            advanceUntilIdle()

            assertEquals(listOf(CloudPlanSelection("cloud", BillingInterval.MONTH)), billing.changes)
            assertEquals("cloud", ready(viewModel).subscription.planId)
            assertFalse(ready(viewModel).working)
        }

    @Test
    fun `a plan too small for what is stored is refused with the numbers and nothing changes`() =
        runTest(dispatcher) {
            val tooSmall =
                CloudPlanProblem.StorageExceedsPlan(
                    usedBytes = 70 * CloudPlanFixtures.GIB,
                    includedBytes =
                        50 * CloudPlanFixtures.GIB,
                )
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.payingForPlus()).apply { actionProblem = tooSmall }
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            viewModel.change("cloud")
            advanceUntilIdle()

            assertEquals(tooSmall, ready(viewModel).problem)
            assertEquals("cloud_plus", ready(viewModel).subscription.planId)
        }

    @Test
    fun `cancelling keeps the plan with an end date and keeping it clears the date`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.payingForPlus())
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            viewModel.cancel()
            advanceUntilIdle()
            assertTrue(ready(viewModel).isEnding)
            assertEquals("cloud_plus", ready(viewModel).subscription.planId)

            viewModel.keep()
            advanceUntilIdle()
            assertFalse(ready(viewModel).isEnding)
        }

    @Test
    fun `payment details open in the browser`() =
        runTest(dispatcher) {
            val viewModel = CloudPlanViewModel(FakeCloudPlanBilling(CloudPlanFixtures.payingForPlus()))
            advanceUntilIdle()

            viewModel.openPaymentDetails()
            advanceUntilIdle()

            assertEquals("https://billing.example/details", viewModel.pages.first())
        }

    @Test
    fun `a second action waits for the first`() =
        runTest(dispatcher) {
            val billing = FakeCloudPlanBilling(CloudPlanFixtures.payingForPlus())
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            viewModel.cancel()
            viewModel.change("cloud")
            advanceUntilIdle()

            assertEquals(1, billing.cancels)
            assertTrue(billing.changes.isEmpty())
        }

    @Test
    fun `when the server says the app's view is stale the plan is read again`() =
        runTest(dispatcher) {
            val billing =
                FakeCloudPlanBilling(CloudPlanFixtures.betaOnBase(purchasesAvailable = true)).apply {
                    actionProblem =
                        CloudPlanProblem.OutOfDate
                }
            val viewModel = CloudPlanViewModel(billing)
            advanceUntilIdle()

            billing.current = CloudPlanFixtures.payingForPlus()
            viewModel.buy("cloud_plus")
            advanceUntilIdle()

            assertEquals(CloudPlanProblem.OutOfDate, ready(viewModel).problem)
            assertEquals("cloud_plus", ready(viewModel).subscription.planId)
        }
}

/** A [CloudPlanBilling] that answers from [current] and records what was asked of it. */
internal class FakeCloudPlanBilling(
    var current: CloudSubscription,
) : CloudPlanBilling {
    var overviewProblem: CloudPlanProblem? = null
    var actionProblem: CloudPlanProblem? = null
    val checkouts = mutableListOf<CloudPlanSelection>()
    val changes = mutableListOf<CloudPlanSelection>()
    var refreshes = 0
    var cancels = 0

    private fun overviewNow() =
        CloudPlanResult.Done(CloudPlanOverview(current, CloudPlanFixtures.plans(onSale = current.purchasesAvailable)))

    private fun <T> acting(done: () -> CloudPlanResult<T>): CloudPlanResult<T> = actionProblem?.let { CloudPlanResult.Failed(it) } ?: done()

    override suspend fun overview(): CloudPlanResult<CloudPlanOverview> =
        overviewProblem?.let { CloudPlanResult.Failed(it) } ?: overviewNow()

    override suspend fun refresh(): CloudPlanResult<CloudPlanOverview> {
        refreshes++
        return overviewNow()
    }

    override suspend fun startCheckout(selection: CloudPlanSelection): CloudPlanResult<String> =
        acting {
            checkouts += selection
            CloudPlanResult.Done("https://checkout.example/${selection.planId}-${selection.interval}")
        }

    override suspend fun changePlan(selection: CloudPlanSelection): CloudPlanResult<CloudPlanOverview> =
        acting {
            changes += selection
            current = current.copy(planId = selection.planId, interval = selection.interval)
            overviewNow()
        }

    override suspend fun cancel(): CloudPlanResult<CloudPlanOverview> =
        acting {
            cancels++
            current = current.copy(endsAtEpochSeconds = current.renewsAtEpochSeconds, renewsAtEpochSeconds = null)
            overviewNow()
        }

    override suspend fun keep(): CloudPlanResult<CloudPlanOverview> =
        acting {
            current = current.copy(renewsAtEpochSeconds = current.endsAtEpochSeconds, endsAtEpochSeconds = null)
            overviewNow()
        }

    override suspend fun paymentDetails(): CloudPlanResult<String> = acting { CloudPlanResult.Done("https://billing.example/details") }
}
