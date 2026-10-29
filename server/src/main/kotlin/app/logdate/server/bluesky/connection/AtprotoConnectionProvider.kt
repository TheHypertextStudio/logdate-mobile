package app.logdate.server.bluesky.connection

import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.OAuthConditionalRevocation

internal interface AtprotoConnectionProvider {
    suspend fun restore(did: AtprotoDid): Boolean

    suspend fun revokeIf(
        did: AtprotoDid,
        beforeRevoke: suspend () -> Boolean,
        removeLocal: suspend () -> Unit,
    ): OAuthConditionalRevocation
}
