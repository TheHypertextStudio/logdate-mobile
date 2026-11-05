package app.logdate.server.auth

import app.logdate.server.devices.DeviceSessionGuard

/** The outcome of checking an access token for one route. */
sealed interface AccessCheck {
    data class Granted(
        val claims: AccessTokenClaims,
    ) : AccessCheck

    /** No `Authorization: Bearer` header. */
    data object Missing : AccessCheck

    /** The token is malformed, expired, signed by someone else, or a refresh token. */
    data object Invalid : AccessCheck

    /** A linked device token whose session was removed, expired, or is unknown to this server. */
    data object SessionRevoked : AccessCheck

    /** A valid linked device token on a route only a signed-in account may use. */
    data object ScopeDenied : AccessCheck
}

/**
 * Turns a bearer token into granted claims or a reason to refuse it.
 *
 * Full-access tokens pass everywhere. Linked device tokens (`scp=sync`) pass only where the route sets
 * `allowSyncScope`, and only while [guard] says their session is active.
 */
class AccessTokenVerifier(
    private val tokenService: TokenService,
    private val guard: DeviceSessionGuard,
) {
    suspend fun verify(
        token: String?,
        allowSyncScope: Boolean,
    ): AccessCheck {
        if (token == null) return AccessCheck.Missing
        val claims = tokenService.decodeAccessToken(token) ?: return AccessCheck.Invalid
        if (claims.scope == TokenScope.FULL) {
            return if (claims.sessionId == null) AccessCheck.Granted(claims) else AccessCheck.Invalid
        }
        val sessionId = claims.sessionId
        if (claims.scope != TokenScope.SYNC || sessionId == null) return AccessCheck.Invalid
        if (!allowSyncScope) return AccessCheck.ScopeDenied
        if (!guard.isActive(claims.accountId, sessionId)) return AccessCheck.SessionRevoked
        return AccessCheck.Granted(claims)
    }
}

/** The token from an `Authorization: Bearer <token>` header, or null when there is no such header. */
internal fun bearerToken(authorizationHeader: String?): String? =
    authorizationHeader
        ?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")
        ?.trim()
