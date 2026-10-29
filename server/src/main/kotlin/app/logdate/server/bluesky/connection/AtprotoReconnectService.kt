@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.hasSupportedSessionIdentity
import app.logdate.server.bluesky.BlueskySignInAttemptStore
import app.logdate.server.bluesky.BlueskySignInProvider
import app.logdate.server.bluesky.BlueskySignInStart
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AtprotoConnectionSnapshot
import kotlinx.coroutines.CancellationException
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal class AtprotoReconnectService(
    private val accounts: AccountRepository,
    private val attempts: BlueskySignInAttemptStore,
    private val provider: BlueskySignInProvider,
    private val connections: AtprotoConnectionService,
    private val clock: Clock = Clock.System,
) {
    suspend fun start(
        accountId: Uuid,
        appChallenge: String,
    ): BlueskySignInStart =
        guarded {
            val did = externalDid(accountId)
            if (!appChallenge.matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            val id = Base64.getUrlEncoder().withoutPadding().encodeToString(JvmOAuthCrypto.randomBytes(32))
            attempts.createReconnect(id, appChallenge, clock.now() + 5.minutes, accountId, did)
            BlueskySignInStart(id, provider.authorizeDid(did, id))
        }

    suspend fun complete(
        accountId: Uuid,
        attemptId: String,
        verifier: String,
    ): AtprotoConnectionSnapshot =
        guarded {
            val did = externalDid(accountId)
            val result = attempts.consumeReconnect(attemptId, verifier, accountId, did) ?: invalid()
            if (result.did != did || result.accountId != accountId.toString()) invalid()
            if (externalDid(accountId) != did) throw AtprotoConnectionFailure(ConnectionFailureKind.CHANGED)
            connections.status(accountId)
        }

    private suspend fun externalDid(accountId: Uuid): AtprotoDid {
        val account =
            accounts.findById(accountId)?.takeIf {
                it.isActive && it.moderation == null && it.identityKind == AccountIdentityKind.EXTERNAL && it.hasSupportedSessionIdentity()
            } ?: throw AtprotoConnectionFailure(ConnectionFailureKind.INELIGIBLE)
        return AtprotoDid.parse(account.did.orEmpty()).getOrNull() ?: throw AtprotoConnectionFailure(ConnectionFailureKind.INELIGIBLE)
    }

    private fun invalid(): Nothing = throw AtprotoConnectionFailure(ConnectionFailureKind.RECONNECT_INVALID)

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AtprotoConnectionFailure) {
            throw failure
        } catch (_: Exception) {
            throw AtprotoConnectionFailure(ConnectionFailureKind.UNAVAILABLE)
        }
}
