package app.logdate.server.billing

import app.logdate.server.auth.TokenService
import app.logdate.server.logging.BillingNotices
import app.logdate.server.responses.error
import app.logdate.server.routes.docs.BillingDocs
import app.logdate.server.routes.sync.extractUserId
import app.logdate.shared.model.CloudPlanSelection
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.UUID

/** What Stripe is told when its notification was accepted. */
@Serializable
internal data class StripeWebhookReceipt(
    val received: Boolean,
)

/** Where a person lands in the browser after a Stripe page, relative to the API root. */
const val BILLING_RETURN_PATH: String = "/billing/return"

/**
 * `/billing`: an account's LogDate Cloud plan, and buying, changing and cancelling it.
 *
 * [billing] is null on a server with billing switched off, which every self-hosted server is. The
 * routes still exist there and answer `404 BILLING_UNAVAILABLE`, which a client reads as "nothing
 * is sold here".
 */
internal fun Route.cloudBillingRoutes(
    tokenService: TokenService,
    billing: CloudBillingService?,
) {
    route("/billing") {
        get("/subscription", BillingDocs.getSubscription) {
            val (accountId, service) = call.billingCaller(tokenService, billing) ?: return@get
            call.answer { service.subscription(accountId) }
        }
        post("/subscription/refresh", BillingDocs.refreshSubscription) {
            val (accountId, service) = call.billingCaller(tokenService, billing) ?: return@post
            call.answer { service.refresh(accountId) }
        }
        post("/subscription/plan", BillingDocs.changePlan) {
            val (accountId, service) = call.billingCaller(tokenService, billing) ?: return@post
            val selection = call.receiveSelection() ?: return@post
            call.answer { service.changePlan(accountId, selection) }
        }
        post("/subscription/cancel", BillingDocs.cancelSubscription) {
            val (accountId, service) = call.billingCaller(tokenService, billing) ?: return@post
            call.answer { service.cancel(accountId) }
        }
        post("/subscription/keep", BillingDocs.keepSubscription) {
            val (accountId, service) = call.billingCaller(tokenService, billing) ?: return@post
            call.answer { service.keep(accountId) }
        }
        post("/checkout", BillingDocs.startCheckout) {
            val (accountId, service) = call.billingCaller(tokenService, billing) ?: return@post
            val selection = call.receiveSelection() ?: return@post
            call.answer { service.startCheckout(accountId, selection) }
        }
        post("/payment-details", BillingDocs.openPaymentDetails) {
            val (accountId, service) = call.billingCaller(tokenService, billing) ?: return@post
            call.answer { service.paymentDetails(accountId) }
        }
        get("/return", BillingDocs.returnPage) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Referrer-Policy", "no-referrer")
            call.respondText(
                billingReturnPage(cancelled = call.request.queryParameters["result"] == "cancelled"),
                ContentType.Text.Html.withCharset(Charsets.UTF_8),
            )
        }
    }
}

/**
 * `POST /billing/stripe/webhook`: Stripe's notifications about subscriptions. Unauthenticated by
 * design; the signature on each delivery is what proves it came from Stripe. [handler] is null on a
 * server not configured for Stripe, which answers `404`.
 */
internal fun Route.stripeWebhookRoutes(handler: StripeWebhookHandler?) {
    post("/billing/stripe/webhook", BillingDocs.stripeWebhook) {
        if (handler == null) return@post call.respondBillingUnavailable()
        val rawBody = call.receiveText()
        val outcome =
            try {
                handler.handle(rawBody, call.request.headers["Stripe-Signature"])
            } catch (e: StripeException) {
                // Answering with a failure makes Stripe deliver the event again later.
                Napier.e(BillingNotices.WEBHOOK_NOT_APPLIED, e)
                return@post call.respond(HttpStatusCode.ServiceUnavailable, error(PROVIDER_UNAVAILABLE, PROVIDER_UNAVAILABLE_MESSAGE))
            }
        when (outcome) {
            StripeWebhookHandler.Outcome.Handled -> {
                call.respond(HttpStatusCode.OK, StripeWebhookReceipt(received = true))
            }

            is StripeWebhookHandler.Outcome.Rejected -> {
                Napier.w(BillingNotices.WEBHOOK_REJECTED)
                call.respond(HttpStatusCode.BadRequest, error("INVALID_WEBHOOK", "The webhook could not be verified."))
            }
        }
    }
}

