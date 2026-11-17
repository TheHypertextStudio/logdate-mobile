package app.logdate.client.domain.account

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.datastore.UserSession
import app.logdate.client.networking.CloudBillingApiClientContract
import app.logdate.client.networking.CloudBillingException
import app.logdate.client.networking.PlanCatalogClient
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.BillingPage
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.EntitlementStatusWire
import app.logdate.shared.model.EntitlementTierWire
import app.logdate.shared.model.PlanOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CloudPlanBillingTest {
    private val base =
        PlanOption(
            id = "cloud",
            name = "LogDate Cloud",
            tier = EntitlementTierWire.STANDARD,
            storageBytesLimit = 50 * GIB,
            backupCountLimit = null,
        )
    private val subscription =
        CloudSubscription(
            planId = "cloud",
            status = EntitlementStatusWire.ACTIVE,
            includedWithBeta = true,
            betaMember = true,
            storageUsedBytes = 12 * GIB,
            storageIncludedBytes = 50 * GIB,
            purchasesAvailable = false,
            paymentDetailsAvailable = false,
        )
    private val plus = CloudPlanSelection("cloud_plus", BillingInterval.MONTH)

    private fun billing(
        api: FakeBillingApi = FakeBillingApi(subscription),
        session: UserSession? = UserSession("token-1", "refresh-1", "account-1"),
        plans: Result<List<PlanOption>> = Result.success(listOf(base)),
        refreshedToken: String? = null,
    ): CloudPlanBilling {
        val sessions = FakeSessionStorage(session)
        return ServerCloudPlanBilling(
            sessionStorage = sessions,
            billing = api,
            catalog = FakePlanCatalog(plans),
            refreshSession = {
                refreshedToken?.also { sessions.current = UserSession(it, "refresh-2", "account-1") } != null
            },
        )
    }

    @Test
    fun `the overview is the account's subscription with the plans its server offers`() =
        runTest {
            val result = billing().overview()

            assertEquals(CloudPlanResult.Done(CloudPlanOverview(subscription, listOf(base))), result)
        }

    @Test
    fun `the subscription still shows when the plan catalog cannot be read`() =
        runTest {
            val result = billing(plans = Result.failure(IllegalStateException("offline"))).overview()

            assertEquals(CloudPlanResult.Done(CloudPlanOverview(subscription, emptyList())), result)
        }

    @Test
    fun `nobody signed in is reported without calling the server`() =
        runTest {
            val api = FakeBillingApi(subscription)

            val result = billing(api = api, session = null).overview()

            assertEquals(CloudPlanResult.Failed(CloudPlanProblem.SignedOut), result)
            assertEquals(emptyList(), api.tokens)
        }

    @Test
    fun `an expired token is renewed once and the call repeated`() =
        runTest {
            val api = FakeBillingApi(subscription, rejectedTokens = setOf("token-1"))

            val result = billing(api = api, refreshedToken = "token-2").overview()

            assertIs<CloudPlanResult.Done<CloudPlanOverview>>(result)
            assertEquals(listOf("token-1", "token-2"), api.tokens)
        }

    @Test
    fun `a session that cannot be renewed reads as signed out`() =
        runTest {
            val api = FakeBillingApi(subscription, rejectedTokens = setOf("token-1"))

            val result = billing(api = api, refreshedToken = null).overview()

            assertEquals(CloudPlanResult.Failed(CloudPlanProblem.SignedOut), result)
            assertEquals(listOf("token-1"), api.tokens)
        }

    @Test
    fun `starting a checkout returns the page to open`() =
        runTest {
            val api = FakeBillingApi(subscription)

            val result = billing(api = api).startCheckout(plus)

            assertEquals(CloudPlanResult.Done("https://checkout.example/1"), result)
            assertEquals(listOf(plus), api.checkouts)
        }

    @Test
    fun `each refusal becomes a problem the app can word`() =
        runTest {
            fun refused(
                code: String,
                details: Map<String, String> = emptyMap(),
            ) = FakeBillingApi(subscription, failure = CloudBillingException(code, status = 409, details = details))

            val cases =
                mapOf(
                    refused("PLANS_NOT_ON_SALE") to CloudPlanProblem.NotOnSale,
                    refused("INCLUDED_WITH_BETA") to CloudPlanProblem.IncludedWithBeta,
                    refused("ALREADY_SUBSCRIBED") to CloudPlanProblem.OutOfDate,
                    refused("NO_SUBSCRIPTION") to CloudPlanProblem.OutOfDate,
                    refused("BILLING_PROVIDER_UNAVAILABLE") to CloudPlanProblem.PaymentProviderUnavailable,
                    refused("BILLING_UNAVAILABLE") to CloudPlanProblem.NoBilling,
                    refused(CloudBillingException.NETWORK) to CloudPlanProblem.Offline,
                    refused("SOMETHING_NEW") to CloudPlanProblem.Unknown,
                    refused("STORAGE_EXCEEDS_PLAN", mapOf("usedBytes" to "${70 * GIB}", "includedBytes" to "${50 * GIB}")) to
                        CloudPlanProblem.StorageExceedsPlan(usedBytes = 70 * GIB, includedBytes = 50 * GIB),
                    refused("STORAGE_EXCEEDS_PLAN") to CloudPlanProblem.Unknown,
                )

            cases.forEach { (api, problem) ->
                assertEquals(CloudPlanResult.Failed(problem), billing(api = api).changePlan(plus))
            }
        }

    private class FakeBillingApi(
        private val subscription: CloudSubscription,
        private val failure: CloudBillingException? = null,
        private val rejectedTokens: Set<String> = emptySet(),
    ) : CloudBillingApiClientContract {
        val tokens = mutableListOf<String>()
        val checkouts = mutableListOf<CloudPlanSelection>()

        private fun <T> answer(
            accessToken: String,
            value: T,
        ): Result<T> {
            tokens += accessToken
            if (accessToken in rejectedTokens) return Result.failure(CloudBillingException("UNAUTHORIZED", status = 401))
            return failure?.let { Result.failure(it) } ?: Result.success(value)
        }

        override suspend fun subscription(accessToken: String) = answer(accessToken, subscription)

        override suspend fun refreshSubscription(accessToken: String) = answer(accessToken, subscription)

        override suspend fun startCheckout(
            accessToken: String,
            selection: CloudPlanSelection,
        ) = answer(accessToken, BillingPage("https://checkout.example/1")).also { checkouts += selection }

        override suspend fun changePlan(
            accessToken: String,
            selection: CloudPlanSelection,
        ) = answer(accessToken, subscription)

        override suspend fun cancel(accessToken: String) = answer(accessToken, subscription)

        override suspend fun keep(accessToken: String) = answer(accessToken, subscription)

        override suspend fun paymentDetails(accessToken: String) = answer(accessToken, BillingPage("https://billing.example/1"))
    }

    private class FakePlanCatalog(
        private val plans: Result<List<PlanOption>>,
    ) : PlanCatalogClient {
        override suspend fun fetchPlans(): Result<List<PlanOption>> = plans

        override suspend fun fetchPlans(serverOrigin: String): Result<List<PlanOption>> = plans
    }

    private class FakeSessionStorage(
        var current: UserSession?,
    ) : SessionStorage {
        override fun getSession(): UserSession? = current

        override fun getSessionFlow(): StateFlow<UserSession?> = MutableStateFlow(current)

        override suspend fun hasValidSession(): Boolean = current != null

        override suspend fun saveSession(session: UserSession) {
            current = session
        }

        override suspend fun clearSession() {
            current = null
        }
    }

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
