package app.logdate.server.routes.docs

import app.logdate.server.billing.StripeWebhookReceipt
import app.logdate.server.openapi.ApiTags
import app.logdate.server.routes.ErrorCase
import app.logdate.server.routes.ErrorEnvelope
import app.logdate.server.routes.bearerOperation
import app.logdate.server.routes.bearerUnauthorized
import app.logdate.server.routes.jsonBody
import app.logdate.server.routes.ok
import app.logdate.server.routes.publicOperation
import app.logdate.server.routes.syncError
import app.logdate.shared.model.BillingInterval
import app.logdate.shared.model.BillingPage
import app.logdate.shared.model.CloudPlanSelection
import app.logdate.shared.model.CloudSubscription
import app.logdate.shared.model.EntitlementStatusWire
import io.github.smiley4.ktoropenapi.config.RequestConfig
import io.github.smiley4.ktoropenapi.config.ResponsesConfig
import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.HttpStatusCode

/** Documentation for the billing endpoints in `CloudBillingRoutes.kt`. */
internal object BillingDocs {
    private const val GIB = 1_073_741_824L

    private val plusSubscription =
        CloudSubscription(
            planId = "cloud_plus",
            status = EntitlementStatusWire.ACTIVE,
            includedWithBeta = false,
            betaMember = true,
            freePlanId = "cloud",
            interval = BillingInterval.MONTH,
            renewsAtEpochSeconds = 1_800_000_000,
            storageUsedBytes = 281 * GIB,
            storageIncludedBytes = 250 * GIB,
            extraStorageSteps = 1,
            purchasesAvailable = true,
            paymentDetailsAvailable = true,
        )

    private val notOnSale =
        ErrorCase(
            "PLANS_NOT_ON_SALE",
            "That plan is not being sold: this server has no payment provider, or it is the base plan before launch. Offer only plans whose `onSale` is `true`.",
            "That plan is not on sale yet.",
        )

    private val noSubscription =
        ErrorCase(
            "NO_SUBSCRIPTION",
            "The account has no paid plan to act on. Read the subscription again and offer checkout instead.",
            "This account has no paid plan.",
        )

    private val includedWithBeta =
        ErrorCase(
            "INCLUDED_WITH_BETA",
            "The account joined during the beta and already has LogDate Cloud at no charge, so it cannot be sold to it.",
            "LogDate Cloud is free for this account, so there is nothing to buy.",
        )

    private val unknownPlan = ErrorCase("UNKNOWN_PLAN", "No plan has that identifier. Read the catalog again.", "That plan does not exist.")

    private val providerUnavailable =
        ErrorCase(
            "BILLING_PROVIDER_UNAVAILABLE",
            "The payment provider refused the request or could not be reached. Nothing changed; trying again is safe.",
            "The payment provider could not be reached. Try again.",
        )

    private val billingUnavailable =
        ErrorCase(
            "BILLING_UNAVAILABLE",
            "This server has billing switched off, as every self-hosted server does. Hide plans and billing altogether.",
            "This server has no billing.",
        )

    private fun RequestConfig.planSelection() =
        jsonBody(
            CloudPlanSelection(planId = "cloud_plus", interval = BillingInterval.YEAR),
            "The plan to be on and how often to bill it.",
        )

    private fun ResponsesConfig.invalidSelection() =
        syncError(
            HttpStatusCode.BadRequest,
            ErrorCase("INVALID_PLAN_SELECTION", "The body is not a plan and an interval.", "Send a plan and how often it bills."),
        )

    private fun ResponsesConfig.providerUnavailable() = syncError(HttpStatusCode.BadGateway, providerUnavailable)

    private fun ResponsesConfig.billingUnavailable() = syncError(HttpStatusCode.NotFound, billingUnavailable)

