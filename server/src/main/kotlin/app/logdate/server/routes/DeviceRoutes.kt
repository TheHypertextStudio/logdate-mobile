package app.logdate.server.routes

import app.logdate.server.auth.AccessCheck
import app.logdate.server.auth.AccessTokenClaims
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.bearerToken
import app.logdate.server.auth.sha256
import app.logdate.server.clientAddress
import app.logdate.server.devices.DeviceLinkMint
import app.logdate.server.devices.DeviceLinkRedemption
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.ratelimit.RateLimitPolicy
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.routes.docs.DeviceLinkDocs
import app.logdate.shared.model.ApiError
import app.logdate.shared.model.ApiErrorResponse
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import app.logdate.shared.model.devices.DeviceLinkRedeemRequest
import app.logdate.shared.model.devices.LinkedDeviceListResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import java.util.UUID

/** Per IP address. A link code has 256 random bits, so this only limits how much guessing traffic reaches the database. */
internal val DEVICE_LINK_REDEEM_RATE_LIMIT = RateLimitPolicy(maxRequests = 10, windowSeconds = 60)

private const val REVOKED_MESSAGE = "This device was removed from the account"
private const val SCOPE_MESSAGE = "A linked device cannot do this"

/**
 * `/auth/devices`: a signed-in phone links another device with a one-time code, lists linked devices and
 * removes them; a linked device can remove itself.
 */
internal fun Route.deviceRoutes(
    linkService: DeviceLinkService,
    verifier: AccessTokenVerifier,
    rateLimiter: SlidingWindowRateLimiter = SlidingWindowRateLimiter(),
) {
    route("/auth/devices") {
        createLinkRoute(linkService, verifier)
        redeemLinkRoute(linkService, rateLimiter)
        listDevicesRoute(linkService, verifier)
        unlinkSelfRoute(linkService, verifier)
        revokeDeviceRoute(linkService, verifier)
    }
}

private fun Route.createLinkRoute(
    linkService: DeviceLinkService,
    verifier: AccessTokenVerifier,
) {
    post("/link", DeviceLinkDocs.createLink) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val claims = call.requireAccess(verifier, allowSyncScope = false) ?: return@post
        val accountId = call.accountUuid(claims) ?: return@post
        when (val minted = linkService.mint(accountId)) {
            is DeviceLinkMint.Minted -> {
                call.respond(HttpStatusCode.OK, minted.code)
            }

            DeviceLinkMint.DeviceLimitReached -> {
                call.respondDeviceError(
                    HttpStatusCode.Conflict,
                    DeviceLinkErrorCodes.DEVICE_LIMIT_REACHED,
                    "This account already has ${DeviceLinkService.MAX_ACTIVE_DEVICES} linked devices",
                )
            }

            DeviceLinkMint.RateLimited -> {
                call.respondDeviceError(
                    HttpStatusCode.TooManyRequests,
                    DeviceLinkErrorCodes.DEVICE_LINK_RATE_LIMITED,
                    "Too many link codes this hour",
                )
            }
        }
    }
}

private fun Route.redeemLinkRoute(
    linkService: DeviceLinkService,
    rateLimiter: SlidingWindowRateLimiter,
) {
    post("/link/redeem", DeviceLinkDocs.redeemLink) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        val address =
            call
                .clientAddress()
                .ifBlank { "unknown" }
                .sha256()
        if (!rateLimiter.allow("device-link-redeem:$address", DEVICE_LINK_REDEEM_RATE_LIMIT)) {
            return@post call.respondDeviceError(
                HttpStatusCode.TooManyRequests,
                "RATE_LIMIT_EXCEEDED",
                "Too many requests. Please retry later.",
            )
        }
        val request =
            call.receiveRedeemRequest()
                ?: return@post call.respondDeviceError(HttpStatusCode.BadRequest, "INVALID_REQUEST", "Request body is invalid")
        when (val redeemed = linkService.redeem(request)) {
            is DeviceLinkRedemption.Redeemed -> {
                call.respond(HttpStatusCode.OK, redeemed.response)
            }

            DeviceLinkRedemption.InvalidCode -> {
                call.respondDeviceError(
                    HttpStatusCode.BadRequest,
                    DeviceLinkErrorCodes.LINK_CODE_INVALID,
                    "The link code is invalid, expired, or already used",
                )
            }

            DeviceLinkRedemption.InvalidDevice -> {
                call.respondDeviceError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Device name or platform is invalid")
            }

            DeviceLinkRedemption.DeviceLimitReached -> {
                call.respondDeviceError(
                    HttpStatusCode.Conflict,
                    DeviceLinkErrorCodes.DEVICE_LIMIT_REACHED,
                    "This account already has ${DeviceLinkService.MAX_ACTIVE_DEVICES} linked devices",
                )
            }
        }
    }
}