/** The signed-in account and the billing service, or null after answering why there is neither. */
private suspend fun ApplicationCall.billingCaller(
    tokenService: TokenService,
    billing: CloudBillingService?,
): Pair<UUID, CloudBillingService>? {
    val accountId = extractUserId(this, tokenService) ?: return null
    if (billing == null) {
        respondBillingUnavailable()
        return null
    }
    return accountId to billing
}

private suspend fun ApplicationCall.respondBillingUnavailable() =
    respond(HttpStatusCode.NotFound, error("BILLING_UNAVAILABLE", "This server has no billing."))

private suspend fun ApplicationCall.receiveSelection(): CloudPlanSelection? =
    runCatching { receive<CloudPlanSelection>() }.getOrNull()
        ?: run {
            respond(HttpStatusCode.BadRequest, error("INVALID_PLAN_SELECTION", "Send a plan and how often it bills."))
            null
        }

/** Answers with [block]'s result, or the refusal or provider failure it raised. */
private suspend inline fun <reified T : Any> ApplicationCall.answer(block: () -> T) {
    response.header(HttpHeaders.CacheControl, "no-store")
    try {
        respond(HttpStatusCode.OK, block())
    } catch (e: BillingRefused) {
        val refusal = e.refusal
        val details =
            if (refusal is BillingRefusal.StorageExceedsPlan) {
                mapOf("usedBytes" to refusal.usedBytes.toString(), "includedBytes" to refusal.includedBytes.toString())
            } else {
                emptyMap()
            }
        respond(HttpStatusCode.Conflict, error(refusal.code, refusal.message, details))
    } catch (e: StripeException) {
        Napier.e(BillingNotices.PROVIDER_FAILED, e)
        respond(HttpStatusCode.BadGateway, error(PROVIDER_UNAVAILABLE, PROVIDER_UNAVAILABLE_MESSAGE))
    }
}

private const val PROVIDER_UNAVAILABLE = "BILLING_PROVIDER_UNAVAILABLE"
private const val PROVIDER_UNAVAILABLE_MESSAGE = "The payment provider could not be reached. Try again."

/**
 * The page the browser shows after Stripe Checkout or the payment-details page. It only tells the
 * person to go back to the app; the app reads the plan from the server, never from this page.
 */
internal fun billingReturnPage(cancelled: Boolean): String {
    val title = if (cancelled) "Nothing was charged" else "You’re all set"
    val body = if (cancelled) "Go back to LogDate to pick a plan when you’re ready." else "Go back to LogDate. Your plan is ready there."
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="robots" content="noindex">
        <title>LogDate Cloud</title>
        <style>
        :root { color-scheme: light dark; --page: #fbf3ea; --ink: #3a1f33; --quiet: #6d5564; }
        @media (prefers-color-scheme: dark) { :root { --page: #24141f; --ink: #f6e9df; --quiet: #cdb6c2; } }
        html, body { height: 100%; margin: 0; }
        body { background: var(--page); color: var(--ink); display: grid; place-items: center;
          font: 17px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
        main { max-width: 22rem; padding: 2rem 1.5rem; text-align: center; }
        .name { color: var(--quiet); font-size: 0.875rem; letter-spacing: 0.02em; margin: 0 0 1.25rem; }
        h1 { font-size: 1.75rem; line-height: 1.2; margin: 0 0 0.5rem; text-wrap: balance; }
        p { margin: 0; color: var(--quiet); text-wrap: pretty; }
        </style>
        </head>
        <body>
        <main>
        <p class="name">LogDate Cloud</p>
        <h1>$title</h1>
        <p>$body</p>
        </main>
        </body>
        </html>
        """.trimIndent()
}
