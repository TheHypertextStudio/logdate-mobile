package app.logdate.server.database

import app.logdate.server.auth.DeviceInfo
import app.logdate.server.auth.SessionManager
import app.logdate.server.auth.SessionType
import app.logdate.server.auth.TemporarySession
import app.logdate.server.crypto.lowercaseHex
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PostgreSQLSessionManager : SessionManager {
    private val secureRandom = SecureRandom()

    companion object {
        private val SESSION_DURATION = 15.minutes
    }

    override suspend fun storeSession(session: TemporarySession): String {
        dbQuery {
            SessionsTable.insert {
                it[id] = session.id
                it[temporaryUserId] = session.temporaryUserId.toJavaUUID()
                it[challenge] = session.challenge
                it[sessionType] = session.sessionType.name
                it[username] = session.username
                it[displayName] = session.displayName
                it[bio] = session.bio
                it[originalBio] = session.originalBio
                it[deviceInfo] = session.deviceInfo?.toString()
                it[createdAt] = session.createdAt
                it[expiresAt] = session.expiresAt
                it[isUsed] = session.isUsed
            }
        }
        return session.id
    }

    override suspend fun getSession(sessionId: String): TemporarySession? {
        return dbQuery {
            val sessionRow =
                SessionsTable
                    .selectAll()
                    .where { SessionsTable.id eq sessionId }
                    .singleOrNull()
                    ?: return@dbQuery null

            val session = sessionRow.toTemporarySession()

            // Check if session is expired
            if (Clock.System.now() > session.expiresAt) {
                // Remove expired session
                SessionsTable.deleteWhere { id eq sessionId }
                return@dbQuery null
            }

            session
        }
    }

    override suspend fun markSessionUsed(sessionId: String): Boolean =
        dbQuery {
            val updatedRows =
                SessionsTable.update({ SessionsTable.id eq sessionId }) {
                    it[isUsed] = true
                }
            updatedRows > 0
        }

    override suspend fun removeSession(sessionId: String): Boolean =
        dbQuery {
            val deletedRows = SessionsTable.deleteWhere { id eq sessionId }
            deletedRows > 0
        }

    override suspend fun cleanupExpiredSessions(): Int =
        dbQuery {
            val now = Clock.System.now()

            // Delete expired or used sessions
            val deletedRows =
                SessionsTable.deleteWhere {
                    (expiresAt less now) or (isUsed eq true)
                }

            deletedRows
        }

    override suspend fun createAccountCreationSession(
        temporaryUserId: Uuid?,
        username: String,
        displayName: String,
        challenge: String,
        deviceInfo: DeviceInfo?,
        bio: String?,
        originalBio: String?,
    ): TemporarySession {
        val sessionId = generateSessionId()
        val resolvedUserId = temporaryUserId ?: Uuid.random()
        val now = Clock.System.now()

        val session =
            TemporarySession(
                id = sessionId,
                temporaryUserId = resolvedUserId,
                challenge = challenge,
                username = username,
                displayName = displayName,
                bio = bio,
                originalBio = originalBio,
                deviceInfo = deviceInfo,
                sessionType = SessionType.ACCOUNT_CREATION,
                createdAt = now,
                expiresAt = now + SESSION_DURATION,
                isUsed = false,
            )

        storeSession(session)
        return session
    }

    override suspend fun createAuthenticationSession(
        challenge: String,
        accountHint: String?,
        deviceInfo: DeviceInfo?,
    ): TemporarySession {
        val sessionId = generateSessionId()
        val temporaryUserId = Uuid.random()
        val now = Clock.System.now()

        val session =
            TemporarySession(
                id = sessionId,
                temporaryUserId = temporaryUserId,
                challenge = challenge,
                username = accountHint ?: "",
                displayName = "",
                bio = null,
                deviceInfo = deviceInfo,
                sessionType = SessionType.AUTHENTICATION,
                createdAt = now,
                expiresAt = now + SESSION_DURATION,
                isUsed = false,
            )

        storeSession(session)
        return session
    }

    override suspend fun validateSession(
        sessionId: String,
        expectedType: SessionType?,
    ): TemporarySession? {
        val session = getSession(sessionId) ?: return null

        // Check if session is already used
        if (session.isUsed) {
            return null
        }

        // Check session type if specified
        if (expectedType != null && session.sessionType != expectedType) {
            return null
        }

        return session
    }

    private fun generateSessionId(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return lowercaseHex(bytes)
    }

    private fun ResultRow.toTemporarySession(): TemporarySession =
        TemporarySession(
            id = this[SessionsTable.id],
            temporaryUserId = this[SessionsTable.temporaryUserId].toKotlinUuid(),
            challenge = this[SessionsTable.challenge],
            username = this[SessionsTable.username] ?: "",
            displayName = this[SessionsTable.displayName] ?: "",
            bio = this[SessionsTable.bio],
            originalBio = this[SessionsTable.originalBio],
            deviceInfo =
                this[SessionsTable.deviceInfo]?.let {
                    // Parse device info JSON if needed
                    null // For now, keeping it simple
                },
            sessionType = SessionType.valueOf(this[SessionsTable.sessionType]),
            createdAt = this[SessionsTable.createdAt],
            expiresAt = this[SessionsTable.expiresAt],
            isUsed = this[SessionsTable.isUsed],
        )
}