    val getSubscription: RouteConfig.() -> Unit = {
        bearerOperation(
            "getCloudSubscription",
            ApiTags.BILLING,
            "Get the plan and how it is paid for",
            """
            The account's LogDate Cloud plan as billing sees it: which plan, whether it costs anything, when it
            renews or stops, how much storage is used, and how many steps of extra storage that takes.

            `purchasesAvailable` says whether this server can start, change or cancel a plan at all. When it
            is `false`, show the plan but no purchase action. Which plans can be bought is in the plan list:
            before launch that is Plus alone. An account that joined during the beta has
            `betaMember: true` and keeps LogDate Cloud at no charge; `includedWithBeta` is `true` while that is
            the plan it is on.

            A self-hosted server without billing answers every billing endpoint with `404 BILLING_UNAVAILABLE`.
            """,
        )
        response {
            ok("The account's plan and billing state.", plusSubscription)
            bearerUnauthorized(ErrorEnvelope.SYNC)
            billingUnavailable()
        }
    }

    val refreshSubscription: RouteConfig.() -> Unit = {
        bearerOperation(
            "refreshCloudSubscription",
            ApiTags.BILLING,
            "Read the plan back from the payment provider",
            """
            Reads the account's subscriptions from the payment provider and answers with the result. Call it
            when the person comes back from a checkout or payment page: the provider also notifies the server by
            itself, but this does not depend on that notification having arrived.

            Paying is what changes the plan. A checkout that was opened and abandoned changes nothing.
            """,
        )
        response {
            ok("The account's plan and billing state, as the provider has it now.", plusSubscription)
            bearerUnauthorized(ErrorEnvelope.SYNC)
            billingUnavailable()
            providerUnavailable()
        }
    }

    val startCheckout: RouteConfig.() -> Unit = {
        bearerOperation(
            "startCloudCheckout",
            ApiTags.BILLING,
            "Start buying a plan",
            """
            Opens a checkout page at the payment provider for an account that pays for nothing yet, and answers
            with its address. Open the address in a browser. When the person finishes, call **Read the plan back
            from the payment provider**.

            Take `planId` and the interval from **List the plans on offer**, and only offer a plan whose
            `onSale` is `true`. An account that already pays for a plan changes it with **Change the plan**
            instead; a second checkout is refused so nobody ends up paying twice.
            """,
        )
        request { planSelection() }
        response {
            ok("The checkout page to open.", BillingPage(url = "https://checkout.stripe.com/c/pay/cs_live_a1B2c3"))
            invalidSelection()
            bearerUnauthorized(ErrorEnvelope.SYNC)
            syncError(
                HttpStatusCode.Conflict,
                notOnSale,
                unknownPlan,
                includedWithBeta,
                ErrorCase(
                    "ALREADY_SUBSCRIBED",
                    "The account already pays for a plan. Use **Change the plan** to move it.",
                    "This account already has a plan. Change it instead.",
                ),
            )
            billingUnavailable()
            providerUnavailable()
        }
    }

    val changePlan: RouteConfig.() -> Unit = {
        bearerOperation(
            "changeCloudPlan",
            ApiTags.BILLING,
            "Change the plan",
            """
            Moves a paid subscription to another plan or billing interval and answers with the result. Moving
            to a larger plan charges the difference for the rest of the period straight away; moving to a
            smaller one leaves a credit that the next invoice uses.

            Moving to a plan that holds less than the account stores is refused with `STORAGE_EXCEEDS_PLAN`;
            `details.usedBytes` and `details.includedBytes` give the numbers to show.
            """,
        )
        request { planSelection() }
        response {
            ok("The account's plan and billing state after the change.", plusSubscription)
            invalidSelection()
            bearerUnauthorized(ErrorEnvelope.SYNC)
            syncError(
                HttpStatusCode.Conflict,
                notOnSale,
                unknownPlan,
                noSubscription,
                includedWithBeta,
                ErrorCase(
                    "STORAGE_EXCEEDS_PLAN",
                    "The account stores more than the chosen plan holds. Say how much, and that space must be freed first.",
                    "This account stores more than that plan holds.",
                ),
                details = mapOf("usedBytes" to "75161927680", "includedBytes" to "53687091200"),
            )
            billingUnavailable()
            providerUnavailable()
        }
    }

