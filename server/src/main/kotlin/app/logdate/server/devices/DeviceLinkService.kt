package app.logdate.server.devices

import app.logdate.server.auth.AccessTokenClaims
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.crypto.sha256Hex
import app.logdate.server.database.toJavaUUID
import app.logdate.server.database.toKotlinUuid
import app.logdate.shared.model.AccountTokens
import app.logdate.shared.model.devices.DeviceLinkCodeResponse
import app.logdate.shared.model.devices.DeviceLinkRedeemRequest
import app.logdate.shared.model.devices.DeviceLinkRedeemResponse
import app.logdate.shared.model.devices.LinkedDevice
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

sealed interface DeviceLinkMint {
    data class Minted(
        val code: DeviceLinkCodeResponse,
    ) : DeviceLinkMint

    data object DeviceLimitReached : DeviceLinkMint

    data object RateLimited : DeviceLinkMint
}

sealed interface DeviceLinkRedemption {
    data class Redeemed(
        val response: DeviceLinkRedeemResponse,
    ) : DeviceLinkRedemption

    data object InvalidCode : DeviceLinkRedemption

    data object InvalidDevice : DeviceLinkRedemption

    data object DeviceLimitReached : DeviceLinkRedemption
}

sealed interface DeviceSessionRefresh {
    /** [refreshToken] is a new 30-day refresh token, present only when the session was just renewed. */
    data class Refreshed(
        val accessToken: String,
        val refreshToken: String?,
    ) : DeviceSessionRefresh

    data object Revoked : DeviceSessionRefresh
}

/**
 * Links devices to an account without a sign-in on the device.
 *
 * A signed-in phone mints a one-time code and hands it to the device; the device redeems it for its own
 * session, whose tokens carry `sid` and `scp=sync`. A code works once and only for
 * [LINK_CODE_LIFETIME_MS]; the store saves only its SHA-256 hash. An account may have at most
 * [MAX_ACTIVE_DEVICES] active sessions and mints at most [MAX_LINK_CODES_PER_HOUR] codes an hour. A
 * session lasts [SESSION_LIFETIME_MS] and is renewed by a refresh that arrives with less than
 * [SESSION_RENEWAL_WINDOW_MS] left.
 */
