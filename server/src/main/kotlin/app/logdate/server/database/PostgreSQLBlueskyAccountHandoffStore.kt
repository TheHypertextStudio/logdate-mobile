package app.logdate.server.database

import app.logdate.server.bluesky.BlueskyAccountHandoff
import app.logdate.server.bluesky.BlueskyAccountHandoffStore
import app.logdate.server.bluesky.BlueskyOAuthSecretCodec
import app.logdate.server.identity.AtRestKeyCipher
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.time.Instant

/** Account proof and passkey challenges never appear in the bearer token or database index. */
internal class PostgreSQLBlueskyAccountHandoffStore(
    private val source: DataSource,
    cipher: AtRestKeyCipher,
    private val clock: Clock = Clock.System,
) : BlueskyAccountHandoffStore {
    private val secrets = BlueskyOAuthSecretCodec(cipher)
    private val tokenFormat = Regex("[A-Za-z0-9_-]{43}")

    override suspend fun issue(
        value: BlueskyAccountHandoff,
        expiresAt: Instant,
    ): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(JvmOAuthCrypto.randomBytes(32))
        val key = key(token)
        val stored = StoredHandoff(value, expiresAt)
        val encrypted = secrets.encrypt("account-handoff", key, stored, StoredHandoff.serializer())
        source.blueskyTransaction {
            prepareStatement("DELETE FROM bluesky_account_handoffs WHERE expires_at <= ?").use {
                it.setTimestamp(1, clock.now().timestamp())
                it.executeUpdate()
            }
            prepareStatement(
                "INSERT INTO bluesky_account_handoffs(handoff_key, encrypted_payload, expires_at) VALUES (?, ?, ?)",
            ).use {
                it.setString(1, key)
                it.setString(2, encrypted)
                it.setTimestamp(3, expiresAt.timestamp())
                it.executeUpdate()
            }
        }
        return token
    }

    override suspend fun take(token: String): BlueskyAccountHandoff? {
        if (!tokenFormat.matches(token)) return null
        val key = key(token)
        val encrypted =
            source.blueskyTransaction {
                prepareStatement(
                    "DELETE FROM bluesky_account_handoffs WHERE handoff_key = ? RETURNING encrypted_payload, expires_at",
                ).use {
                    it.setString(1, key)
                    it.executeQuery().use { rows ->
                        if (rows.next() && rows.getTimestamp(2).toInstant() > clock.now().javaInstant()) rows.getString(1) else null
                    }
                }
            } ?: return null
        // Commit consumption before decoding: damaged or unreadable handoffs cannot be replayed.
        val stored = secrets.decrypt("account-handoff", key, encrypted, StoredHandoff.serializer())
        return stored.value.takeIf { stored.expiresAt > clock.now() }
    }

    private fun key(token: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray()))

    @Serializable
    private data class StoredHandoff(
        val value: BlueskyAccountHandoff,
        val expiresAt: Instant,
    )
}
