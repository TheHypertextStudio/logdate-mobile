package app.logdate.server.bluesky.connection

import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.AtprotoOAuthClient
import studio.hypertext.atproto.oauth.client.OAuthConditionalRevocation
import studio.hypertext.atproto.oauth.client.OAuthRefreshMode
import studio.hypertext.atproto.oauth.client.OAuthRevocationException
import studio.hypertext.atproto.oauth.client.OAuthSessionEndReason
import studio.hypertext.atproto.oauth.client.OAuthSessionEndedException
import studio.hypertext.atproto.oauth.client.OAuthSessionNotFoundException
import studio.hypertext.atproto.oauth.client.OAuthSessionStore

/** The supplied client must require fresh authorization after a hosting change, including during renewal. */
internal class AtprotoOAuthConnectionProvider(
    private val client: AtprotoOAuthClient,
    private val sessions: OAuthSessionStore,
) : AtprotoConnectionProvider {
    override suspend fun restore(did: AtprotoDid): Boolean {
        repeat(2) {
            val stored = sessions.get(did) ?: return false
            val (pds, authority) = client.resolver.resolveForDid(did)
            if (stored.pdsUrl != pds || stored.issuer != authority.issuer) {
                val outcome =
                    try {
                        client.revokeIf(
                            did,
                            beforeRevoke = guard@{
                                val latest = sessions.get(did) ?: return@guard false
                                val (currentPds, currentAuthority) = client.resolver.resolveForDid(did)
                                latest.pdsUrl != currentPds || latest.issuer != currentAuthority.issuer
                            },
                            removeLocal = { sessions.delete(did, OAuthSessionEndReason.AUTHORITY_CHANGED) },
                        )
                    } catch (_: OAuthRevocationException) {
                        null
                    }
                // A replacement may already match the current authority; inspect it afresh without ending it.
                if (outcome == OAuthConditionalRevocation.SKIPPED) return@repeat
                return false
            }
            return try {
                client.restore(did, OAuthRefreshMode.AUTO)
                sessions.get(did) != null
            } catch (_: OAuthSessionEndedException) {
                false
            } catch (_: OAuthSessionNotFoundException) {
                false
            }
        }
        throw AtprotoConnectionFailure(ConnectionFailureKind.UNAVAILABLE)
    }

    override suspend fun revokeIf(
        did: AtprotoDid,
        beforeRevoke: suspend () -> Boolean,
        removeLocal: suspend () -> Unit,
    ): OAuthConditionalRevocation = client.revokeIf(did, beforeRevoke, removeLocal)
}
