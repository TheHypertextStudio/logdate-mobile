package app.logdate.server.billing

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Setting a Stripe account up to sell LogDate Cloud, against a Stripe that remembers what it is given. */
class StripeCatalogSetupTest {
    /** Just enough of Stripe to hold products, prices, meters and webhook endpoints. */
    private class RememberingStripe {
        val prices = mutableListOf<Map<String, String>>()
        val meters = mutableListOf<Map<String, String>>()
        val products = mutableListOf<String>()
        val webhooks = mutableListOf<Pair<String, List<String>>>()
        var writes = 0

        val engine =
            MockEngine { request ->
                val form =
                    (request.body as? FormDataContent)
                        ?.formData
                        ?.entries()
                        ?.associate { it.key to it.value.single() }
                        .orEmpty()
                val path = request.url.encodedPath
                val body =
                    when {
                        request.method == HttpMethod.Get && path == "/v1/prices" -> {
                            val keys =
                                request.url.parameters
                                    .getAll("lookup_keys[]")
                                    .orEmpty()
                            list(prices.filter { it["lookup_key"] in keys }.map(::priceJson))
                        }

                        request.method == HttpMethod.Get && path == "/v1/billing/meters" -> {
                            list(meters.map { """{"id":"${it["id"]}","event_name":"${it["event_name"]}"}""" })
                        }

                        request.method == HttpMethod.Get && path == "/v1/webhook_endpoints" -> {
                            list(
                                webhooks.map { (url, events) ->
                                    """{"url":"$url","enabled_events":[${events.joinToString(",") { "\"$it\"" }}]}"""
                                },
                            )
                        }

                        path == "/v1/products" -> {
                            writes++
                            products += form.getValue("name")
                            """{"id":"prod_${products.size}"}"""
                        }

                        path == "/v1/billing/meters" -> {
                            writes++
                            meters += form + ("id" to "mtr_${meters.size + 1}")
                            """{"id":"mtr_${meters.size}"}"""
                        }

                        path == "/v1/prices" -> {
                            writes++
                            prices += form + ("id" to "price_${prices.size + 1}")
                            priceJson(prices.last())
                        }

                        else -> {
                            error("unexpected request ${request.method.value} $path")
                        }
                    }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }

        private fun list(items: List<String>) = """{"object":"list","data":[${items.joinToString(",")}]}"""

        private fun priceJson(price: Map<String, String>): String =
            """
            {"id":"${price["id"]}","lookup_key":"${price["lookup_key"]}","unit_amount":${price["unit_amount"]},
             "currency":"${price["currency"]}","recurring":{"interval":"${price["recurring[interval]"]}",
             "usage_type":"${price["recurring[usage_type]"] ?: "licensed"}"}}
            """.trimIndent()

        fun setup() = StripeCatalogSetup(HttpStripeApi(HttpClient(engine), StripeConfig("sk_test_key", "", "https://stripe.test")))
    }

    @Test
    fun `status on an empty account lists everything as missing and changes nothing`() =
        runTest {
            val stripe = RememberingStripe()

            val steps = stripe.setup().run(apply = false)

            assertEquals(6, steps.size)
            assertTrue(steps.all { it is CatalogStep.Missing })
            assertEquals(0, stripe.writes)
            assertFalse(catalogIsReady(steps))
        }

    @Test
    fun `apply creates the meter, the four plan prices and the metered price at the launch amounts`() =
        runTest {
            val stripe = RememberingStripe()

            val steps = stripe.setup().run(apply = true)

            assertTrue(steps.all { it is CatalogStep.Created })
            assertTrue(catalogIsReady(steps))
            assertEquals(
                mapOf(
                    "logdate_cloud_monthly" to ("399" to "month"),
                    "logdate_cloud_yearly" to ("3999" to "year"),
                    "logdate_cloud_plus_monthly" to ("799" to "month"),
                    "logdate_cloud_plus_yearly" to ("7999" to "year"),
                    "logdate_cloud_plus_extra_storage" to ("100" to "month"),
                ),
                stripe.prices.associate { it.getValue("lookup_key") to (it.getValue("unit_amount") to it.getValue("recurring[interval]")) },
            )
            assertTrue(stripe.prices.all { it["currency"] == "usd" })
            assertEquals(listOf("LogDate Cloud", "LogDate Cloud Plus", "LogDate Cloud Plus extra storage (50 GB)"), stripe.products)

            val meter = stripe.meters.single()
            assertEquals("logdate_cloud_extra_storage", meter["event_name"])
            assertEquals("last", meter["default_aggregation[formula]"], "the charge follows the extra storage in use when it is billed")
            val metered = stripe.prices.single { it["lookup_key"] == "logdate_cloud_plus_extra_storage" }
            assertEquals("metered", metered["recurring[usage_type]"])
            assertEquals(meter["id"], metered["recurring[meter]"])
            assertTrue(stripe.prices.filter { it !== metered }.none { "recurring[usage_type]" in it })
        }

