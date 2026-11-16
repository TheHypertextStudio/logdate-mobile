package app.logdate.server.billing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One price LogDate Cloud needs in Stripe, found and created by its lookup key. */
data class CatalogPrice(
    val lookupKey: String,
    /** The name of the Stripe product the price belongs to, as it reads on an invoice. */
    val productName: String,
    val unitAmount: Long,
    /** `month` or `year`. */
    val interval: String,
    /** Billed by what the storage meter reports rather than a fixed quantity. */
    val metered: Boolean = false,
)

/**
 * LogDate Cloud's launch prices. This is the one place the amounts are written in code, and it is used only to create the prices in Stripe. Everything
 * that shows or charges a price afterwards reads it back from Stripe.
 */
object LaunchCatalog {
    const val CURRENCY: String = "usd"

    val prices: List<CatalogPrice> =
        listOf(
            CatalogPrice(StripeCatalogNames.monthlyLookupKey("cloud"), "LogDate Cloud", unitAmount = 399, interval = "month"),
            CatalogPrice(StripeCatalogNames.yearlyLookupKey("cloud"), "LogDate Cloud", unitAmount = 3999, interval = "year"),
            CatalogPrice(StripeCatalogNames.monthlyLookupKey("cloud_plus"), "LogDate Cloud Plus", unitAmount = 799, interval = "month"),
            CatalogPrice(StripeCatalogNames.yearlyLookupKey("cloud_plus"), "LogDate Cloud Plus", unitAmount = 7999, interval = "year"),
            CatalogPrice(
                StripeCatalogNames.extraStorageLookupKey("cloud_plus"),
                "LogDate Cloud Plus extra storage (50 GB)",
                unitAmount = 100,
                interval = "month",
                metered = true,
            ),
        )
}

/** What a setup run found or did for one thing LogDate Cloud needs in Stripe. */
sealed interface CatalogStep {
    val name: String

    /** It was already there and matches. */
    data class Present(
        override val name: String,
    ) : CatalogStep

    /** It was missing and has been created. */
    data class Created(
        override val name: String,
    ) : CatalogStep

    /** It is missing; a run that applies changes would create it. */
    data class Missing(
        override val name: String,
    ) : CatalogStep

    /** It exists but differs. Nothing is changed, because a price people may be paying is never rewritten. */
    data class Differs(
        override val name: String,
        val detail: String,
    ) : CatalogStep
}

/**
 * Makes a Stripe account ready to sell LogDate Cloud: the two plans' monthly and yearly prices, the
 * meter that counts extra storage and the usage-billed price attached to it. It can also check that
 * the webhook endpoint exists and sends the events the server acts on.
 *
 * Every run reads what Stripe already has and only adds what is missing, so running it again is
 * harmless, and running it against test mode and then live mode sets both up alike.
 */
