package app.logdate.server.billing

import app.logdate.server.logging.BillingNotices
import io.github.aakira.napier.Napier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * Handles a Stripe webhook: proves Stripe sent it, then brings the account's entitlement in line
 * with the subscription the event is about.
 *
 * The event's own copy of the subscription is never trusted for state. Events can arrive late or
 * out of order, so the handler reads the subscription from Stripe as it is now and applies that.
 * Handling an event twice is therefore harmless, and each event is recorded so a repeat is
 * answered without calling Stripe again.
 */
class StripeWebhookHandler(
    private val verifier: StripeSignatureVerifier,
    private val stripe: StripeApi,
    private val sync: SubscriptionSync,
    private val store: BillingStore,
    private val clock: Clock = Clock.System,
) {
    sealed class Outcome {
        /** The event was applied, or needed nothing. Stripe should not send it again. */
        data object Handled : Outcome()

        /** The signature did not verify or the body was not an event. Nothing was changed. */
        data class Rejected(
            val reason: String,
        ) : Outcome()
    }

    /** Handles one delivery. Throws [StripeException] when Stripe cannot be read, so Stripe retries. */
    suspend fun handle(
        rawBody: String,
        signatureHeader: String?,
    ): Outcome {
        val verification = verifier.verify(rawBody, signatureHeader, clock.now().epochSeconds)
        if (verification is StripeSignatureVerifier.Verification.Rejected) return Outcome.Rejected(verification.reason)

        val event = runCatching { json.parseToJsonElement(rawBody).jsonObject }.getOrNull() ?: return Outcome.Rejected("Body is not JSON")
        val eventId = event.string("id") ?: return Outcome.Rejected("Event has no id")
        val type = event.string("type") ?: return Outcome.Rejected("Event has no type")
        if (store.eventHandled(eventId)) return Outcome.Handled

        val subject = (event["data"] as? JsonObject)?.get("object") as? JsonObject
        val subscriptionId =
            when {
                type.startsWith("customer.subscription.") -> subject?.string("id")
                type == "checkout.session.completed" -> subject?.string("subscription")
                else -> null
            }
        val accountId =
            subscriptionId
                ?.let { stripe.subscription(it) }
                ?.let { sync.apply(it) }
        if (subscriptionId != null && accountId == null) {
            Napier.w(BillingNotices.WEBHOOK_CHANGED_NOTHING)
        }
        store.recordEvent(eventId = eventId, eventType = type, accountId = accountId)
        return Outcome.Handled
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
