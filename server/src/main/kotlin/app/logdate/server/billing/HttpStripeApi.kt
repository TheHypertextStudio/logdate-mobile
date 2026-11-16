package app.logdate.server.billing

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.util.UUID

/**
 * [StripeApi] over Stripe's HTTP API: form-encoded requests, JSON answers, the secret key as a
 * bearer token, and the API version pinned so Stripe's defaults can't change under the server.
 *
 * Only the handful of calls LogDate Cloud makes are here, which is why the server carries no
 * Stripe SDK.
 */
class HttpStripeApi(
    private val http: HttpClient,
    private val config: StripeConfig,
) : StripeApi {
    override suspend fun prices(lookupKeys: List<String>): List<StripePrice> {
        if (lookupKeys.isEmpty()) return emptyList()
        val body =
            read(
                send {
                    http.get("${config.apiBaseUrl}/v1/prices") {
                        stripeHeaders()
                        parameter("active", "true")
                        parameter("limit", "100")
                        lookupKeys.forEach { parameter("lookup_keys[]", it) }
                    }
                },
            )
        return body.objects("data").mapNotNull { it.toPrice() }
    }

    override suspend fun createCustomer(accountId: UUID): String =
        post(
            "/v1/customers",
            // A retried request must not leave an account with two customers.
            idempotencyKey = "logdate-customer-$accountId",
        ) {
            append("metadata[account_id]", accountId.toString())
        }.string("id") ?: throw malformed("customer without an id")

    override suspend fun createCheckoutSession(request: StripeCheckoutRequest): String =
        post("/v1/checkout/sessions") {
            append("mode", "subscription")
            append("customer", request.customerId)
            append("client_reference_id", request.accountId.toString())
            append("line_items[0][price]", request.priceId)
            append("line_items[0][quantity]", "1")
            append("success_url", request.successUrl)
            append("cancel_url", request.cancelUrl)
            append("subscription_data[metadata][account_id]", request.accountId.toString())
            append("subscription_data[metadata][plan_id]", request.planId)
            // Flexible billing lets a yearly plan carry a monthly usage-billed item later.
            append("subscription_data[billing_mode][type]", "flexible")
        }.string("url") ?: throw malformed("checkout session without a url")

    override suspend fun subscription(id: String): StripeSubscription? {
        val response = send { http.get("${config.apiBaseUrl}/v1/subscriptions/$id") { stripeHeaders() } }
        if (response.status == HttpStatusCode.NotFound) return null
        return read(response).toSubscription()
    }

    override suspend fun subscriptions(customerId: String): List<StripeSubscription> =
        read(
            send {
                http.get("${config.apiBaseUrl}/v1/subscriptions") {
                    stripeHeaders()
                    parameter("customer", customerId)
                    parameter("status", "all")
                    parameter("limit", "10")
                }
            },
        ).objects("data").map { it.toSubscription() }

    override suspend fun changePrice(
        subscriptionId: String,
        itemId: String,
        priceId: String,
        invoiceNow: Boolean,
    ): StripeSubscription =
        post("/v1/subscriptions/$subscriptionId") {
            append("items[0][id]", itemId)
            append("items[0][price]", priceId)
            append("proration_behavior", if (invoiceNow) "always_invoice" else "create_prorations")
        }.toSubscription()

    override suspend fun addMeteredItem(
        subscriptionId: String,
        priceId: String,
    ) {
        post("/v1/subscription_items") {
            append("subscription", subscriptionId)
            append("price", priceId)
        }
    }

    override suspend fun removeItem(itemId: String) {
        read(send { http.delete("${config.apiBaseUrl}/v1/subscription_items/$itemId") { stripeHeaders() } })
    }

    override suspend fun cancelAtPeriodEnd(subscriptionId: String): StripeSubscription =
        post("/v1/subscriptions/$subscriptionId") {
            // With a yearly plan and a monthly metered item, this is the end of the paid year.
            append("cancel_at", "max_period_end")
        }.toSubscription()

    override suspend fun keepSubscription(subscriptionId: String): StripeSubscription =
        post("/v1/subscriptions/$subscriptionId") {
            append("cancel_at", "")
        }.toSubscription()

    override suspend fun cancelNow(subscriptionId: String) {
        val response = send { http.delete("${config.apiBaseUrl}/v1/subscriptions/$subscriptionId") { stripeHeaders() } }
        if (response.status == HttpStatusCode.NotFound) return
        read(response)
    }

    override suspend fun createPortalSession(
        customerId: String,
        returnUrl: String,
    ): String =
        post("/v1/billing_portal/sessions") {
            append("customer", customerId)
            append("return_url", returnUrl)
        }.string("url") ?: throw malformed("portal session without a url")

    override suspend fun reportMeterEvent(
        eventName: String,
        customerId: String,
        value: Long,
        identifier: String,
        timestampEpochSeconds: Long,
    ) {
        post("/v1/billing/meter_events") {
            append("event_name", eventName)
            append("payload[stripe_customer_id]", customerId)
            append("payload[value]", value.toString())
            append("identifier", identifier)
            append("timestamp", timestampEpochSeconds.toString())
        }
    }

    /** `GET [path]` with [query], answering Stripe's JSON. For the calls only catalog setup makes. */
    internal suspend fun get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): JsonObject =
        read(
            send {
                http.get("${config.apiBaseUrl}$path") {
                    stripeHeaders()
                    query.forEach { (name, value) -> parameter(name, value) }
                }
            },
        )

    /** `POST [path]` with a form body, answering Stripe's JSON. */
    internal suspend fun post(
        path: String,
        idempotencyKey: String? = null,
        form: ParametersBuilder.() -> Unit,
    ): JsonObject =
        read(
            send {
                http.post("${config.apiBaseUrl}$path") {
                    stripeHeaders()
                    if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
                    setBody(FormDataContent(Parameters.build(form)))
                }
            },
        )

    /** Runs a request, turning a network failure into the same [StripeException] a refusal raises. */
    private suspend fun send(request: suspend () -> HttpResponse): HttpResponse =
        try {
            request()
        } catch (e: IOException) {
            throw StripeException(status = 0, code = null, message = "Stripe could not be reached", cause = e)
        }

    private fun io.ktor.client.request.HttpRequestBuilder.stripeHeaders() {
        header(HttpHeaders.Authorization, "Bearer ${config.secretKey}")
        header("Stripe-Version", API_VERSION)
    }

    private suspend fun read(response: HttpResponse): JsonObject {
        val text = response.bodyAsText()
        val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (!response.status.isSuccess()) {
            val error = body?.get("error") as? JsonObject
            throw StripeException(
                status = response.status.value,
                code = error?.string("code") ?: error?.string("type"),
                message = error?.string("message") ?: "Stripe answered ${response.status.value}",
            )
        }
        return body ?: throw malformed("an answer that is not a JSON object")
    }

    private fun malformed(what: String) = StripeException(status = 0, code = null, message = "Stripe sent $what")

    private fun JsonObject.toSubscription(): StripeSubscription =
        StripeSubscription(
            id = string("id") ?: throw malformed("a subscription without an id"),
            customerId = string("customer") ?: (get("customer") as? JsonObject)?.string("id").orEmpty(),
            status = string("status").orEmpty(),
            cancelAtEpochSeconds = long("cancel_at"),
            accountId = (get("metadata") as? JsonObject)?.string("account_id"),
            items =
                (get("items") as? JsonObject)?.objects("data").orEmpty().mapNotNull { item ->
                    val id = item.string("id") ?: return@mapNotNull null
                    val price = (item["price"] as? JsonObject)?.toPrice() ?: return@mapNotNull null
                    StripeSubscriptionItem(id = id, price = price, currentPeriodEndEpochSeconds = item.long("current_period_end"))
                },
        )

    private fun JsonObject.toPrice(): StripePrice? {
        val recurring = get("recurring") as? JsonObject ?: return null
        return StripePrice(
            id = string("id") ?: return null,
            lookupKey = string("lookup_key"),
            unitAmount = long("unit_amount") ?: 0L,
            currency = string("currency").orEmpty(),
            interval = recurring.string("interval").orEmpty(),
            metered = recurring.string("usage_type") == "metered",
        )
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    companion object {
        /** The Stripe API version every request asks for. */
        const val API_VERSION: String = "2026-10-28.endive"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
