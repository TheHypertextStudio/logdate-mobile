package studio.hypertext.atproto.oauth.client

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the client remembers about a sign-in between sending the person to the authorization
 * server and handling the callback.
 *
 * Treat this as secret: it contains the PKCE verifier and the session's private DPoP key.
 *
 * @property state The random `state` value sent with the authorization request.
 * @property issuer The authorization server the request was sent to.
 * @property dpopKey The private DPoP key bound to this sign-in.
 * @property clientKeyId The client key that authenticated the request. The session must keep it.
 * @property pkceVerifier The PKCE code verifier.
 * @property redirectUri The redirect URI sent with the request.
 * @property expectedDid The account the sign-in started for, or `null` when it started from a
 *   server URL.
 * @property appState Opaque application data passed to [AtprotoOAuthClient.authorize].
 * @property createdAt When the authorization request was created.
 * @property requestedScope The permissions requested by this attempt. Null reads older stored
 *   attempts, which use the configured client scope as their limit.
 */
@Serializable
public data class OAuthAuthorizationState(
    val state: String,
    val issuer: String,
    val dpopKey: JsonWebKey,
    val clientKeyId: String,
    val pkceVerifier: String,
    val redirectUri: String,
    val expectedDid: AtprotoDid?,
    val appState: String?,
    val createdAt: Instant,
    val requestedScope: String? = null,
) {
    override fun toString(): String = "OAuthAuthorizationState(issuer=$issuer, expectedDid=$expectedDid, createdAt=$createdAt)"
}

internal fun scopesAreSubset(
    granted: String,
    allowed: String,
): Boolean {
    val permitted = allowed.split(' ').filter(String::isNotBlank).toSet()
    return granted.split(' ').filter(String::isNotBlank).all { it in permitted }
}

/**
 * Everything needed to use and renew an OAuth session, in a form any server instance can load.
 *
 * Treat this as secret: it contains the tokens and the session's private DPoP key.
 *
 * @property did The account the session authorizes.
 * @property issuer The authorization server that issued the tokens.
 * @property pdsUrl The account's data server (the resource server the tokens are for).
 * @property scope The scopes the person granted, space-separated.
 * @property accessToken The DPoP-bound access token.
 * @property refreshToken The refresh token, if the server issued one.
 * @property expiresAt When the access token expires, if the server said.
 * @property dpopKey The private DPoP key the tokens are bound to.
 * @property clientKeyId The client key the session was started with.
 */
@Serializable
public data class OAuthStoredSession(
    val did: AtprotoDid,
    val issuer: String,
    val pdsUrl: String,
    val scope: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant?,
    val dpopKey: JsonWebKey,
    val clientKeyId: String,
) {
    override fun toString(): String = "OAuthStoredSession(did=$did, issuer=$issuer, pdsUrl=$pdsUrl, scope=$scope, expiresAt=$expiresAt)"
}

/**
 * Persists pending authorization requests between [AtprotoOAuthClient.authorize] and
 * [AtprotoOAuthClient.callback].
 *
 * The callback may arrive at a different server instance than the one that started the sign-in,
 * so production implementations should use shared storage.
 *
 * Most sign-ins that start are never finished, so entries pile up unless the store drops them.
 * Give each entry a time to live from `expiresAt` (for example a Redis `EXPIREAT` or a database
 * column a cleanup job checks). The client refuses an expired entry either way.
 */
public interface OAuthStateStore {
    /**
     * Saves [value] under its `state`. The entry is useless after [expiresAt] and may be deleted
     * then.
     */
    public suspend fun save(
        value: OAuthAuthorizationState,
        expiresAt: Instant,
    )

    /**
     * Returns and removes the entry for [state] in one step, so each state can be used once.
     */
    public suspend fun take(state: String): OAuthAuthorizationState?
}

/**
 * Persists OAuth sessions, keyed by account DID.
 *
 * Refresh tokens are single-use, so [save] must replace the previous entry and every instance must
 * read the latest value through [get].
 */