@OptIn(ExperimentalUuidApi::class)
class DeviceLinkService(
    private val repository: DeviceSessionRepository,
    private val tokenService: TokenService,
    private val accountRepository: AccountRepository,
    private val guard: DeviceSessionGuard,
    private val clock: Clock = Clock.System,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun mint(accountId: UUID): DeviceLinkMint {
        val bytes = ByteArray(LINK_CODE_BYTES).also(random::nextBytes)
        val now = nowMillis()
        val code = DeviceLinkCode(hashLinkCode(bytes), accountId, now, now + LINK_CODE_LIFETIME_MS)
        return when (repository.createLinkCode(code, LIMITS)) {
            LinkCodeCreation.CREATED -> DeviceLinkMint.Minted(DeviceLinkCodeResponse(encodeLinkCode(bytes), code.expiresAt))
            LinkCodeCreation.DEVICE_LIMIT_REACHED -> DeviceLinkMint.DeviceLimitReached
            LinkCodeCreation.RATE_LIMITED -> DeviceLinkMint.RateLimited
        }
    }

    suspend fun redeem(request: DeviceLinkRedeemRequest): DeviceLinkRedemption {
        val bytes = decodeLinkCode(request.code) ?: return DeviceLinkRedemption.InvalidCode
        val deviceName = request.deviceName.trim()
        if (!isValidDeviceName(deviceName) || !PLATFORM.matches(request.platform)) return DeviceLinkRedemption.InvalidDevice
        val now = nowMillis()
        val newSession = NewDeviceSession(Uuid.random().toJavaUUID(), deviceName, request.platform, now, now + SESSION_LIFETIME_MS)
        val session =
            when (val outcome = repository.redeemLinkCode(hashLinkCode(bytes), newSession, LIMITS)) {
                is LinkCodeRedemption.Redeemed -> outcome.session
                LinkCodeRedemption.Invalid -> return DeviceLinkRedemption.InvalidCode
                LinkCodeRedemption.DeviceLimitReached -> return DeviceLinkRedemption.DeviceLimitReached
            }
        val account = accountRepository.findById(session.accountId.toKotlinUuid())
        if (account == null) {
            repository.revoke(session.accountId, session.id, now)
            return DeviceLinkRedemption.InvalidCode
        }
        val accountId = session.accountId.toString()
        val sessionId = session.id.toString()
        val tokens =
            AccountTokens(
                accessToken = tokenService.generateDeviceAccessToken(accountId, account.did, sessionId),
                refreshToken = tokenService.generateDeviceRefreshToken(accountId, account.did, sessionId),
            )
        return DeviceLinkRedemption.Redeemed(DeviceLinkRedeemResponse(accountId, sessionId, tokens))
    }

    suspend fun list(
        accountId: UUID,
        currentSessionId: String?,
    ): List<LinkedDevice> =
        repository.listActive(accountId, nowMillis()).map { session ->
            LinkedDevice(
                id = session.id.toString(),
                deviceName = session.deviceName,
                platform = session.platform,
                createdAt = session.createdAt,
                lastRefreshedAt = session.lastRefreshedAt,
                expiresAt = session.expiresAt,
                current = session.id.toString() == currentSessionId,
            )
        }

    suspend fun revoke(
        accountId: UUID,
        sessionId: UUID,
    ): Boolean {
        val revoked = repository.revoke(accountId, sessionId, nowMillis())
        guard.invalidate(sessionId.toString())
        return revoked
    }

    suspend fun refresh(claims: AccessTokenClaims): DeviceSessionRefresh {
        val sessionId = claims.sessionId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return DeviceSessionRefresh.Revoked
        val now = nowMillis()
        val session =
            repository.find(sessionId)?.takeIf { it.isActive(now) && it.accountId.toString() == claims.accountId }
                ?: return DeviceSessionRefresh.Revoked
        val accessToken = tokenService.generateDeviceAccessToken(claims.accountId, claims.did, session.id.toString())
        if (session.expiresAt - now >= SESSION_RENEWAL_WINDOW_MS) return DeviceSessionRefresh.Refreshed(accessToken, refreshToken = null)
        if (!repository.extend(session.id, now + SESSION_LIFETIME_MS, now)) return DeviceSessionRefresh.Revoked
        val refreshToken = tokenService.generateDeviceRefreshToken(claims.accountId, claims.did, session.id.toString())
        return DeviceSessionRefresh.Refreshed(accessToken, refreshToken)
    }

    private fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    companion object {
        const val LINK_CODE_LIFETIME_MS = 120_000L
        const val MAX_ACTIVE_DEVICES = 5
        const val MAX_LINK_CODES_PER_HOUR = 10
        const val SESSION_LIFETIME_MS = 30L * 24 * 60 * 60 * 1_000
        const val SESSION_RENEWAL_WINDOW_MS = 15L * 24 * 60 * 60 * 1_000
        private const val LINK_CODE_BYTES = 32
        private const val MAX_DEVICE_NAME_LENGTH = 120
        private val PLATFORM = Regex("[a-z][a-z0-9_]{0,31}")
        private val LIMITS = DeviceSessionLimits(MAX_ACTIVE_DEVICES, MAX_LINK_CODES_PER_HOUR)

        private fun isValidDeviceName(name: String): Boolean = name.length in 1..MAX_DEVICE_NAME_LENGTH && name.none(Char::isISOControl)
    }
}

internal fun hashLinkCode(bytes: ByteArray): String = sha256Hex(bytes)

private fun encodeLinkCode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun decodeLinkCode(code: String): ByteArray? =
    runCatching { Base64.getUrlDecoder().decode(code) }
        .getOrNull()
        ?.takeIf { it.size == 32 && encodeLinkCode(it) == code }
