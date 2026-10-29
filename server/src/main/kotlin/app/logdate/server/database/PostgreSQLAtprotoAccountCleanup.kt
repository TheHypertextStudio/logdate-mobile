@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AccountConnectionCleanup
import app.logdate.server.auth.AccountRepository
import app.logdate.server.bluesky.connection.AccountCredentialMutationLock
import app.logdate.server.bluesky.connection.AtprotoConnectionProvider
import app.logdate.server.bluesky.connection.ConnectionChanged
import app.logdate.shared.model.AccountIdentityKind
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthRevocationException
import studio.hypertext.atproto.oauth.client.OAuthSessionLock
import javax.sql.DataSource
import kotlin.uuid.Uuid

internal class PostgreSQLAtprotoAccountCleanup(
    private val accounts: AccountRepository,
    private val source: DataSource,
    private val credentials: AccountCredentialMutationLock,
    private val sessions: OAuthSessionLock,
    private val provider: AtprotoConnectionProvider? = null,
) : AccountConnectionCleanup {
    private data class Outcome<T>(
        val value: T,
    )

    override suspend fun <T> withAccountRemoval(
        accountId: Uuid,
        removeAccount: suspend () -> T,
    ): T =
        credentials.withLock(accountId) {
            val account = accounts.findById(accountId)
            val did = account?.takeIf { it.identityKind == AccountIdentityKind.EXTERNAL }?.did?.let { AtprotoDid.parse(it).getOrNull() }
            if (did == null) return@withLock removeAccount()

            suspend fun matches(): Boolean =
                accounts.findById(accountId)?.let { it.identityKind == AccountIdentityKind.EXTERNAL && it.did == did.toString() } == true

            suspend fun remove(): T {
                if (!matches()) throw ConnectionChanged()
                source.atprotoStateTransaction(did) { eraseAtprotoConnection(did) }
                return removeAccount()
            }
            val gateway = provider
            if (gateway == null) return@withLock sessions.withLock(did) { remove() }
            var result: Outcome<T>? = null
            try {
                gateway.revokeIf(did, beforeRevoke = { matches() }, removeLocal = { result = Outcome(remove()) })
            } catch (failure: OAuthRevocationException) {
                if (result == null) throw failure
            }
            (result ?: throw ConnectionChanged()).value
        }
}