public interface OAuthSessionStore {
    /**
     * Returns the session for [did], or `null` if there is none.
     */
    public suspend fun get(did: AtprotoDid): OAuthStoredSession?

    /**
     * Saves [session], replacing any previous session for the same DID.
     */
    public suspend fun save(session: OAuthStoredSession)

    /**
     * Removes the session for [did], if there is one.
     */
    public suspend fun delete(did: AtprotoDid)

    /** Removes the session while retaining a bounded cause, when the store supports lifecycle history. */
    public suspend fun delete(
        did: AtprotoDid,
        reason: OAuthSessionEndReason,
    ): Unit = delete(did)
}

/**
 * Serializes token refreshes for one account.
 *
 * Refresh tokens are single-use: if two refreshes of the same session run at once, the second one
 * reuses a spent refresh token, and authorization servers that detect reuse (the reference one
 * included) end the whole session. The person is signed out. The client reads sessions without the
 * lock and takes it only to renew one, reading the session again inside it.
 *
 * The default [InProcessOAuthSessionLock] covers one process. **Server instances that share a
 * session store must share a lock too**, such as a database advisory lock or a lease in Redis.
 * When a refresh is rejected the client re-reads the store in case another instance just saved
 * newer tokens, but that does not replace the lock: on a server with reuse detection, those newer
 * tokens have already been revoked.
 */
public interface OAuthSessionLock {
    /**
     * Runs [block] while holding the lock for [did].
     */
    public suspend fun <T> withLock(
        did: AtprotoDid,
        block: suspend () -> T,
    ): T
}

/**
 * An [OAuthSessionLock] that serializes refreshes within the current process. A per-account lock
 * exists only while someone holds or waits for it.
 */
public class InProcessOAuthSessionLock : OAuthSessionLock {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val guard = Mutex()
    private val locks = mutableMapOf<AtprotoDid, Entry>()

    override suspend fun <T> withLock(
        did: AtprotoDid,
        block: suspend () -> T,
    ): T {
        val entry = guard.withLock { locks.getOrPut(did) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                guard.withLock {
                    entry.users--
                    if (entry.users == 0) locks.remove(did)
                }
            }
        }
    }

    /**
     * The number of accounts whose lock is held or awaited.
     */
    internal suspend fun activeLockCount(): Int = guard.withLock { locks.size }
}

/**
 * An in-memory [OAuthStateStore] for tests and single-process development servers. Expired
 * entries are dropped whenever the store is used.
 *
 * @param clock Decides when entries expire.
 */
public class InMemoryOAuthStateStore(
    private val clock: Clock = Clock.System,
) : OAuthStateStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Pair<OAuthAuthorizationState, Instant>>()

    override suspend fun save(
        value: OAuthAuthorizationState,
        expiresAt: Instant,
    ) {
        mutex.withLock {
            dropExpired()
            entries[value.state] = value to expiresAt
        }
    }

    override suspend fun take(state: String): OAuthAuthorizationState? =
        mutex.withLock {
            dropExpired()
            entries.remove(state)?.first
        }

    /**
     * The number of entries that have not expired.
     */
    public suspend fun size(): Int =
        mutex.withLock {
            dropExpired()
            entries.size
        }

    private fun dropExpired() {
        val now = clock.now()
        entries.values.removeAll { (_, expiresAt) -> expiresAt <= now }
    }
}

/**
 * An in-memory [OAuthSessionStore] for tests and single-process development servers.
 */
public class InMemoryOAuthSessionStore : OAuthSessionStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<AtprotoDid, OAuthStoredSession>()

    override suspend fun get(did: AtprotoDid): OAuthStoredSession? = mutex.withLock { entries[did] }

    override suspend fun save(session: OAuthStoredSession) {
        mutex.withLock { entries[session.did] = session }
    }

    override suspend fun delete(did: AtprotoDid) {
        mutex.withLock { entries.remove(did) }
    }
}
