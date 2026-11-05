package app.logdate.server.routes

import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.firehose.PublicRepoAccess
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthNonceService
import app.logdate.server.oauth.OAuthScopes
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import studio.hypertext.atproto.pds.PdsBlobService
import studio.hypertext.atproto.pds.PdsDiscoveryService
import studio.hypertext.atproto.pds.PdsRepoService
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.PdsSyncService

fun Route.xrpcRoutes(
    identityService: AtprotoIdentityService,
    discoveryService: PdsDiscoveryService? = null,
    accountRepository: AccountRepository? = null,
    tokenService: TokenService? = null,
    repoService: PdsRepoService? = null,
    sessionService: PdsSessionService? = null,
    syncService: PdsSyncService? = null,
    blobService: PdsBlobService? = null,
    atprotoSessionTokenService: AtprotoSessionTokenService? = null,
    oauthAccessTokenService: OAuthAccessTokenService? = null,
    oauthDpopVerifier: OAuthDpopVerifier? = null,
    oauthNonceService: OAuthNonceService? = null,
    publicOrigin: String? = null,
    collectionsOpenToApps: Set<String> = OAuthScopes.collectionsOpenToApps,
    publicRepos: PublicRepoAccess? = null,
) {
    val context =
        XrpcRouteContext(
            identityService = identityService,
            discoveryService = discoveryService,
            accountRepository = accountRepository,
            tokenService = tokenService,
            repoService = repoService,
            sessionService = sessionService,
            syncService = syncService,
            blobService = blobService,
            atprotoSessionTokenService = atprotoSessionTokenService,
            oauthAccessTokenService = oauthAccessTokenService,
            oauthDpopVerifier = oauthDpopVerifier,
            oauthNonceService = oauthNonceService,
            publicOrigin = publicOrigin,
            collectionsOpenToApps = collectionsOpenToApps,
            publicRepos = publicRepos,
        )
    route("/xrpc") {
        xrpcSessionRoutes(context)
        xrpcRepoReadRoutes(context)
        xrpcRepoWriteRoutes(context)
        xrpcBlobRoutes(context)
    }
}
