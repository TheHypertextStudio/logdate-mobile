package app.logdate.server.database

import app.logdate.server.auth.DeviceInfo
import app.logdate.server.auth.SessionType
import app.logdate.server.auth.TemporarySession
import app.logdate.server.database.PostgreSqlRepositoryTestSupport.withSessionTables
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class TemporarySessionRepositoryTest {
    @Test
    fun `session manager handles creation validation expiration and cleanup`() {
        withSessionTables {
            val repository = PostgreSQLSessionManager()
            val now = Clock.System.now()

            val accountCreation =
                repository.createAccountCreationSession(
                    temporaryUserId = null,
                    username = "alice",
                    displayName = "Alice",
                    challenge = "challenge-a",
                    deviceInfo = DeviceInfo(platform = "android", deviceName = "Pixel"),
                    bio = "bio",
                )
            assertNotNull(repository.getSession(accountCreation.id))
            assertNotNull(repository.validateSession(accountCreation.id, SessionType.ACCOUNT_CREATION))
            assertNull(repository.validateSession(accountCreation.id, SessionType.AUTHENTICATION))

            assertTrue(repository.markSessionUsed(accountCreation.id))
            assertNull(repository.validateSession(accountCreation.id, SessionType.ACCOUNT_CREATION))
            assertFalse(repository.markSessionUsed("missing"))

            val authSession =
                repository.createAuthenticationSession(
                    challenge = "challenge-b",
                    accountHint = null,
                    deviceInfo = null,
                )
            assertEquals("", authSession.username)
            assertNotNull(repository.validateSession(authSession.id, SessionType.AUTHENTICATION))
            assertTrue(repository.removeSession(authSession.id))
            assertFalse(repository.removeSession(authSession.id))
            assertNull(repository.getSession("missing-session"))

            // Store an expired session to cover expiry removal path.
            val expired =
                TemporarySession(
                    id = "expired-session",
                    temporaryUserId = Uuid.random(),
                    challenge = "challenge-expired",
                    username = "u",
                    displayName = "d",
                    bio = null,
                    deviceInfo = null,
                    sessionType = SessionType.AUTHENTICATION,
                    createdAt = now.minus(10.seconds),
                    expiresAt = now.minus(1.seconds),
                    isUsed = false,
                )
            repository.storeSession(expired)
            assertNull(repository.getSession(expired.id))

            // Add one used and one expired session to exercise cleanup logic.
            val usedSession =
                TemporarySession(
                    id = "used-session",
                    temporaryUserId = Uuid.random(),
                    challenge = "challenge-used",
                    username = "u",
                    displayName = "d",
                    bio = null,
                    deviceInfo = null,
                    sessionType = SessionType.AUTHENTICATION,
                    createdAt = now,
                    expiresAt = now.plus(5.minutes),
                    isUsed = true,
                )
            repository.storeSession(usedSession)

            val expired2 =
                TemporarySession(
                    id = "expired-session-2",
                    temporaryUserId = Uuid.random(),
                    challenge = "challenge-expired-2",
                    username = "u",
                    displayName = "d",
                    bio = null,
                    deviceInfo = null,
                    sessionType = SessionType.AUTHENTICATION,
                    createdAt = now.minus(10.minutes),
                    expiresAt = now.minus(2.minutes),
                    isUsed = false,
                )
            repository.storeSession(expired2)

            // Insert a row with nullable username/displayName to cover null fallback mapping.
            val nullableRowSessionId = "nullable-fields-session"
            transaction {
                SessionsTable.insert {
                    it[id] = nullableRowSessionId
                    it[temporaryUserId] = Uuid.random().toJavaUUID()
                    it[challenge] = "c-null"
                    it[sessionType] = SessionType.AUTHENTICATION.name
                    it[username] = null
                    it[displayName] = null
                    it[bio] = null
                    it[deviceInfo] = null
                    val now = Clock.System.now()
                    it[createdAt] = now
                    it[expiresAt] = now + 5.minutes
                    it[isUsed] = false
                }
            }
            val nullableRowSession = repository.getSession(nullableRowSessionId)
            assertNotNull(nullableRowSession)
            assertEquals("", nullableRowSession.username)
            assertEquals("", nullableRowSession.displayName)

            assertTrue(repository.cleanupExpiredSessions() >= 2)
        }
    }
}
