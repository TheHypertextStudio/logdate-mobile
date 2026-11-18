package app.logdate.server.devices

import java.util.UUID

/** Codes created more than this long ago no longer count toward the hourly limit and may be deleted. */
internal const val LINK_CODE_RATE_WINDOW_MS = 60 * 60 * 1_000L

/** A linked device's revocable session. Times are epoch milliseconds. */
data class DeviceSession(
    val id: UUID,
    val accountId: UUID,
    val deviceName: String,
    val platform: String,
    val createdAt: Long,
    val lastRefreshedAt: Long,
    val expiresAt: Long,
    val revokedAt: Long? = null,
) {
    fun isActive(now: Long): Boolean = revokedAt == null && expiresAt > now
}

/** A one-time link code, identified only by the SHA-256 hash of its bytes. */
data class DeviceLinkCode(
    val codeHash: String,
    val accountId: UUID,
    val createdAt: Long,
    val expiresAt: Long,
)

/** The session a redeemed link code creates; its account comes from the code. */
data class NewDeviceSession(
    val id: UUID,
    val deviceName: String,
    val platform: String,
    val createdAt: Long,
    val expiresAt: Long,
)

/**
 * A session unused for [idleAfterMs] (judged by its last refresh) doesn't hold one of the
 * [maxActiveSessions] places. It stays signed in, and takes a place again once it is used.
 */
data class DeviceSessionLimits(
    val maxActiveSessions: Int,
    val maxCodesPerHour: Int,
    val idleAfterMs: Long = Long.MAX_VALUE,
) {
    fun isIdle(
        session: DeviceSession,
        now: Long,
    ): Boolean = now - session.lastRefreshedAt >= idleAfterMs
}

enum class LinkCodeCreation {
    CREATED,
    DEVICE_LIMIT_REACHED,
    RATE_LIMITED,
}

sealed interface LinkCodeRedemption {
    data class Redeemed(
        val session: DeviceSession,
    ) : LinkCodeRedemption

    data object Invalid : LinkCodeRedemption

    data object DeviceLimitReached : LinkCodeRedemption
}

interface DeviceSessionRepository {
    /**
     * Stores [code] unless its account already has [DeviceSessionLimits.maxActiveSessions] active sessions in use or
     * minted [DeviceSessionLimits.maxCodesPerHour] codes in the last hour.
     */
    suspend fun createLinkCode(
        code: DeviceLinkCode,
        limits: DeviceSessionLimits,
    ): LinkCodeCreation

    /**
     * Spends the unexpired, unspent code with [codeHash] and creates [session] for its account in one step:
     * of any number of concurrent calls with one code, exactly one returns [LinkCodeRedemption.Redeemed].
     * Only sessions in use count toward [DeviceSessionLimits.maxActiveSessions]; idle ones are never revoked for room.
     */
    suspend fun redeemLinkCode(
        codeHash: String,
        session: NewDeviceSession,
        limits: DeviceSessionLimits,
    ): LinkCodeRedemption

    suspend fun find(sessionId: UUID): DeviceSession?

    suspend fun listActive(
        accountId: UUID,
        now: Long,
    ): List<DeviceSession>

    /** Marks the session revoked. True when [accountId] owns the session, including one already revoked. */
    suspend fun revoke(
        accountId: UUID,
        sessionId: UUID,
        now: Long,
    ): Boolean

    /** Moves an unrevoked session's expiry to [expiresAt]; false when the session is gone or revoked. */
    suspend fun extend(
        sessionId: UUID,
        expiresAt: Long,
        now: Long,
    ): Boolean

    /**
     * Records that an unrevoked session was used at [now] without touching its expiry, so it can't undo a renewal
     * that commits at the same time; false when the session is gone or revoked.
     */
    suspend fun touch(
        sessionId: UUID,
        now: Long,
    ): Boolean
}

class InMemoryDeviceSessionRepository : DeviceSessionRepository {
    private data class StoredCode(
        val code: DeviceLinkCode,
        val redeemedAt: Long? = null,
    )

    private val lock = Any()
    private val codes = mutableMapOf<String, StoredCode>()
    private val sessions = mutableMapOf<UUID, DeviceSession>()

    override suspend fun createLinkCode(
        code: DeviceLinkCode,
        limits: DeviceSessionLimits,
    ): LinkCodeCreation =
        synchronized(lock) {
            val windowStart = code.createdAt - LINK_CODE_RATE_WINDOW_MS
            codes.values.removeAll { it.code.expiresAt < windowStart }
            if (inUseCount(code.accountId, code.createdAt, limits) >= limits.maxActiveSessions) {
                return@synchronized LinkCodeCreation.DEVICE_LIMIT_REACHED
            }
            val recent = codes.values.count { it.code.accountId == code.accountId && it.code.createdAt > windowStart }
            if (recent >= limits.maxCodesPerHour) return@synchronized LinkCodeCreation.RATE_LIMITED
            codes[code.codeHash] = StoredCode(code)
            LinkCodeCreation.CREATED
        }

    override suspend fun redeemLinkCode(
        codeHash: String,
        session: NewDeviceSession,
        limits: DeviceSessionLimits,
    ): LinkCodeRedemption =
        synchronized(lock) {
            val now = session.createdAt
            val stored =
                codes[codeHash]?.takeIf { it.redeemedAt == null && it.code.expiresAt > now }
                    ?: return@synchronized LinkCodeRedemption.Invalid
            if (inUseCount(stored.code.accountId, now, limits) >= limits.maxActiveSessions) {
                return@synchronized LinkCodeRedemption.DeviceLimitReached
            }
            codes[codeHash] = stored.copy(redeemedAt = now)
            val created =
                DeviceSession(
                    id = session.id,
                    accountId = stored.code.accountId,
                    deviceName = session.deviceName,
                    platform = session.platform,
                    createdAt = now,
                    lastRefreshedAt = now,
                    expiresAt = session.expiresAt,
                )
            sessions[created.id] = created
            LinkCodeRedemption.Redeemed(created)
        }

    override suspend fun find(sessionId: UUID): DeviceSession? = synchronized(lock) { sessions[sessionId] }

    override suspend fun listActive(
        accountId: UUID,
        now: Long,
    ): List<DeviceSession> =
        synchronized(lock) {
            sessions.values.filter { it.accountId == accountId && it.isActive(now) }.sortedBy(DeviceSession::createdAt)
        }

    override suspend fun revoke(
        accountId: UUID,
        sessionId: UUID,
        now: Long,
    ): Boolean =
        synchronized(lock) {
            val existing = sessions[sessionId]?.takeIf { it.accountId == accountId } ?: return@synchronized false
            if (existing.revokedAt == null) sessions[sessionId] = existing.copy(revokedAt = now)
            true
        }

    override suspend fun extend(
        sessionId: UUID,
        expiresAt: Long,
        now: Long,
    ): Boolean =
        synchronized(lock) {
            val existing = sessions[sessionId]?.takeIf { it.revokedAt == null } ?: return@synchronized false
            sessions[sessionId] = existing.copy(expiresAt = expiresAt, lastRefreshedAt = now)
            true
        }

    override suspend fun touch(
        sessionId: UUID,
        now: Long,
    ): Boolean =
        synchronized(lock) {
            val existing = sessions[sessionId]?.takeIf { it.revokedAt == null } ?: return@synchronized false
            sessions[sessionId] = existing.copy(lastRefreshedAt = now)
            true
        }

    private fun inUseCount(
        accountId: UUID,
        now: Long,
        limits: DeviceSessionLimits,
    ): Int = sessions.values.count { it.accountId == accountId && it.isActive(now) && !limits.isIdle(it, now) }
}
