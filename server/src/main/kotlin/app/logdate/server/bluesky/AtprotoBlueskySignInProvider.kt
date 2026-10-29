package app.logdate.server.bluesky

import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.oauth.client.AtprotoOAuthClient
import studio.hypertext.atproto.oauth.client.OAuthAuthorizationErrorException
import studio.hypertext.atproto.oauth.client.OAuthAuthorizeOptions
import studio.hypertext.atproto.oauth.client.OAuthCallbackException
import studio.hypertext.atproto.oauth.client.OAuthConsentDeniedException
import studio.hypertext.atproto.syntax.Handle

internal class AtprotoBlueskySignInProvider(
    private val client: AtprotoOAuthClient,
) : BlueskySignInProvider {
    init {
        require(client.config.scope == "atproto") { "Bluesky sign-in must declare only the atproto scope" }
    }

    override suspend fun authorize(
        handle: Handle,
        attemptId: String,
    ): String =
        client
            .authorize(
                handle.normalized,
                OAuthAuthorizeOptions(scope = "atproto", appState = attemptId),
            ).url

    override suspend fun authorizeDid(
        did: AtprotoDid,
        attemptId: String,
    ): String = client.authorize(did.toString(), OAuthAuthorizeOptions(scope = "atproto", appState = attemptId)).url

    override suspend fun callback(
        parameters: Map<String, String>,
        acceptIdentity: suspend (VerifiedBlueskyCallback) -> Unit,
    ) {
        try {
            client.callback(parameters) { identity ->
                acceptIdentity(VerifiedBlueskyCallback(identity.did, identity.appState))
            }
        } catch (failure: OAuthConsentDeniedException) {
            throw BlueskySignInFailure(BlueskySignInFailureKind.DECLINED, failure.appState)
        } catch (failure: OAuthAuthorizationErrorException) {
            throw BlueskySignInFailure(BlueskySignInFailureKind.UNAVAILABLE, failure.appState)
        } catch (failure: OAuthCallbackException) {
            val expired =
                failure.reason == OAuthCallbackException.Reason.UNKNOWN_STATE ||
                    failure.reason == OAuthCallbackException.Reason.EXPIRED_STATE
            throw BlueskySignInFailure(if (expired) BlueskySignInFailureKind.EXPIRED else BlueskySignInFailureKind.UNAVAILABLE)
        }
    }
}