    @Test
    fun `applying a second time finds everything present and creates nothing`() =
        runTest {
            val stripe = RememberingStripe()
            stripe.setup().run(apply = true)
            val writesAfterFirst = stripe.writes

            val steps = stripe.setup().run(apply = true)

            assertTrue(steps.all { it is CatalogStep.Present })
            assertEquals(writesAfterFirst, stripe.writes)
        }

    @Test
    fun `a price that exists at another amount is reported and left alone`() =
        runTest {
            val stripe = RememberingStripe()
            stripe.setup().run(apply = true)
            val index = stripe.prices.indexOfFirst { it["lookup_key"] == "logdate_cloud_monthly" }
            stripe.prices[index] = stripe.prices[index] + ("unit_amount" to "499")
            val writesBefore = stripe.writes

            val steps = stripe.setup().run(apply = true)

            val differs = assertIs<CatalogStep.Differs>(steps.single { it.name == "price logdate_cloud_monthly" })
            assertEquals("amount 499 instead of 399", differs.detail)
            assertEquals(writesBefore, stripe.writes, "a price people may be paying is never rewritten")
            assertFalse(catalogIsReady(steps))
        }

    @Test
    fun `the webhook is checked for the events the server acts on and never created`() =
        runTest {
            val stripe = RememberingStripe()
            val url = "https://cloud.logdate.test/api/v1/billing/stripe/webhook"
            stripe.setup().run(apply = true)
            val writesBefore = stripe.writes

            val missing = stripe.setup().run(apply = true, webhookUrl = url).last()
            stripe.webhooks += url to listOf("customer.subscription.updated")
            val incomplete = stripe.setup().run(apply = true, webhookUrl = url).last()
            stripe.webhooks[0] = url to StripeCatalogSetup.WEBHOOK_EVENTS
            val present = stripe.setup().run(apply = true, webhookUrl = url).last()

            assertIs<CatalogStep.Missing>(missing)
            assertTrue(missing.name.contains("checkout.session.completed"), "what to add is spelled out")
            assertTrue(assertIs<CatalogStep.Differs>(incomplete).detail.contains("customer.subscription.deleted"))
            assertIs<CatalogStep.Present>(present)
            assertEquals(writesBefore, stripe.writes)
        }

    @Test
    fun `the command line names a subcommand and an https webhook address`() {
        assertEquals(StripeCatalogInvocation(apply = false), parseStripeCatalogArgs(listOf("status")))
        assertEquals(
            StripeCatalogInvocation(apply = true, webhookUrl = "https://cloud.logdate.test/hook", reportFile = Path.of("/tmp/report.txt")),
            parseStripeCatalogArgs(listOf("apply", "--webhook-url", "https://cloud.logdate.test/hook", "--report-file", "/tmp/report.txt")),
        )
        assertFailsWith<StripeCatalogUsageException> { parseStripeCatalogArgs(emptyList()) }
        assertFailsWith<StripeCatalogUsageException> { parseStripeCatalogArgs(listOf("apply", "--webhook-url", "http://insecure")) }
        assertFailsWith<StripeCatalogUsageException> { parseStripeCatalogArgs(listOf("status", "--force")) }
    }

    @Test
    fun `each step reads as one line`() {
        val lines =
            describeCatalogSteps(
                listOf(
                    CatalogStep.Present("price a"),
                    CatalogStep.Created("price b"),
                    CatalogStep.Missing("price c"),
                    CatalogStep.Differs("price d", "amount 1 instead of 2"),
                ),
            )

        assertEquals(listOf("ok       price a", "created  price b", "missing  price c", "differs  price d: amount 1 instead of 2"), lines)
    }
}
