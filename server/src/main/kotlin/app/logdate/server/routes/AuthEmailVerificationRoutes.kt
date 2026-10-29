@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.EmailVerificationService
import app.logdate.server.auth.TokenService
import app.logdate.server.entitlements.EntitlementService
import app.logdate.server.routes.docs.AccountDocs
import app.logdate.shared.model.BeginEmailVerificationResponse
import app.logdate.shared.model.CompleteEmailVerificationRequest
import app.logdate.shared.model.EmailVerificationConflictResponse
import app.logdate.shared.model.EmailVerificationErrorResponse
import app.logdate.shared.model.EmailVerifiedResponse
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal fun Route.registerEmailVerificationBeginRoute(
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
    entitlementService: EntitlementService?,
    emailVerificationService: EmailVerificationService?,
) {
    post("/me/email/verify/begin", AccountDocs.beginEmailVerification) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@post
            val service =
                emailVerificationService ?: return@post call.respondApiError(
                    HttpStatusCode.NotImplemented,
                    "EMAIL_VERIFICATION_UNAVAILABLE",
                    "Email verification is not configured on this server",
                    metrics,
                )
            if (!isEmailVerificationFeatureEnabled(account.id, entitlementService)) {
                return@post call.respondApiError(
                    HttpStatusCode.NotFound,
                    "FEATURE_DISABLED",
                    "Email verification is not enabled for this account",
                    metrics,
                )
            }
            val challenge = service.begin(account.id)
            call.respond(
                HttpStatusCode.OK,
                BeginEmailVerificationResponse(
                    transactionId = challenge.transactionId.toString(),
                    nonce = challenge.nonce,
                    audience = EMAIL_VERIFICATION_AUDIENCE,
                ),
            )
        } catch (e: Exception) {
            Napier.e("Failed to begin email verification", e)
            call.respondForRequestException(e, "Failed to begin email verification", metrics)
        }
    }
}

internal fun Route.registerEmailVerificationCompleteRoute(
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry,
    entitlementService: EntitlementService?,
    emailVerificationService: EmailVerificationService?,
) {
    post("/me/email/verify/complete", AccountDocs.completeEmailVerification) {
        try {
            val account =
                resolveAuthenticatedAccount(call, accountRepository, tokenService, metrics)
                    ?: return@post
            val service =
                emailVerificationService ?: return@post call.respondApiError(
                    HttpStatusCode.NotImplemented,
                    "EMAIL_VERIFICATION_UNAVAILABLE",
                    "Email verification is not configured on this server",
                    metrics,
                )
            if (!isEmailVerificationFeatureEnabled(account.id, entitlementService)) {
                return@post call.respondApiError(
                    HttpStatusCode.NotFound,
                    "FEATURE_DISABLED",
                    "Email verification is not enabled for this account",
                    metrics,
                )
            }
            val request = call.receive<CompleteEmailVerificationRequest>()
            val txId =
                runCatching { Uuid.parse(request.transactionId) }.getOrNull()
                    ?: return@post call.respondApiError(
                        HttpStatusCode.BadRequest,
                        "INVALID_TRANSACTION_ID",
                        "Malformed transaction id",
                        metrics,
                    )
            when (val result = service.complete(account.id, txId, request.credentialJson)) {
                is EmailVerificationService.Result.Success ->
                    call.respond(
                        HttpStatusCode.OK,
                        EmailVerifiedResponse(
                            email = result.email,
                            emailVerifiedAt = result.emailVerifiedAt,
                        ),
                    )
                is EmailVerificationService.Result.Conflict ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        EmailVerificationConflictResponse(
                            message = "This email is already attached to another LogDate account.",
                        ),
                    )
                is EmailVerificationService.Result.Failed ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        EmailVerificationErrorResponse(reason = result.reason),
                    )
            }
        } catch (e: Exception) {
            Napier.e("Failed to complete email verification", e)
            call.respondForRequestException(e, "Failed to complete email verification", metrics)
        }
    }
}

/** Audience identifier the holder Key Binding JWT must declare. Surfaced to
 * clients via the /begin response so they don't need to hardcode it. */
private const val EMAIL_VERIFICATION_AUDIENCE = "https://logdate.app/auth/email"

/**
 * Returns true when the email_verification entitlement flag is on for [accountId].
 * Falls open (true) when no [entitlementService] is wired — matching the self-host
 * default exposed by [GET /me/entitlement] for installs without billing.
 */
private suspend fun isEmailVerificationFeatureEnabled(
    accountId: Uuid,
    entitlementService: EntitlementService?,
): Boolean {
    val service = entitlementService ?: return true
    return try {
        service.resolve(java.util.UUID.fromString(accountId.toString())).features["email_verification"] == true
    } catch (e: Exception) {
        io.github.aakira.napier.Napier
            .w("Failed to resolve email_verification entitlement; treating as disabled", e)
        false
    }
}
