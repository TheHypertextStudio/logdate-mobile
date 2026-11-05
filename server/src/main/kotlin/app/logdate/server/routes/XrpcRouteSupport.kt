package app.logdate.server.routes

import app.logdate.server.atproto.AtprotoSessionTokenService
import app.logdate.server.atproto.InvalidSwapException
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.TokenService
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.logging.IdentityNotices
import app.logdate.server.oauth.OAuthAccessTokenService
import app.logdate.server.oauth.OAuthDpopVerifier
import app.logdate.server.oauth.OAuthNonceService
import app.logdate.server.oauth.OAuthScopes
import app.logdate.server.oauth.OAuthUseDpopNonceException
import io.github.aakira.napier.Napier
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.pds.PdsErrorResponse
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.UnsupportedCollectionException
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import java.util.UUID

@Serializable
internal data class CreateRecordInput(
    val repo: String,
    val collection: Nsid,
    val record: JsonObject,
    @SerialName("rkey")
    val recordKey: RecordKey? = null,
    val validate: Boolean? = null,
    val swapCommit: String? = null,
)

@Serializable
internal data class PutRecordInput(
    val repo: String,
    val collection: Nsid,
    @SerialName("rkey")
    val recordKey: RecordKey,
    val record: JsonObject,
    val validate: Boolean? = null,
    val swapRecord: String? = null,
    val swapCommit: String? = null,
)

@Serializable
internal data class DeleteRecordInput(
    val repo: String,
    val collection: Nsid,
    @SerialName("rkey")
    val recordKey: RecordKey,
    val swapRecord: String? = null,
    val swapCommit: String? = null,
)

/** Picks the token kind from the `Authorization` header and checks it with the matching steps. */
internal suspend fun io.ktor.server.application.ApplicationCall.requireAuthenticatedAccount(
    identityService: AtprotoIdentityService,
    accountRepository: AccountRepository?,
    tokenService: TokenService?,
    atprotoSessionTokenService: AtprotoSessionTokenService?,
    oauthAccessTokenService: OAuthAccessTokenService?,
    oauthDpopVerifier: OAuthDpopVerifier?,
    oauthNonceService: OAuthNonceService?,
    publicOrigin: String?,
): XrpcPrincipal? {
    if (accountRepository == null) {
        respond(HttpStatusCode.NotImplemented, PdsErrorResponse("Unsupported", "Repo auth is not configured"))
        return null
    }
    val authHeader = request.header(HttpHeaders.Authorization)
    return when {
        authHeader == null -> respondAuthRequired("Missing bearer token")
        authHeader.startsWith("DPoP ") -> {
            if (oauthAccessTokenService == null || oauthDpopVerifier == null || oauthNonceService == null) {
                respond(HttpStatusCode.NotImplemented, PdsErrorResponse("Unsupported", "OAuth DPoP auth is not configured"))
                return null
            }
            requireOAuthPrincipal(
                accessToken = authHeader.removePrefix("DPoP ").trim(),
                checks = OAuthChecks(oauthAccessTokenService, oauthDpopVerifier, oauthNonceService, publicOrigin),
                identityService = identityService,
                accountRepository = accountRepository,
            )
        }
        authHeader.startsWith("Bearer ") ->
            requireLogDatePrincipal(
                bearerToken = authHeader.removePrefix("Bearer ").trim(),
                identityService = identityService,
                accountRepository = accountRepository,
                tokenService = tokenService,
                atprotoSessionTokenService = atprotoSessionTokenService,
            )
        else -> respondAuthRequired("Missing bearer token")
    }
}

/** The services that check an OAuth access token and its DPoP proof. */
private class OAuthChecks(
    val accessTokens: OAuthAccessTokenService,
    val dpop: OAuthDpopVerifier,
    val nonces: OAuthNonceService,
    /** The server's configured public origin; the DPoP `htu` is compared against it, never a client-sent host. */
    val publicOrigin: String?,
)

/**
 * Checks an OAuth access token, its DPoP proof and the account it acts for. A failure to reach
 * the replay store is a server problem, so it is logged and answered as one, not as a bad proof.
 */
