package app.logdate.server.database

import app.logdate.server.devices.DeviceLinkCode
import app.logdate.server.devices.DeviceSession
import app.logdate.server.devices.DeviceSessionLimits
import app.logdate.server.devices.DeviceSessionRepository
import app.logdate.server.devices.LINK_CODE_RATE_WINDOW_MS
import app.logdate.server.devices.LinkCodeCreation
import app.logdate.server.devices.LinkCodeRedemption
import app.logdate.server.devices.NewDeviceSession
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class PostgreSQLDeviceSessionRepository : DeviceSessionRepository {
    override suspend fun createLinkCode(
        code: DeviceLinkCode,
        limits: DeviceSessionLimits,
    ): LinkCodeCreation =
        dbQuery {
            val windowStart = code.createdAt - LINK_CODE_RATE_WINDOW_MS
            DeviceLinkCodesTable.deleteWhere { expiresAt less windowStart }
            if (inUseCount(code.accountId, code.createdAt, limits) >= limits.maxActiveSessions) {
                return@dbQuery LinkCodeCreation.DEVICE_LIMIT_REACHED
            }
            val recent =
                DeviceLinkCodesTable
                    .selectAll()
                    .where { (DeviceLinkCodesTable.accountId eq code.accountId) and (DeviceLinkCodesTable.createdAt greater windowStart) }
                    .count()
                    .toInt()
            if (recent >= limits.maxCodesPerHour) return@dbQuery LinkCodeCreation.RATE_LIMITED
            DeviceLinkCodesTable.insert {
                it[DeviceLinkCodesTable.codeHash] = code.codeHash
                it[DeviceLinkCodesTable.accountId] = code.accountId
                it[DeviceLinkCodesTable.createdAt] = code.createdAt
                it[DeviceLinkCodesTable.expiresAt] = code.expiresAt
            }
            LinkCodeCreation.CREATED
        }

    override suspend fun redeemLinkCode(
        codeHash: String,
        session: NewDeviceSession,
        limits: DeviceSessionLimits,
    ): LinkCodeRedemption =
        dbQuery {
            val now = session.createdAt
            val accountId =
                DeviceLinkCodesTable
                    .selectAll()
                    .where { spendable(codeHash, now) }
                    .singleOrNull()
                    ?.get(DeviceLinkCodesTable.accountId)
                    ?: return@dbQuery LinkCodeRedemption.Invalid
            if (inUseCount(accountId, now, limits) >= limits.maxActiveSessions) {
                return@dbQuery LinkCodeRedemption.DeviceLimitReached
            }
            // The guarded update is what makes a code single use: a concurrent redeemer re-reads
            // redeemed_at after this transaction commits and updates no row.
            val spent =
                DeviceLinkCodesTable.update({ spendable(codeHash, now) }) {
                    it[DeviceLinkCodesTable.redeemedAt] = now
                    it[DeviceLinkCodesTable.sessionId] = session.id
                }
            if (spent != 1) return@dbQuery LinkCodeRedemption.Invalid
            DeviceSessionsTable.insert {
                it[DeviceSessionsTable.id] = session.id
                it[DeviceSessionsTable.accountId] = accountId
                it[DeviceSessionsTable.deviceName] = session.deviceName
                it[DeviceSessionsTable.platform] = session.platform
                it[DeviceSessionsTable.createdAt] = now
                it[DeviceSessionsTable.lastRefreshedAt] = now
                it[DeviceSessionsTable.expiresAt] = session.expiresAt
            }
            LinkCodeRedemption.Redeemed(
                DeviceSession(
                    id = session.id,
                    accountId = accountId,
                    deviceName = session.deviceName,
                    platform = session.platform,
                    createdAt = now,
                    lastRefreshedAt = now,
                    expiresAt = session.expiresAt,
                ),
            )
        }

    override suspend fun find(sessionId: UUID): DeviceSession? =
        dbQuery {
            DeviceSessionsTable
                .selectAll()
                .where { DeviceSessionsTable.id eq sessionId }
                .singleOrNull()
                ?.toDeviceSession()
        }

    override suspend fun listActive(
        accountId: UUID,
        now: Long,
    ): List<DeviceSession> =
        dbQuery {
            DeviceSessionsTable
                .selectAll()
                .where { active(accountId, now) }
                .orderBy(DeviceSessionsTable.createdAt to SortOrder.ASC)
                .map { it.toDeviceSession() }
        }

    override suspend fun revoke(
        accountId: UUID,
        sessionId: UUID,
        now: Long,
    ): Boolean =
        dbQuery {
            DeviceSessionsTable.update({ owned(accountId, sessionId) and DeviceSessionsTable.revokedAt.isNull() }) {
                it[DeviceSessionsTable.revokedAt] = now
            }
            DeviceSessionsTable.selectAll().where { owned(accountId, sessionId) }.any()
        }

    override suspend fun extend(
        sessionId: UUID,
        expiresAt: Long,
        now: Long,
    ): Boolean =
        dbQuery {
            DeviceSessionsTable.update({ (DeviceSessionsTable.id eq sessionId) and DeviceSessionsTable.revokedAt.isNull() }) {
                it[DeviceSessionsTable.expiresAt] = expiresAt
                it[DeviceSessionsTable.lastRefreshedAt] = now
            } == 1
        }

    private fun spendable(
        codeHash: String,
        now: Long,
    ): Op<Boolean> =
        (DeviceLinkCodesTable.codeHash eq codeHash) and
            DeviceLinkCodesTable.redeemedAt.isNull() and
            (DeviceLinkCodesTable.expiresAt greater now)

    private fun active(
        accountId: UUID,
        now: Long,
    ): Op<Boolean> =
        (DeviceSessionsTable.accountId eq accountId) and
            DeviceSessionsTable.revokedAt.isNull() and
            (DeviceSessionsTable.expiresAt greater now)

    private fun owned(
        accountId: UUID,
        sessionId: UUID,
    ): Op<Boolean> = (DeviceSessionsTable.id eq sessionId) and (DeviceSessionsTable.accountId eq accountId)

    private fun inUseCount(
        accountId: UUID,
        now: Long,
        limits: DeviceSessionLimits,
    ): Int =
        DeviceSessionsTable
            .selectAll()
            .where { active(accountId, now) }
            .map { it.toDeviceSession() }
            .count { !limits.isIdle(it, now) }
}

private fun ResultRow.toDeviceSession(): DeviceSession =
    DeviceSession(
        id = this[DeviceSessionsTable.id],
        accountId = this[DeviceSessionsTable.accountId],
        deviceName = this[DeviceSessionsTable.deviceName],
        platform = this[DeviceSessionsTable.platform],
        createdAt = this[DeviceSessionsTable.createdAt],
        lastRefreshedAt = this[DeviceSessionsTable.lastRefreshedAt],
        expiresAt = this[DeviceSessionsTable.expiresAt],
        revokedAt = this[DeviceSessionsTable.revokedAt],
    )
