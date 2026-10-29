@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.RefreshTokenRevocationRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.auth.hasSupportedSessionIdentity
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.routes.docs.AuthSessionDocs
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

private const val METRIC_AUTH_TOKEN_REFRESH = "auth.token.refresh"

internal fun Route.registerTokenRefreshRoute(
    accountRepository: AccountRepository,
    tokenService: TokenService,
    refreshTokenRevocationRepository: RefreshTokenRevocationRepository,
    metrics: AuthMetricsRegistry,
    deviceLinkService: DeviceLinkService?,
) {
    post("/refresh", AuthSessionDocs.refreshAccessToken) {
        val start = System.currentTimeMillis()
        var success = false
        try {
            val request = call.receive<RefreshTokenRequestV1>()
            if (request.refreshToken.isBlank()) {
                return@post call.respondApiError(
                    HttpStatusCode.Unauthorized,
                    "INVALID_REFRESH_TOKEN",
                    "Refresh token is required",
                    metrics,
                )
            }
            if (refreshTokenRevocationRepository.isRevoked(request.refreshToken)) {
                return@post call.respondApiError(
                    HttpStatusCode.Unauthorized,
                    "REFRESH_TOKEN_REVOKED",
                    "Refresh token has been revoked",
                    metrics,
                )
            }
            val accountId = tokenService.validateRefreshToken(request.refreshToken)
            if (accountId == null) {
                success =
                    call.respondDeviceRefresh(request.refreshToken, tokenService, accountRepository, deviceLinkService, metrics)
                return@post
            }
            val account = Uuid.parseOrNull(accountId)?.let { accountRepository.findById(it) }
            if (account == null || !account.hasSupportedSessionIdentity()) {
                return@post call.respondApiError(
                    HttpStatusCode.Unauthorized,
                    "INVALID_REFRESH_TOKEN",
                    "This account cannot start a session",
                    metrics,
                )
            }
            val accessToken = tokenService.generateAccessToken(accountId, account.did)
            call.respond(HttpStatusCode.OK, RefreshTokenResponseV1(true, RefreshTokenDataV1(accessToken)))
            success = true
        } catch (e: Exception) {
            Napier.e("Failed to refresh token", e)
            call.respondForRequestException(e, "Failed to refresh token", metrics)
        } finally {
            metrics.recordOperation(METRIC_AUTH_TOKEN_REFRESH, System.currentTimeMillis() - start, success)
        }
    }
}

internal fun Route.registerLogoutRoute(
    refreshTokenRevocationRepository: RefreshTokenRevocationRepository,
    metrics: AuthMetricsRegistry,
) {
    post("/logout", AuthSessionDocs.logout) {
        try {
            val request = call.receive<LogoutRequestV1>()
            if (request.refreshToken.isBlank()) {
                return@post call.respondApiError(
                    HttpStatusCode.BadRequest,
                    "INVALID_REFRESH_TOKEN",
                    "Refresh token is required",
                    metrics,
                )
            }
            refreshTokenRevocationRepository.revoke(request.refreshToken)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        } catch (e: Exception) {
            Napier.e("Failed to log out", e)
            call.respondForRequestException(e, "Failed to log out", metrics)
        }
    }
}