    val cancelSubscription: RouteConfig.() -> Unit = {
        bearerOperation(
            "cancelCloudSubscription",
            ApiTags.BILLING,
            "Cancel the plan",
            """
            Sets the paid subscription to stop at the end of what has been paid for and answers with the result,
            whose `endsAtEpochSeconds` is when it stops. The plan keeps working until then, nothing is deleted
            afterwards, and **Keep the plan** undoes it while it has not yet stopped.

            When it stops, an account that joined during the beta goes back to LogDate Cloud at no charge. Any
            other account has no plan and can store nothing new until it buys one.
            """,
        )
        response {
            ok(
                "The account's plan and billing state, now with an end date.",
                plusSubscription.copy(renewsAtEpochSeconds = null, endsAtEpochSeconds = 1_800_000_000),
            )
            bearerUnauthorized(ErrorEnvelope.SYNC)
            syncError(HttpStatusCode.Conflict, notOnSale, noSubscription)
            billingUnavailable()
            providerUnavailable()
        }
    }

    val keepSubscription: RouteConfig.() -> Unit = {
        bearerOperation(
            "keepCloudSubscription",
            ApiTags.BILLING,
            "Keep the plan",
            "Undoes **Cancel the plan** for a subscription that has not yet stopped, so it renews as before.",
        )
        response {
            ok("The account's plan and billing state, renewing again.", plusSubscription)
            bearerUnauthorized(ErrorEnvelope.SYNC)
            syncError(HttpStatusCode.Conflict, notOnSale, noSubscription)
            billingUnavailable()
            providerUnavailable()
        }
    }

    val openPaymentDetails: RouteConfig.() -> Unit = {
        bearerOperation(
            "openCloudPaymentDetails",
            ApiTags.BILLING,
            "Open payment details",
            """
            Answers with the address of the payment provider's page for this account, where the person can
            change their card and read their receipts. Open it in a browser. Offer it only when the subscription
            says `paymentDetailsAvailable`.
            """,
        )
        response {
            ok("The page to open.", BillingPage(url = "https://billing.stripe.com/p/session/live_a1B2c3"))
            bearerUnauthorized(ErrorEnvelope.SYNC)
            syncError(HttpStatusCode.Conflict, notOnSale, noSubscription)
            billingUnavailable()
            providerUnavailable()
        }
    }

    val returnPage: RouteConfig.() -> Unit = {
        publicOperation(
            "getBillingReturnPage",
            ApiTags.BILLING,
            "Show the page a browser returns to",
            """
            The web page the payment provider sends the browser to after a checkout or payment page. It tells
            the person to go back to LogDate and nothing else: it grants nothing and carries no account data.
            `result=cancelled` words it for a checkout that was abandoned.
            """,
        )
        request {
            queryParameter<String>("result") {
                description = "`done` after a finished page, `cancelled` after an abandoned checkout."
                required = false
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "An HTML page."
                body<String> { mediaTypes(io.ktor.http.ContentType.Text.Html) }
            }
        }
    }

    val stripeWebhook: RouteConfig.() -> Unit = {
        publicOperation(
            "receiveStripeWebhook",
            ApiTags.BILLING,
            "Receive a Stripe notification",
            """
            Called by Stripe, not by clients. Stripe posts an event here whenever a subscription starts,
            changes or ends, signed in the `Stripe-Signature` header; an event whose signature does not verify
            is refused and changes nothing. The server then reads the subscription from Stripe and brings the
            account's plan in line with it. A server not configured for Stripe answers `404`.
            """,
        )
        response {
            ok("The event was applied, or needed nothing.", StripeWebhookReceipt(received = true))
            syncError(
                HttpStatusCode.BadRequest,
                ErrorCase(
                    "INVALID_WEBHOOK",
                    "The signature did not verify, or the body is not an event.",
                    "The webhook could not be verified.",
                ),
            )
            syncError(HttpStatusCode.NotFound, billingUnavailable)
            syncError(HttpStatusCode.ServiceUnavailable, providerUnavailable)
        }
    }
}
