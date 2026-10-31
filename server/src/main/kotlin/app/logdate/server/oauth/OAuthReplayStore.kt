package app.logdate.server.oauth

import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Remembers single-use values, such as DPoP proof IDs and PKCE code challenges, for as long as
 * they could still be accepted, so a second use is refused.
 *
 * A server with a database uses the PostgreSQL store, which every server instance shares. A replay
 * sent to a different instance from the first use is therefore still caught.
 */
interface OAuthReplayStore {
    /**
     * Records [key] for [ttl] and returns `true`. Returns `false` when [key] was already recorded
     * and has not expired, which means this is a replay.
     */
    suspend fun firstUse(
        key: String,
        ttl: Duration,
    ): Boolean
}

/** The value a replay store keeps for [key]: its SHA-256, so stored rows never contain the value itself. */
internal fun replayKeyHash(key: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }

/**
 * A replay store for one process, used when the server runs without a database. It holds at most
 * [maxEntries] values and, when full, forgets the one that expires soonest.
 */
class InMemoryOAuthReplayStore(
    private val clock: Clock = Clock.System,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : OAuthReplayStore {
    private val seen = HashMap<String, Instant>()

    override suspend fun firstUse(
        key: String,
        ttl: Duration,
    ): Boolean =
        synchronized(seen) {
            val now = clock.now()
            // Values of different kinds have different lifetimes, so expiry is checked per entry.
            seen.values.removeIf { expiresAt -> expiresAt <= now }
            val hash = replayKeyHash(key)
            if (seen.containsKey(hash)) return false
            if (seen.size >= maxEntries) {
                seen.minByOrNull { it.value }?.let { soonest -> seen.remove(soonest.key) }
            }
            seen[hash] = now + ttl
            true
        }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 100_000
    }
}