private suspend fun io.ktor.server.application.ApplicationCall.requireOAuthPrincipal(
    accessToken: String,
    checks: OAuthChecks,
    identityService: AtprotoIdentityService,
    accountRepository: AccountRepository,
): XrpcPrincipal? {
    val accessClaims = checks.accessTokens.validateAccessToken(accessToken) ?: return respondAuthRequired("Invalid DPoP access token")
    val proof =
        request.header(DPOP_HEADER)?.trim()?.takeIf(String::isNotEmpty)
            ?: return respondAuthRequired("Missing DPoP proof")
    val verification =
        try {
            checks.dpop.verify(
                proof = proof,
                method = request.httpMethod.value,
                htu = checks.publicOrigin?.let { it.trimEnd('/') + request.path() } ?: absoluteRequestUrl(),
                expectedNonce = checks.nonces.currentNonce(),
                expectedAth = checks.dpop.accessTokenHash(accessToken),
            )
        } catch (error: Exception) {
            Napier.e("Could not check a DPoP proof", error)
            respond(
                HttpStatusCode.ServiceUnavailable,
                PdsErrorResponse("TemporarilyUnavailable", "The server could not check this request. Try again shortly."),
            )
            return null
        }
    val verified =
        verification.getOrElse { error ->
            if (error is OAuthUseDpopNonceException) response.headers.append(DPOP_NONCE_HEADER, error.nonce)
            return respondAuthRequired(error.message ?: "Invalid DPoP proof")
        }
    if (verified.keyThumbprint != accessClaims.keyThumbprint) return respondAuthRequired("DPoP proof does not match the access token")
    val account =
        accountRepository
            .findByDid(accessClaims.subjectDid)
            ?.takeIf(Account::isActive)
            ?.let { identityService.ensureIdentity(it) }
            ?: return respondAuthRequired("Account not found for DPoP access token")
    return XrpcPrincipal(account, OAuthGrant(clientId = accessClaims.clientId, scopes = OAuthScopes.parse(accessClaims.scope)))
}

/** Checks a LogDate app token or an AT Protocol session token; neither carries an OAuth grant. */
@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private suspend fun io.ktor.server.application.ApplicationCall.requireLogDatePrincipal(
    bearerToken: String,
    identityService: AtprotoIdentityService,
    accountRepository: AccountRepository,
    tokenService: TokenService?,
    atprotoSessionTokenService: AtprotoSessionTokenService?,
): XrpcPrincipal? {
    if (atprotoSessionTokenService == null && tokenService == null) {
        respond(HttpStatusCode.NotImplemented, PdsErrorResponse("Unsupported", "Repo auth is not configured"))
        return null
    }
    atprotoSessionTokenService
        ?.getAccessAccount(bearerToken, accountRepository, identityService)
        ?.let { return XrpcPrincipal(it, oauth = null) }

    val accountId =
        tokenService
            ?.validateAccessToken(bearerToken)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return respondAuthRequired("Invalid bearer token")
    val account =
        accountRepository
            .findById(kotlin.uuid.Uuid.parse(accountId.toString()))
            ?.let { identityService.ensureIdentity(it) }
            ?: return respondAuthRequired("Account not found for bearer token")
    return XrpcPrincipal(account, oauth = null)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondAuthRequired(message: String): XrpcPrincipal? {
    respond(HttpStatusCode.Unauthorized, PdsErrorResponse("AuthRequired", message))
    return null
}

/**
 * Who is calling an XRPC method: the account, and for an app signed in through OAuth, which app
 * and what it was granted. LogDate app and AT Protocol session tokens carry no [oauth] grant.
 */
internal data class XrpcPrincipal(
    val account: Account,
    val oauth: OAuthGrant?,
)

/** What an app signed in through OAuth was granted. */
internal data class OAuthGrant(
    val clientId: String,
    val scopes: Set<String>,
) {
    /** `atproto` alone only confirms the identity; any repository read or write needs `transition:generic`. */
    val canUseRepository: Boolean get() = OAuthScopes.TRANSITION_GENERIC in scopes
}

/** Answers `403 InsufficientScope` and returns `false` when [grant] only confirms the identity. */
internal suspend fun ApplicationCall.allowOAuthRepositoryUse(grant: OAuthGrant): Boolean {
    if (grant.canUseRepository) return true
    respond(
        HttpStatusCode.Forbidden,
        PdsErrorResponse("InsufficientScope", "This app was only granted the atproto scope, which does not include the repository"),
    )
    return false
}

/**
 * Lets LogDate sessions write anything they own. An OAuth app needs `transition:generic` and may
 * only write a collection that is open to apps.
 */
internal suspend fun ApplicationCall.allowOAuthWrite(
    principal: XrpcPrincipal,
    collection: String,
    openCollections: Set<String>,
): Boolean {
    val grant = principal.oauth ?: return true
    if (!allowOAuthRepositoryUse(grant)) return false
    if (OAuthScopes.isOpenToApps(collection, openCollections)) return true
    respond(HttpStatusCode.Forbidden, PdsErrorResponse("Forbidden", "Apps signed in through OAuth cannot write to this collection"))
    return false
}

internal suspend fun ApplicationCall.respondUploadsClosedToOAuthApps() {
    respond(
        HttpStatusCode.Forbidden,
        PdsErrorResponse(
            "Forbidden",
            "Apps signed in through OAuth cannot upload files yet, because no collection open to apps refers to files",
        ),
    )
}

internal suspend fun ApplicationCall.respondClosedToOAuthApps() {
    respond(HttpStatusCode.Forbidden, PdsErrorResponse("Forbidden", "This method is not available to apps signed in through OAuth"))
}

internal suspend fun ApplicationCall.respondRecordNotFound() {
    respond(HttpStatusCode.BadRequest, PdsErrorResponse("RecordNotFound", "Record not found"))
}

private fun io.ktor.server.application.ApplicationCall.absoluteRequestUrl(): String {
    val origin = request.origin
    val host =
        if (origin.serverPort == DEFAULT_HTTPS_PORT || origin.serverPort == DEFAULT_HTTP_PORT) {
            origin.serverHost
        } else {
            "${origin.serverHost}:${origin.serverPort}"
        }
    return "${origin.scheme}://$host${request.path()}"
}

internal suspend fun io.ktor.server.application.ApplicationCall.requireBearerToken(): String? {
    val authHeader = request.header(HttpHeaders.Authorization)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        respond(HttpStatusCode.Unauthorized, PdsErrorResponse("AuthRequired", "Missing bearer token"))
        return null
    }
    return authHeader.removePrefix("Bearer ").trim()
}

