package app.logdate.server.billing

import app.logdate.server.auth.JwtTokenService
import app.logdate.server.entitlements.StoredEntitlementService
import app.logdate.shared.model.BillingPage
import app.logdate.shared.model.CloudSubscription
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation

/** The billing endpoints as a client meets them: who may call, what comes back, and how refusals read. */
class CloudBillingRoutesTest {
    private val accountId = UUID.fromString("44444444-4444-4444-4444-444444444444")
    private val tokens = JwtTokenService("billing-routes-test-secret-key-32-chars")
    private val json = Json { ignoreUnknownKeys = true }
    private val webhookSecret = "whsec_routes"

    private fun billingApp(
        launched: Boolean = true,
        test: suspend ApplicationTestBuilder.(FakeStripe, SettableUsage) -> Unit,
    ) = runBlocking {
        withBillingDatabase { database -> testApplication { test(install(database, launched), usage) } }
    }

    private val usage = SettableUsage()

    private fun ApplicationTestBuilder.install(
        database: Database,
        launched: Boolean,
    ): FakeStripe {
        val stripe = FakeStripe()
        val store = BillingStore(database)
        val prices = StripePlanPrices(stripe)
        val sync = SubscriptionSync(store, stripe, prices)
        val service =
            CloudBillingService(
                store = store,
                entitlements = StoredEntitlementService(database),
                usage = usage,
                launch = CloudLaunch(launched = launched),
                stripe = stripe,
                prices = prices,
                sync = sync,
                returnUrl = "https://cloud.logdate.test/api/v1/billing/return",
            )
        val webhooks = StripeWebhookHandler(StripeSignatureVerifier(webhookSecret), stripe, sync, store)
        application {
            install(ServerContentNegotiation) { json(json) }
            routing {
                route("/api/v1") {
                    cloudBillingRoutes(tokens, service)
                    stripeWebhookRoutes(webhooks)
                }
            }
        }
        return stripe
    }

    private fun bearer() = "Bearer ${tokens.generateAccessToken(accountId.toString())}"

    private suspend fun ApplicationTestBuilder.postJson(
        path: String,
        body: String? = null,
    ) = client.post("/api/v1$path") {
        header(HttpHeaders.Authorization, bearer())
        if (body != null) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    @Test
    fun `the subscription cannot be read without signing in`() =
        billingApp { _, _ ->
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/billing/subscription").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/billing/checkout").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/billing/subscription/cancel").status)
        }

    @Test
    fun `buying a plan goes from checkout to an active subscription`() =
        billingApp { stripe, _ ->
            val checkout = postJson("/billing/checkout", """{"planId":"cloud_plus","interval":"YEAR"}""")
            assertEquals(HttpStatusCode.OK, checkout.status, checkout.bodyAsText())
            assertEquals("https://checkout.stripe.test/1", json.decodeFromString<BillingPage>(checkout.bodyAsText()).url)

            stripe.pay()
            val refreshed = json.decodeFromString<CloudSubscription>(postJson("/billing/subscription/refresh").bodyAsText())
            assertEquals("cloud_plus", refreshed.planId)

            val read =
                client.get("/api/v1/billing/subscription") { header(HttpHeaders.Authorization, bearer()) }
            assertEquals("no-store", read.headers[HttpHeaders.CacheControl])
            assertEquals(refreshed, json.decodeFromString<CloudSubscription>(read.bodyAsText()))
        }

    @Test
    fun `buying the base plan before launch is refused with a code a client can act on`() =
        billingApp(launched = false) { stripe, _ ->
            val response = postJson("/billing/checkout", """{"planId":"cloud","interval":"MONTH"}""")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("PLANS_NOT_ON_SALE"))
            assertTrue(stripe.checkouts.isEmpty())
        }

    @Test
    fun `a plan too small for what the account stores is refused with both numbers`() =
        billingApp { stripe, usage ->
            postJson("/billing/checkout", """{"planId":"cloud_plus","interval":"MONTH"}""")
            stripe.pay()
            postJson("/billing/subscription/refresh")
            usage[accountId] = 70 * GIB

            val response = postJson("/billing/subscription/plan", """{"planId":"cloud","interval":"MONTH"}""")

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("STORAGE_EXCEEDS_PLAN"), body)
            assertTrue(body.contains("\"usedBytes\":\"${70 * GIB}\"") && body.contains("\"includedBytes\":\"${50 * GIB}\""), body)
        }

    @Test
    fun `a body that is not a plan selection is a bad request`() =
        billingApp { _, _ ->
            val response = postJson("/billing/checkout", """{"plan":"cloud_plus"}""")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("INVALID_PLAN_SELECTION"))
        }

    @Test
    fun `Stripe being unreachable is reported as the payment provider's failure`() =
        billingApp { stripe, _ ->
            postJson("/billing/checkout", """{"planId":"cloud_plus","interval":"MONTH"}""")
            stripe.unreachable = true

            val response = postJson("/billing/checkout", """{"planId":"cloud_plus","interval":"MONTH"}""")

            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertTrue(response.bodyAsText().contains("BILLING_PROVIDER_UNAVAILABLE"))
        }

    @Test
    fun `a server without billing answers that it has none`() =
        testApplication {
            application {
                install(ServerContentNegotiation) { json(json) }
                routing {
                    route("/api/v1") {
                        cloudBillingRoutes(tokens, billing = null)
                        stripeWebhookRoutes(handler = null)
                    }
                }
            }

            val read = client.get("/api/v1/billing/subscription") { header(HttpHeaders.Authorization, bearer()) }
            val webhook = client.post("/api/v1/billing/stripe/webhook") { setBody("{}") }

            assertEquals(HttpStatusCode.NotFound, read.status)
            assertTrue(read.bodyAsText().contains("BILLING_UNAVAILABLE"))
            assertEquals(HttpStatusCode.NotFound, webhook.status)
        }

    @Test
    fun `a webhook without a valid signature is refused`() =
        billingApp { stripe, _ ->
            val response =
                client.post("/api/v1/billing/stripe/webhook") {
                    header("Stripe-Signature", "t=1,v1=deadbeef")
                    setBody("""{"id":"evt_1","type":"customer.subscription.created","data":{"object":{"id":"sub_1"}}}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(0, stripe.subscriptionReads)
        }

    @Test
    fun `the return page tells the person to go back to LogDate`() =
        billingApp { _, _ ->
            val done = client.get("/api/v1/billing/return?result=done")
            val cancelled = client.get("/api/v1/billing/return?result=cancelled")

            assertEquals(HttpStatusCode.OK, done.status)
            assertTrue(done.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
            assertTrue(done.bodyAsText().contains("Go back to LogDate. Your plan is ready there."))
            assertTrue(cancelled.bodyAsText().contains("Nothing was charged"))
        }
}