private fun Route.listDevicesRoute(
    linkService: DeviceLinkService,
    verifier: AccessTokenVerifier,
) {
    get(DeviceLinkDocs.listDevices) {
        val claims = call.requireAccess(verifier, allowSyncScope = true) ?: return@get
        val accountId = call.accountUuid(claims) ?: return@get
        call.respond(LinkedDeviceListResponse(linkService.list(accountId, claims.sessionId)))
    }
}

private fun Route.unlinkSelfRoute(
    linkService: DeviceLinkService,
    verifier: AccessTokenVerifier,
) {
    delete("/self", DeviceLinkDocs.unlinkSelf) {
        val claims = call.requireAccess(verifier, allowSyncScope = true) ?: return@delete
        val accountId = call.accountUuid(claims) ?: return@delete
        val sessionId =
            claims.sessionId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respondDeviceError(
                    HttpStatusCode.BadRequest,
                    DeviceLinkErrorCodes.NOT_A_LINKED_DEVICE,
                    "This token does not belong to a linked device",
                )
        linkService.revoke(accountId, sessionId)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.revokeDeviceRoute(
    linkService: DeviceLinkService,
    verifier: AccessTokenVerifier,
) {
    delete("/{id}", DeviceLinkDocs.revokeDevice) {
        val claims = call.requireAccess(verifier, allowSyncScope = false) ?: return@delete
        val accountId = call.accountUuid(claims) ?: return@delete
        val sessionId =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respondDeviceError(HttpStatusCode.BadRequest, "VALIDATION_ERROR", "Device ID must be a UUID")
        if (!linkService.revoke(accountId, sessionId)) {
            return@delete call.respondDeviceError(HttpStatusCode.NotFound, DeviceLinkErrorCodes.DEVICE_NOT_FOUND, "Linked device not found")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private suspend fun ApplicationCall.requireAccess(
    verifier: AccessTokenVerifier,
    allowSyncScope: Boolean,
): AccessTokenClaims? {
    val token = bearerToken(request.header(HttpHeaders.Authorization))
    if (token.isNullOrBlank()) {
        respondDeviceError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Access token is required")
        return null
    }
    when (val check = verifier.verify(token, allowSyncScope)) {
        is AccessCheck.Granted -> {
            return check.claims
        }

        AccessCheck.SessionRevoked -> {
            respondDeviceError(HttpStatusCode.Unauthorized, DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED, REVOKED_MESSAGE)
        }

        AccessCheck.ScopeDenied -> {
            respondDeviceError(HttpStatusCode.Forbidden, DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED, SCOPE_MESSAGE)
        }

        AccessCheck.Missing, AccessCheck.Invalid -> {
            respondDeviceError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Invalid or expired access token")
        }
    }
    return null
}

private suspend fun ApplicationCall.accountUuid(claims: AccessTokenClaims): UUID? {
    val accountId = runCatching { UUID.fromString(claims.accountId) }.getOrNull()
    if (accountId == null) respondDeviceError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Invalid or expired access token")
    return accountId
}

private suspend fun ApplicationCall.receiveRedeemRequest(): DeviceLinkRedeemRequest? =
    try {
        receive<DeviceLinkRedeemRequest>()
    } catch (_: BadRequestException) {
        null
    } catch (_: ContentTransformationException) {
        null
    } catch (_: ContentConvertException) {
        null
    } catch (_: SerializationException) {
        null
    }

private suspend fun ApplicationCall.respondDeviceError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    respond(status, ApiErrorResponse(ApiError(code, message)))
}
