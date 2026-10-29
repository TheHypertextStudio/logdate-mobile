@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AccessCheck
import app.logdate.server.auth.AccessTokenClaims
import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.auth.TokenScope
import app.logdate.server.auth.TokenService
import app.logdate.server.auth.bearerToken
import app.logdate.server.auth.hasSupportedSessionIdentity
import app.logdate.server.devices.DeviceLinkService
import app.logdate.server.devices.DeviceSessionRefresh
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal suspend fun resolveAuthenticatedAccount(
    call: ApplicationCall,
    accountRepository: AccountRepository,
    tokenService: TokenService,
    metrics: AuthMetricsRegistry?,
): Account? {
    val authHeader = call.request.header(HttpHeaders.Authorization)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Access token is required", metrics)
        return null
    }

    val token = authHeader.removePrefix("Bearer ").trim()
    if (token.isBlank()) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Access token is required", metrics)
        return null
    }

    val accountId = tokenService.validateAccessToken(token)
    if (accountId == null) {
        if (tokenService.decodeAccessToken(token)?.scope == TokenScope.SYNC) {
            call.respondApiError(
                HttpStatusCode.Forbidden,
                DeviceLinkErrorCodes.DEVICE_SCOPE_DENIED,
                "A linked device cannot do this",
                metrics,
            )
            return null
        }
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Invalid or expired access token", metrics)
        return null
    }

    val account = accountRepository.findById(Uuid.parse(accountId))
    if (account == null) {
        call.respondApiError(HttpStatusCode.NotFound, "ACCOUNT_NOT_FOUND", "Account not found", metrics)
        return null
    }

    if (!account.hasSupportedSessionIdentity()) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "This account cannot start a session", metrics)
        return null
    }
    return account
}

/** Like [resolveAuthenticatedAccount], but also accepts a linked device while its session is active. */
internal suspend fun resolveAuthenticatedSession(
    call: ApplicationCall,
    accountRepository: AccountRepository,
    verifier: AccessTokenVerifier,
    metrics: AuthMetricsRegistry?,
): Pair<Account, AccessTokenClaims>? {
    val token = bearerToken(call.request.header(HttpHeaders.Authorization))
    if (token.isNullOrBlank()) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Access token is required", metrics)
        return null
    }
    val claims =
        when (val check = verifier.verify(token, allowSyncScope = true)) {
            is AccessCheck.Granted -> check.claims
            AccessCheck.SessionRevoked -> {
                call.respondApiError(
                    HttpStatusCode.Unauthorized,
                    DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
                    "This device was removed from the account",
                    metrics,
                )
                return null
            }
            AccessCheck.Missing, AccessCheck.Invalid, AccessCheck.ScopeDenied -> {
                call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "Invalid or expired access token", metrics)
                return null
            }
        }
    val account = runCatching { Uuid.parse(claims.accountId) }.getOrNull()?.let { accountRepository.findById(it) }
    if (account == null) {
        call.respondApiError(HttpStatusCode.NotFound, "ACCOUNT_NOT_FOUND", "Account not found", metrics)
        return null
    }
    if (!account.hasSupportedSessionIdentity()) {
        call.respondApiError(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "This account cannot start a session", metrics)
        return null
    }
    return account to claims
}

/** Refreshes a linked device's tokens, or answers why it cannot. Returns whether it answered 200. */
internal suspend fun ApplicationCall.respondDeviceRefresh(
    refreshToken: String,
    tokenService: TokenService,
    accountRepository: AccountRepository,
    deviceLinkService: DeviceLinkService?,
    metrics: AuthMetricsRegistry,
): Boolean {
    val claims = tokenService.decodeRefreshToken(refreshToken)?.takeIf { it.sessionId != null }
    if (claims == null) {
        respondApiError(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "Invalid or expired refresh token", metrics)
        return false
    }
    val account = Uuid.parseOrNull(claims.accountId)?.let { accountRepository.findById(it) }
    if (account == null || !account.hasSupportedSessionIdentity()) {
        respondApiError(HttpStatusCode.Unauthorized, "INVALID_REFRESH_TOKEN", "This account cannot start a session", metrics)
        return false
    }
    when (val refreshed = deviceLinkService?.refresh(claims.copy(did = account.did)) ?: DeviceSessionRefresh.Revoked) {
        DeviceSessionRefresh.Revoked -> {
            respondApiError(
                HttpStatusCode.Unauthorized,
                DeviceLinkErrorCodes.DEVICE_SESSION_REVOKED,
                "This device was removed from the account",
                metrics,
            )
            return false
        }
        is DeviceSessionRefresh.Refreshed ->
            respond(HttpStatusCode.OK, RefreshTokenResponseV1(true, RefreshTokenDataV1(refreshed.accessToken, refreshed.refreshToken)))
    }
    return true
}