private const val DPOP_HEADER = "DPoP"
private const val DPOP_NONCE_HEADER = "DPoP-Nonce"
private const val DEFAULT_HTTPS_PORT = 443
private const val DEFAULT_HTTP_PORT = 80

private suspend fun resolveRepoAccount(
    identityService: AtprotoIdentityService,
    repo: String,
): Account? =
    identityService.findByDid(repo.trim())
        ?: identityService.findByHandle(repo.trim())

internal suspend fun resolveRepoDid(
    identityService: AtprotoIdentityService,
    repo: String,
): AtprotoDid? =
    resolveRepoAccount(identityService, repo)
        ?.did
        ?.let(AtprotoDid::require)

internal suspend fun parseRecordId(
    identityService: AtprotoIdentityService,
    repo: String,
    collection: String,
    recordKey: String,
): RepoRecordId? {
    val resolvedRepo = resolveRepoDid(identityService, repo) ?: return null
    val resolvedCollection = Nsid.parse(collection).getOrNull() ?: return null
    val resolvedRecordKey = RecordKey.parse(recordKey).getOrNull() ?: return null
    return RepoRecordId(repo = resolvedRepo, collection = resolvedCollection, recordKey = resolvedRecordKey)
}

internal fun ownsRepo(
    account: Account,
    repo: String,
): Boolean {
    val normalized = repo.trim().lowercase()
    return normalized == account.did?.lowercase() || normalized == account.handle?.lowercase()
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondForRepoError(error: Throwable?) {
    when (error) {
        is UnsupportedCollectionException ->
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", error.message ?: "Unsupported collection"),
            )

        is studio.hypertext.atproto.repo.InvalidRepoCursorException ->
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", error.message ?: "Invalid cursor"),
            )

        is InvalidSwapException ->
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidSwap", error.message ?: "swapRecord did not match"),
            )

        else ->
            respond(
                HttpStatusCode.BadRequest,
                PdsErrorResponse("InvalidRequest", error?.message ?: "Invalid XRPC request"),
            )
    }
}

internal suspend fun ApplicationCall.respondRepoNotFound(did: String) {
    respond(HttpStatusCode.NotFound, PdsErrorResponse("RepoNotFound", "Unknown repo: $did"))
}

internal suspend fun ApplicationCall.respondBlobNotFound() {
    respond(HttpStatusCode.NotFound, PdsErrorResponse("BlobNotFound", "Blob not found"))
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondForBlobError(error: Throwable?) {
    val failure = blobFailureResponse(error)
    respond(failure.status, failure.body)
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondForSessionError(error: Throwable?) {
    when (error?.message) {
        "InvalidToken" ->
            respond(HttpStatusCode.Unauthorized, PdsErrorResponse("InvalidToken", "Authentication failed"))

        else -> {
            // The cause can carry database or library text, so it stays in the server log.
            Napier.e(IdentityNotices.SESSION_REQUEST_FAILED, error)
            respond(HttpStatusCode.InternalServerError, PdsErrorResponse("InternalServerError", "Session request failed"))
        }
    }
}

internal suspend fun io.ktor.server.application.ApplicationCall.respondForSyncError(error: Throwable?) {
    val message = error?.message.orEmpty()
    when {
        message.startsWith("Unknown repo:") ->
            respond(HttpStatusCode.NotFound, PdsErrorResponse("RepoNotFound", message))

        else ->
            respond(HttpStatusCode.BadRequest, PdsErrorResponse("InvalidRequest", message.ifBlank { "Invalid sync request" }))
    }
}
