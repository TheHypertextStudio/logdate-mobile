@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.serialization.ExperimentalSerializationApi::class)

package app.logdate.server.database

import app.logdate.server.bluesky.BlueskyOAuthSecretCodec
import app.logdate.server.bluesky.BlueskySignInAttemptStore
import app.logdate.server.bluesky.BlueskySignInFailure
import app.logdate.server.bluesky.BlueskySignInFailureKind
import app.logdate.server.bluesky.VerifiedBlueskySignIn
import app.logdate.server.identity.AtRestKeyCipher
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.identity.AtprotoDid
import java.security.MessageDigest
import java.sql.Connection
import java.util.Base64
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal class PostgreSQLBlueskySignInAttemptStore(
    private val source: DataSource,
    private val cipher: AtRestKeyCipher,
    private val clock: Clock = Clock.System,
) : BlueskySignInAttemptStore {
    private val secrets = BlueskyOAuthSecretCodec(cipher)
    private val identifier = Regex("[A-Za-z0-9_-]{43}")
    private val proof = Regex("[A-Za-z0-9._~-]{43,128}")

    override suspend fun create(
        id: String,
        challenge: String,
        expiresAt: Instant,
        accountId: String?,
    ) = createAttempt(id, challenge, expiresAt, accountId)

    override suspend fun createReconnect(
        id: String,
        challenge: String,
        expiresAt: Instant,
        accountId: Uuid,
        expectedDid: AtprotoDid,
    ) {
        require(expiresAt <= clock.now() + 5.minutes)
        createAttempt(id, challenge, expiresAt, accountId.toString(), Purpose.RECONNECT, expectedDid)
    }

    private suspend fun createAttempt(
        id: String,
        challenge: String,
        expiresAt: Instant,
        accountId: String?,
        purpose: Purpose = Purpose.SIGN_IN,
        expectedDid: AtprotoDid? = null,
    ) {
        require(identifier.matches(id) && identifier.matches(challenge))
        val now = clock.now()
        require(expiresAt > now && expiresAt <= now + 10.minutes)
        val key = key(id)
        val encrypted = encrypt(key, Attempt(challenge, expiresAt, accountId, purpose = purpose, expectedDid = expectedDid))
        source.blueskyTransaction {
            prepareStatement("DELETE FROM bluesky_sign_in_attempts WHERE expires_at <= ?").use {
                it.setTimestamp(1, now.timestamp())
                it.executeUpdate()
            }
            prepareStatement("INSERT INTO bluesky_sign_in_attempts(attempt_key, encrypted_payload, expires_at) VALUES (?, ?, ?)").use {
                it.setString(1, key)
                it.setString(2, encrypted)
                it.setTimestamp(3, expiresAt.timestamp())
                it.executeUpdate()
            }
        }
    }

    override suspend fun complete(
        id: String,
        did: AtprotoDid,
    ): Boolean {
        if (!identifier.matches(id)) return false
        val key = key(id)
        return source.blueskyTransaction {
            val attempt = pending(key) ?: return@blueskyTransaction false
            if (attempt.did != null || attempt.failure != null) return@blueskyTransaction false
            if (attempt.purpose == Purpose.RECONNECT && (attempt.expectedDid != did || !ownsReconnect(attempt))) {
                return@blueskyTransaction false
            }
            val exchangeDeadline = clock.now() + 5.minutes
            prepareStatement("UPDATE bluesky_sign_in_attempts SET encrypted_payload = ?, expires_at = ? WHERE attempt_key = ?").use {
                it.setString(1, encrypt(key, attempt.copy(did = did, expiresAt = exchangeDeadline)))
                it.setTimestamp(2, exchangeDeadline.timestamp())
                it.setString(3, key)
                it.executeUpdate() == 1
            }
        }
    }

    override suspend fun fail(
        id: String,
        kind: BlueskySignInFailureKind,
    ): Boolean {
        require(kind != BlueskySignInFailureKind.EXPIRED)
        if (!identifier.matches(id)) return false
        val key = key(id)
        return source.blueskyTransaction {
            val attempt = pending(key) ?: return@blueskyTransaction false
            if (attempt.did != null || attempt.failure != null) return@blueskyTransaction false
            val deadline = clock.now() + 5.minutes
            prepareStatement("UPDATE bluesky_sign_in_attempts SET encrypted_payload = ?, expires_at = ? WHERE attempt_key = ?").use {
                it.setString(1, encrypt(key, attempt.copy(failure = kind, expiresAt = deadline)))
                it.setTimestamp(2, deadline.timestamp())
                it.setString(3, key)
                it.executeUpdate() == 1
            }
        }
    }

    override suspend fun consume(
        id: String,
        verifier: String,
    ): VerifiedBlueskySignIn? = consumeBound(id, verifier) { it.purpose == Purpose.SIGN_IN }

    override suspend fun consumeReconnect(
        id: String,
        verifier: String,
        accountId: Uuid,
        expectedDid: AtprotoDid,
    ): VerifiedBlueskySignIn? =
        consumeBound(id, verifier) {
            it.purpose == Purpose.RECONNECT &&
                it.accountId == accountId.toString() &&
                it.expectedDid == expectedDid &&
                ownsReconnect(it)
        }

    private suspend fun consumeBound(
        id: String,
        verifier: String,
        accepts: Connection.(Attempt) -> Boolean,
    ): VerifiedBlueskySignIn? {
        if (!identifier.matches(id) || !proof.matches(verifier)) return null
        val key = key(id)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash(verifier))
        val result =
            source.blueskyTransaction {
                val attempt = pending(key) ?: return@blueskyTransaction null
                if (!accepts(attempt)) return@blueskyTransaction null
                if (attempt.did == null && attempt.failure == null) return@blueskyTransaction null
                if (!MessageDigest.isEqual(challenge.encodeToByteArray(), attempt.challenge.encodeToByteArray())) {
                    return@blueskyTransaction null
                }
                delete(key)
                attempt
            } ?: return null
        // Commit consumption before throwing a stored denial, so it cannot be exchanged twice.
        result.failure?.let { throw BlueskySignInFailure(it) }
        return VerifiedBlueskySignIn(requireNotNull(result.did), result.accountId)
    }

    private fun Connection.ownsReconnect(attempt: Attempt): Boolean {
        val accountId = attempt.accountId?.let(Uuid::parseOrNull) ?: return false
        val did = attempt.expectedDid ?: return false
        return prepareStatement(
            "SELECT 1 FROM accounts WHERE id = ? AND did = ? AND identity_kind = 'external' " +
                "AND is_active = TRUE AND moderation IS NULL",
        ).use {
            it.setObject(1, accountId.toJavaUUID())
            it.setString(2, did.toString())
            it.executeQuery().use { rows -> rows.next() }
        }
    }

    private fun Connection.pending(key: String): Attempt? {
        val encrypted =
            prepareStatement("SELECT encrypted_payload FROM bluesky_sign_in_attempts WHERE attempt_key = ? FOR UPDATE").use {
                it.setString(1, key)
                it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            } ?: return null
        val attempt = secrets.decrypt("app-attempt", key, encrypted, Attempt.serializer())
        // The encrypted expiry remains authoritative if a database column is changed.
        if (attempt.expiresAt <= clock.now()) {
            delete(key)
            return null
        }
        return attempt
    }

    private fun Connection.delete(key: String) {
        prepareStatement("DELETE FROM bluesky_sign_in_attempts WHERE attempt_key = ?").use {
            it.setString(1, key)
            it.executeUpdate()
        }
    }

    private fun encrypt(
        key: String,
        attempt: Attempt,
    ) = secrets.encrypt("app-attempt", key, attempt, Attempt.serializer())

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())

    private fun key(id: String) = HexFormat.of().formatHex(hash(id))

    @Serializable
    private enum class Purpose { SIGN_IN, RECONNECT }

    @Serializable
    private data class Attempt(
        val challenge: String,
        val expiresAt: Instant,
        val accountId: String?,
        val did: AtprotoDid? = null,
        val failure: BlueskySignInFailureKind? = null,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val purpose: Purpose = Purpose.SIGN_IN,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val expectedDid: AtprotoDid? = null,
    )
}
