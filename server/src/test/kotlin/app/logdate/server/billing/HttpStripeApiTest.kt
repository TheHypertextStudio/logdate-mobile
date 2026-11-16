package app.logdate.server.billing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The requests the server sends Stripe and how it reads Stripe's answers. */
class HttpStripeApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        answer: String,
    ): HttpStripeApi {
        val engine =
            MockEngine { request ->
                requests += request
                respond(answer, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return HttpStripeApi(
            HttpClient(engine),
            StripeConfig(secretKey = "sk_test_key", webhookSecret = "whsec", apiBaseUrl = "https://stripe.test"),
        )
    }

    private fun HttpRequestData.form(): Map<String, String> =
        (body as FormDataContent).formData.entries().associate { it.key to it.value.single() }

    private val subscriptionJson =
        """
        {
          "id": "sub_1", "object": "subscription", "customer": "cus_1", "status": "active",
          "cancel_at": 1802592000, "metadata": {"account_id": "0b0f7e3e-58a5-4c62-9a55-2f3f2a3c9a11", "plan_id": "cloud_plus"},
          "items": {"object": "list", "data": [
            {"id": "si_fixed", "current_period_end": 1831536000,
             "price": {"id": "price_y", "lookup_key": "logdate_cloud_plus_yearly", "unit_amount": 7999, "currency": "usd",
                       "recurring": {"interval": "year", "usage_type": "licensed"}}},
            {"id": "si_metered", "current_period_end": 1802592000,
             "price": {"id": "price_extra", "lookup_key": "logdate_cloud_plus_extra_storage", "unit_amount": 100, "currency": "usd",
                       "recurring": {"interval": "month", "usage_type": "metered", "meter": "mtr_1"}}}
          ]}
        }
        """.trimIndent()

    @Test
    fun `every request carries the secret key and the pinned API version`() =
        runTest {
            api(answer = """{"id":"cus_1"}""").createCustomer(UUID.randomUUID())

            val request = requests.single()
            assertEquals("Bearer sk_test_key", request.headers[HttpHeaders.Authorization])
            assertEquals(HttpStripeApi.API_VERSION, request.headers["Stripe-Version"])
        }

    @Test
    fun `creating a customer is idempotent per account and sends no personal details`() =
        runTest {
            val accountId = UUID.randomUUID()

            val customerId = api(answer = """{"id":"cus_1"}""").createCustomer(accountId)

            val request = requests.single()
            assertEquals("cus_1", customerId)
            assertEquals("https://stripe.test/v1/customers", request.url.toString())
            assertEquals("logdate-customer-$accountId", request.headers["Idempotency-Key"])
            assertEquals(mapOf("metadata[account_id]" to accountId.toString()), request.form())
        }

    @Test
    fun `a checkout is a flexible-billing subscription for one fixed price, tied to the account`() =
        runTest {
            val accountId = UUID.randomUUID()

            val url =
                api(answer = """{"id":"cs_1","url":"https://checkout.stripe.com/c/pay/cs_1"}""").createCheckoutSession(
                    StripeCheckoutRequest(
                        customerId = "cus_1",
                        priceId = "price_y",
                        accountId = accountId,
                        planId = "cloud_plus",
                        successUrl = "https://cloud.logdate.test/return?result=done",
                        cancelUrl = "https://cloud.logdate.test/return?result=cancelled",
                    ),
                )

            assertEquals("https://checkout.stripe.com/c/pay/cs_1", url)
            assertEquals("https://stripe.test/v1/checkout/sessions", requests.single().url.toString())
            assertEquals(
                mapOf(
                    "mode" to "subscription",
                    "customer" to "cus_1",
                    "client_reference_id" to accountId.toString(),
                    "line_items[0][price]" to "price_y",
                    "line_items[0][quantity]" to "1",
                    "success_url" to "https://cloud.logdate.test/return?result=done",
                    "cancel_url" to "https://cloud.logdate.test/return?result=cancelled",
                    "subscription_data[metadata][account_id]" to accountId.toString(),
                    "subscription_data[metadata][plan_id]" to "cloud_plus",
                    "subscription_data[billing_mode][type]" to "flexible",
                ),
                requests.single().form(),
            )
        }

    @Test
    fun `a subscription is read with its account, end date and both kinds of item`() =
        runTest {
            val subscription = api(answer = subscriptionJson).subscription("sub_1")!!

            assertEquals("https://stripe.test/v1/subscriptions/sub_1", requests.single().url.toString())
            assertEquals("cus_1", subscription.customerId)
            assertEquals("active", subscription.status)
            assertEquals(1_802_592_000L, subscription.cancelAtEpochSeconds)
            assertEquals("0b0f7e3e-58a5-4c62-9a55-2f3f2a3c9a11", subscription.accountId)
            val (fixed, metered) = subscription.items
            assertEquals(StripePrice("price_y", "logdate_cloud_plus_yearly", 7999, "usd", "year", metered = false), fixed.price)
            assertEquals(1_831_536_000L, fixed.currentPeriodEndEpochSeconds)
            assertTrue(metered.price.metered)
            assertEquals("month", metered.price.interval)
        }

    @Test
    fun `a subscription Stripe does not have reads as none`() =
        runTest {
            val missing =
                api(
                    HttpStatusCode.NotFound,
                    """{"error":{"type":"invalid_request_error","code":"resource_missing","message":"No such subscription"}}""",
                ).subscription("sub_gone")

            assertNull(missing)
        }

    @Test
    fun `prices are looked up by key among active prices`() =
        runTest {
            val prices =
                api(
                    answer =
                        """
                        {"object":"list","data":[
                          {"id":"price_m","lookup_key":"logdate_cloud_monthly","unit_amount":399,"currency":"usd",
                           "recurring":{"interval":"month","usage_type":"licensed"}},
                          {"id":"price_once","lookup_key":"one_off","unit_amount":500,"currency":"usd","recurring":null}
                        ]}
                        """.trimIndent(),
                ).prices(listOf("logdate_cloud_monthly", "logdate_cloud_yearly"))

            val url = requests.single().url
            assertEquals(HttpMethod.Get, requests.single().method)
            assertEquals("/v1/prices", url.encodedPath)
            assertEquals(listOf("logdate_cloud_monthly", "logdate_cloud_yearly"), url.parameters.getAll("lookup_keys[]"))
            assertEquals("true", url.parameters["active"])
            assertEquals(listOf(StripePrice("price_m", "logdate_cloud_monthly", 399, "usd", "month", metered = false)), prices)
        }

    @Test
    fun `moving up invoices at once and moving down leaves a credit`() =
        runTest {
            val api = api(answer = subscriptionJson)

            api.changePrice("sub_1", itemId = "si_fixed", priceId = "price_plus", invoiceNow = true)
            api.changePrice("sub_1", itemId = "si_fixed", priceId = "price_base", invoiceNow = false)

            assertEquals("https://stripe.test/v1/subscriptions/sub_1", requests.first().url.toString())
            assertEquals(
                mapOf("items[0][id]" to "si_fixed", "items[0][price]" to "price_plus", "proration_behavior" to "always_invoice"),
                requests.first().form(),
            )
            assertEquals("create_prorations", requests.last().form()["proration_behavior"])
        }

    @Test
    fun `the metered price is added as an item with no quantity`() =
        runTest {
            api(answer = """{"id":"si_2"}""").addMeteredItem("sub_1", "price_extra")

            assertEquals("https://stripe.test/v1/subscription_items", requests.single().url.toString())
            assertEquals(mapOf("subscription" to "sub_1", "price" to "price_extra"), requests.single().form())
        }

    @Test
    fun `cancelling stops at the end of the longest paid period and keeping clears it`() =
        runTest {
            val api = api(answer = subscriptionJson)

            api.cancelAtPeriodEnd("sub_1")
            api.keepSubscription("sub_1")

            assertEquals(mapOf("cancel_at" to "max_period_end"), requests.first().form())
            assertEquals(mapOf("cancel_at" to ""), requests.last().form())
        }

    @Test
    fun `ending a subscription now deletes it, and one already gone is not an error`() =
        runTest {
            api(HttpStatusCode.NotFound, """{"error":{"code":"resource_missing","message":"No such subscription"}}""").cancelNow("sub_1")

            assertEquals(HttpMethod.Delete, requests.single().method)
            assertEquals("https://stripe.test/v1/subscriptions/sub_1", requests.single().url.toString())
        }

    @Test
    fun `extra storage is reported as a meter event for the customer`() =
        runTest {
            api(answer = """{"object":"billing.meter_event"}""").reportMeterEvent(
                eventName = "logdate_cloud_extra_storage",
                customerId = "cus_1",
                value = 3,
                identifier = "account-1800000000",
                timestampEpochSeconds = 1_800_000_000,
            )

            assertEquals("https://stripe.test/v1/billing/meter_events", requests.single().url.toString())
            assertEquals(
                mapOf(
                    "event_name" to "logdate_cloud_extra_storage",
                    "payload[stripe_customer_id]" to "cus_1",
                    "payload[value]" to "3",
                    "identifier" to "account-1800000000",
                    "timestamp" to "1800000000",
                ),
                requests.single().form(),
            )
        }

    @Test
    fun `the payment page is opened for the customer with a way back`() =
        runTest {
            val url =
                api(
                    answer = """{"url":"https://billing.stripe.com/p/session/1"}""",
                ).createPortalSession("cus_1", "https://cloud.logdate.test/return")

            assertEquals("https://billing.stripe.com/p/session/1", url)
            assertEquals(mapOf("customer" to "cus_1", "return_url" to "https://cloud.logdate.test/return"), requests.single().form())
        }

    @Test
    fun `a refusal from Stripe carries Stripe's status and code`() =
        runTest {
            val failure =
                assertFailsWith<StripeException> {
                    api(
                        HttpStatusCode.PaymentRequired,
                        """{"error":{"type":"card_error","code":"card_declined","message":"Your card was declined."}}""",
                    ).changePrice("sub_1", "si_fixed", "price_plus", invoiceNow = true)
                }

            assertEquals(402, failure.status)
            assertEquals("card_declined", failure.code)
        }

    @Test
    fun `the configuration never prints its keys`() {
        val printed = StripeConfig(secretKey = "sk_live_secret", webhookSecret = "whsec_secret").toString()

        assertTrue("sk_live_secret" !in printed && "whsec_secret" !in printed)
    }
}
