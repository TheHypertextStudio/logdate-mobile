package app.logdate.client.networking

import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.EntitlementStatusWire
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CloudBillingApiClientTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String,
    ) = CloudBillingApiClient(
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        TestConfigRepository(),
    )

    private val plusSubscription =
        """
        {"planId":"cloud_plus","status":"ACTIVE","includedWithBeta":false,"betaMember":true,"interval":"YEAR",
         "renewsAtEpochSeconds":1802592000,"storageUsedBytes":301989888000,"storageIncludedBytes":268435456000,
         "extraStorageSteps":1,"purchasesAvailable":true,"paymentDetailsAvailable":true,"somethingNewer":"ignored"}
        """.trimIndent()

    @Test
    fun `the subscription is read with the account's token`() =
        runTest {
            val subscription = client(body = plusSubscription).subscription("token-1").getOrThrow()

            val request = requests.single()
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("http://localhost/api/v1/billing/subscription", request.url.toString())
            assertEquals("Bearer token-1", request.headers[HttpHeaders.Authorization])
            assertEquals("cloud_plus", subscription.planId)
            assertEquals(EntitlementStatusWire.ACTIVE, subscription.status)
            assertEquals(BillingInterval.YEAR, subscription.interval)
            assertEquals(1, subscription.extraStorageSteps)
            assertTrue(subscription.purchasesAvailable)
        }

    @Test
    fun `starting a checkout sends the plan and interval and returns the page to open`() =
        runTest {
            val page =
                client(body = """{"url":"https://checkout.stripe.com/c/pay/cs_1"}""")
                    .startCheckout("token-1", CloudPlanSelection("cloud_plus", BillingInterval.YEAR))
                    .getOrThrow()

            val request = requests.single()
            assertEquals("https://checkout.stripe.com/c/pay/cs_1", page.url)
            assertEquals("http://localhost/api/v1/billing/checkout", request.url.toString())
            assertEquals("""{"planId":"cloud_plus","interval":"YEAR"}""", (request.body as TextContent).text)
        }

    @Test
    fun `each change goes to its own endpoint`() =
        runTest {
            val client = client(body = plusSubscription)

            client.refreshSubscription("t")
            client.changePlan("t", CloudPlanSelection("cloud", BillingInterval.MONTH))
            client.cancel("t")
            client.keep("t")

            assertEquals(
                listOf(
                    "/api/v1/billing/subscription/refresh",
                    "/api/v1/billing/subscription/plan",
                    "/api/v1/billing/subscription/cancel",
                    "/api/v1/billing/subscription/keep",
                ),
                requests.map { it.url.encodedPath },
            )
            assertTrue(requests.all { it.method == HttpMethod.Post })
        }

    @Test
    fun `a refusal carries the server's code and the numbers that go with it`() =
        runTest {
            val refusal =
                client(
                    status = HttpStatusCode.Conflict,
                    body =
                        """{"code":"STORAGE_EXCEEDS_PLAN","message":"This account stores more than that plan holds.",""" +
                            """"details":{"usedBytes":"75161927680","includedBytes":"53687091200"}}""",
                ).changePlan("t", CloudPlanSelection("cloud", BillingInterval.MONTH)).exceptionOrNull()

            val failure = assertIs<CloudBillingException>(refusal)
            assertEquals("STORAGE_EXCEEDS_PLAN", failure.code)
            assertEquals(409, failure.status)
            assertEquals(mapOf("usedBytes" to "75161927680", "includedBytes" to "53687091200"), failure.details)
        }

    @Test
    fun `a server with billing switched off reads as having none`() =
        runTest {
            val switchedOff =
                client(HttpStatusCode.NotFound, """{"code":"BILLING_UNAVAILABLE","message":"This server has no billing."}""")
                    .subscription("t")
                    .exceptionOrNull()
            val olderServer = client(HttpStatusCode.NotFound, "").subscription("t").exceptionOrNull()

            assertTrue(assertIs<CloudBillingException>(switchedOff).billingUnavailable)
            assertTrue(assertIs<CloudBillingException>(olderServer).billingUnavailable)
        }

    @Test
    fun `a failure with no readable body is unknown and not mistaken for a refusal`() =
        runTest {
            val failure = client(HttpStatusCode.InternalServerError, "<html>oops</html>").paymentDetails("t").exceptionOrNull()

            assertEquals(CloudBillingException.UNKNOWN, assertIs<CloudBillingException>(failure).code)
        }

    @Test
    fun `not reaching the server is a network failure`() =
        runTest {
            val client =
                CloudBillingApiClient(HttpClient(MockEngine { throw IOException("offline") }), TestConfigRepository())

            val failure = client.subscription("t").exceptionOrNull()

            assertEquals(CloudBillingException.NETWORK, assertIs<CloudBillingException>(failure).code)
        }
}