class StripeCatalogSetup(
    private val stripe: HttpStripeApi,
) {
    /** Checks each thing, creating what is missing when [apply] is set. */
    suspend fun run(
        apply: Boolean,
        webhookUrl: String? = null,
    ): List<CatalogStep> {
        val steps = mutableListOf<CatalogStep>()
        val existing = stripe.prices(LaunchCatalog.prices.map { it.lookupKey }).associateBy { it.lookupKey }
        val meterName = "meter ${StripeCatalogNames.EXTRA_STORAGE_METER_EVENT}"
        var meterId = findMeter()
        steps +=
            when {
                meterId != null -> CatalogStep.Present(meterName)
                !apply -> CatalogStep.Missing(meterName)
                else -> CatalogStep.Created(meterName).also { meterId = createMeter() }
            }
        val products = mutableMapOf<String, String>()
        for (price in LaunchCatalog.prices) {
            val name = "price ${price.lookupKey}"
            val found = existing[price.lookupKey]
            steps +=
                when {
                    found != null -> {
                        price.compare(found, name)
                    }

                    !apply -> {
                        CatalogStep.Missing(name)
                    }

                    else -> {
                        val productId = products.getOrPut(price.productName) { createProduct(price.productName) }
                        createPrice(price, productId, meterId)
                        CatalogStep.Created(name)
                    }
                }
        }
        if (webhookUrl != null) steps += checkWebhook(webhookUrl)
        return steps
    }

    private fun CatalogPrice.compare(
        found: StripePrice,
        name: String,
    ): CatalogStep {
        val differences =
            listOfNotNull(
                "amount ${found.unitAmount} instead of $unitAmount".takeIf { found.unitAmount != unitAmount },
                "currency ${found.currency} instead of ${LaunchCatalog.CURRENCY}".takeIf { found.currency != LaunchCatalog.CURRENCY },
                "billed every ${found.interval} instead of every $interval".takeIf { found.interval != interval },
                (if (metered) "not billed by use" else "billed by use").takeIf { found.metered != metered },
            )
        return if (differences.isEmpty()) CatalogStep.Present(name) else CatalogStep.Differs(name, differences.joinToString(", "))
    }

    private suspend fun findMeter(): String? =
        stripe
            .get("/v1/billing/meters", listOf("status" to "active", "limit" to "100"))
            .objects("data")
            .firstOrNull { it.string("event_name") == StripeCatalogNames.EXTRA_STORAGE_METER_EVENT }
            ?.string("id")

    private suspend fun createMeter(): String? =
        stripe
            .post("/v1/billing/meters") {
                append("display_name", "LogDate Cloud extra storage")
                append("event_name", StripeCatalogNames.EXTRA_STORAGE_METER_EVENT)
                // The last value reported in a period is the extra storage in use when it is billed.
                append("default_aggregation[formula]", "last")
                append("customer_mapping[type]", "by_id")
                append("customer_mapping[event_payload_key]", "stripe_customer_id")
                append("value_settings[event_payload_key]", "value")
            }.string("id")

    private suspend fun createProduct(name: String): String =
        stripe.post("/v1/products") { append("name", name) }.string("id")
            ?: throw StripeException(status = 0, code = null, message = "Stripe sent a product without an id")

    private suspend fun createPrice(
        price: CatalogPrice,
        productId: String,
        meterId: String?,
    ) {
        stripe.post("/v1/prices") {
            append("product", productId)
            append("currency", LaunchCatalog.CURRENCY)
            append("unit_amount", price.unitAmount.toString())
            append("lookup_key", price.lookupKey)
            append("recurring[interval]", price.interval)
            if (price.metered) {
                append("recurring[usage_type]", "metered")
                append("recurring[meter]", meterId ?: throw StripeException(0, null, "The extra storage meter does not exist"))
            }
        }
    }

    /**
     * Checks the webhook endpoint at [url]. It is never created here: Stripe shows an endpoint's
     * signing secret once, at creation, and that secret belongs in the secret store, not in this
     * command's output.
     */
    private suspend fun checkWebhook(url: String): CatalogStep {
        val name = "webhook $url"
        val events = WEBHOOK_EVENTS.joinToString(", ")
        val existing =
            stripe.get("/v1/webhook_endpoints", listOf("limit" to "100")).objects("data").firstOrNull { it.string("url") == url }
                ?: return CatalogStep.Missing("$name (add it in Stripe, sending: $events)")
        val sent = (existing["enabled_events"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
        val missing = WEBHOOK_EVENTS.filterNot { it in sent || "*" in sent }
        return if (missing.isEmpty()) {
            CatalogStep.Present(
                name,
            )
        } else {
            CatalogStep.Differs(name, "does not send ${missing.joinToString(", ")}")
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.objects(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    companion object {
        /** The events the webhook handler acts on. */
        val WEBHOOK_EVENTS: List<String> =
            listOf(
                "checkout.session.completed",
                "customer.subscription.created",
                "customer.subscription.updated",
                "customer.subscription.deleted",
                "customer.subscription.paused",
                "customer.subscription.resumed",
            )
    }
}
