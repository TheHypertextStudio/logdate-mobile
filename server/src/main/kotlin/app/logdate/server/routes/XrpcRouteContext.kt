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
import io.ktor.server.application.ApplicationCall
import studio.hypertext.atproto.pds.PdsBlobService
import studio.hypertext.atproto.pds.PdsDiscoveryService
import studio.hypertext.atproto.pds.PdsRepoService
import studio.hypertext.atproto.pds.PdsSessionService
import studio.hypertext.atproto.pds.PdsSyncService

internal class XrpcRouteContext(
    val identityService: AtprotoIdentityService,
    val discoveryService: PdsDiscoveryService? = null,
    val accountRepository: AccountRepository? = null,
    val tokenService: TokenService? = null,
    val repoService: PdsRepoService? = null,
    val sessionService: PdsSessionService? = null,
    val syncService: PdsSyncService? = null,
    val blobService: PdsBlobService? = null,
    val atprotoSessionTokenService: AtprotoSessionTokenService? = null,
    val oauthAccessTokenService: OAuthAccessTokenService? = null,
    val oauthDpopVerifier: OAuthDpopVerifier? = null,
    val oauthNonceService: OAuthNonceService? = null,
    val publicOrigin: String? = null,
    val collectionsOpenToApps: Set<String> = OAuthScopes.collectionsOpenToApps,
    val publicRepos: PublicRepoAccess? = null,
) {
    val openCollections = OAuthScopes.requireOpenable(collectionsOpenToApps)
    val publicReads = publicRepos?.let { PublicRepoReads(it, identityService, blobService) }

    // A repo can hold its owner's private journal, so reads are owner-only. Anyone else gets
    // `respondAsMissing`, the answer for a repo that does not exist, before anything is looked up.
    // The exception is a repo the server shows publicly (see [PublicRepoReads]): each read handler
    // offers it to `publicReads` first, which answers only from public collections and returns
    // without touching the response for any other repo.
    suspend fun ApplicationCall.requireRepoOwner(
        repo: String,
        respondAsMissing: suspend ApplicationCall.() -> Unit,
    ): XrpcPrincipal? {
        val principal =
            requireAuthenticatedAccount(
                identityService = identityService,
                accountRepository = accountRepository,
                tokenService = tokenService,
                atprotoSessionTokenService = atprotoSessionTokenService,
                oauthAccessTokenService = oauthAccessTokenService,
                oauthDpopVerifier = oauthDpopVerifier,
                oauthNonceService = oauthNonceService,
                publicOrigin = publicOrigin,
            ) ?: return null
        if (ownsRepo(principal.account, repo)) return principal
        respondAsMissing()
        return null
    }
}
