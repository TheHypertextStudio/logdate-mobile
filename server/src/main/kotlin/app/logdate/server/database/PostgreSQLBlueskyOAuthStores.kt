package app.logdate.server.database

import app.logdate.server.bluesky.BlueskyOAuthSecretCodec
import app.logdate.server.identity.AtRestKeyCipher
import app.logdate.shared.model.AtprotoConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthAuthorizationState
import studio.hypertext.atproto.oauth.client.OAuthSessionEndReason
import studio.hypertext.atproto.oauth.client.OAuthSessionStore
import studio.hypertext.atproto.oauth.client.OAuthStateStore
import studio.hypertext.atproto.oauth.client.OAuthStoredSession
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Timestamp
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant

class PostgreSQLBlueskyOAuthStateStore(
    private val source: DataSource,
    private val cipher: AtRestKeyCipher,
    private val clock: Clock = Clock.System,
) : OAuthStateStore {
    private val secrets = BlueskyOAuthSecretCodec(cipher)

    override suspend fun save(
        value: OAuthAuthorizationState,
        expiresAt: Instant,
    ) {
        val key = stateKey(value.state)
        val encrypted = secrets.encrypt("authorization", key, value, OAuthAuthorizationState.serializer())
        source.blueskyTransaction {
            prepareStatement("DELETE FROM bluesky_oauth_states WHERE expires_at <= ?").use {
                it.setTimestamp(1, clock.now().timestamp())
                it.executeUpdate()
            }
            prepareStatement("INSERT INTO bluesky_oauth_states(state_key, encrypted_payload, expires_at) VALUES (?, ?, ?)").use {
                it.setString(1, key)
                it.setString(2, encrypted)
                it.setTimestamp(3, expiresAt.timestamp())
                it.executeUpdate()
            }
        }
    }

    override suspend fun take(state: String): OAuthAuthorizationState? {
        val key = stateKey(state)
        val stored =
            source.blueskyTransaction {
                prepareStatement(
                    "DELETE FROM bluesky_oauth_states WHERE state_key = ? RETURNING encrypted_payload, expires_at",
                ).use {
                    it.setString(1, key)
                    it.executeQuery().use { rows ->
                        if (rows.next()) rows.getString(1) to rows.getTimestamp(2).toInstant() else null
                    }
                }
            } ?: return null
        // Consume even an expired or damaged value; neither can be replayed as a callback.
        if (stored.second <= clock.now().javaInstant()) return null
        return secrets.decrypt("authorization", key, stored.first, OAuthAuthorizationState.serializer()).also {
            require(it.state == state) { "Stored Bluesky sign-in data could not be verified" }
        }
    }

    private fun stateKey(state: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state.encodeToByteArray()))
}

class PostgreSQLBlueskyOAuthSessionStore(
    private val source: DataSource,
    private val cipher: AtRestKeyCipher,
    private val clock: Clock = Clock.System,
) : OAuthSessionStore {
    private val secrets = BlueskyOAuthSecretCodec(cipher)

    override suspend fun get(did: AtprotoDid): OAuthStoredSession? {
        val encrypted =
            source.blueskyTransaction {
                prepareStatement("SELECT encrypted_payload FROM bluesky_oauth_sessions WHERE did = ?").use {
                    it.setString(1, did.toString())
                    it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
            } ?: return null
        return secrets.decrypt("session", did.toString(), encrypted, OAuthStoredSession.serializer()).also {
            require(it.did == did) { "Stored Bluesky sign-in data could not be verified" }
        }
    }

    override suspend fun save(session: OAuthStoredSession) {
        val encrypted = secrets.encrypt("session", session.did.toString(), session, OAuthStoredSession.serializer())
        source.atprotoStateTransaction(session.did) {
            prepareStatement(
                "INSERT INTO bluesky_oauth_sessions(did, encrypted_payload, updated_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT(did) DO UPDATE SET encrypted_payload = EXCLUDED.encrypted_payload, updated_at = EXCLUDED.updated_at",
            ).use {
                it.setString(1, session.did.toString())
                it.setString(2, encrypted)
                it.setTimestamp(3, clock.now().timestamp())
                it.executeUpdate()
            }
            advanceAtprotoConnection(session.did, AtprotoConnectionState.CONNECTED, clock.now())
        }
    }

    override suspend fun delete(did: AtprotoDid) {
        delete(did, OAuthSessionEndReason.REJECTED)
    }

    override suspend fun delete(
        did: AtprotoDid,
        reason: OAuthSessionEndReason,
    ) {
        source.atprotoStateTransaction(did) {
            val activity =
                prepareStatement("DELETE FROM bluesky_oauth_sessions WHERE did = ? RETURNING updated_at").use {
                    it.setString(1, did.toString())
                    it.executeQuery().use { rows -> if (rows.next()) rows.getTimestamp(1).connectionInstant() else null }
                } ?: return@atprotoStateTransaction
            val state =
                when (reason) {
                    OAuthSessionEndReason.EXPIRED -> AtprotoConnectionState.EXPIRED
                    OAuthSessionEndReason.REVOKED -> AtprotoConnectionState.REVOKED
                    OAuthSessionEndReason.REJECTED, OAuthSessionEndReason.AUTHORITY_CHANGED -> AtprotoConnectionState.RECONNECT_REQUIRED
                }
            advanceAtprotoConnection(did, state, clock.now(), activity)
        }
    }
}

internal suspend fun <T> DataSource.blueskyTransaction(block: Connection.() -> T): T =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = connection.block()
                connection.commit()
                result
            } catch (failure: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
                throw failure
            }
        }
    }

internal fun Instant.javaInstant(): java.time.Instant = java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

internal fun Instant.timestamp(): Timestamp = Timestamp.from(javaInstant())
