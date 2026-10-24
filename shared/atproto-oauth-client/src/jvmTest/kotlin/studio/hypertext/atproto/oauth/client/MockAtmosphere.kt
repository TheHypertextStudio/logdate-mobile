package studio.hypertext.atproto.oauth.client

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import studio.hypertext.atproto.identity.DefaultIdentityResolver
import studio.hypertext.atproto.identity.DidPlcResolver
import studio.hypertext.atproto.identity.WellKnownHandleResolver
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A clock tests can move forward.
 */
internal class TestClock(
    var current: Instant = Instant.parse("2026-10-18T12:00:00Z"),
) : Clock {
    override fun now(): Instant = current

    fun advance(duration: Duration) {
        current += duration
    }
}

/**
 * An account hosted in the mock Atmosphere.
 */
internal data class MockAccount(
    val did: String,
    val handle: String,
    val pdsUrl: String,
    val claimsHandle: Boolean = true,
)

/**
 * A faithful stand-in for the network a sign-in touches, built from the AT Protocol OAuth,
 * DPoP (RFC 9449), PAR (RFC 9126), PKCE (RFC 7636), and JWT client authentication (RFC 7523)
 * specifications:
 *
 * - the PLC directory and `/.well-known/atproto-did` handle resolution
 * - a data server publishing protected resource metadata and serving
 *   `com.atproto.server.getSession` to DPoP-bound access tokens
 * - an entryway authorization server with PAR, token, and revocation endpoints that requires
 *   server-issued DPoP nonces, verifies every DPoP proof and client assertion signature with an
 *   independent JOSE library (Nimbus), binds sessions to the DPoP key and client key, rotates
 *   single-use refresh tokens, and detects code and refresh token replay
 * - the client metadata document, fetched from the client the way a real server would
 *
 * Tests can switch on misbehaviours to check that the client rejects them.
 */
internal class MockAtmosphere(
    val clock: TestClock = TestClock(),
) {
    val issuer = "https://auth.example.com"
    val pdsUrl = "https://pds.example.com"
    val otherIssuer = "https://other-auth.example.net"
    val otherPdsUrl = "https://pds.example.net"

    /** A second data server behind [issuer], for accounts that move between servers. */
    val secondPdsUrl = "https://pds2.example.com"

    val alice = MockAccount(did = "did:plc:aliceaaaaaaaaaaaaaaaaaaa", handle = "alice.example.com", pdsUrl = pdsUrl)
    val bob = MockAccount(did = "did:plc:bobbbbbbbbbbbbbbbbbbbbbb", handle = "bob.example.com", pdsUrl = pdsUrl)
    val elsewhere = MockAccount(did = "did:plc:elsewhereeeeeeeeeeeeeeee", handle = "elsewhere.example.net", pdsUrl = otherPdsUrl)

    val accounts = mutableListOf(alice, bob, elsewhere)

    /** The client metadata document the authorization server fetches. Set by the test. */
    var clientMetadataJson: String? = null

    var accessTokenLifetime: Duration = 5.minutes

    /** When false, the servers neither require DPoP nonces nor send `DPoP-Nonce` headers. */
    var requireNonce: Boolean = true

    var issueRefreshTokens: Boolean = true

    /**
     * When true, as on the reference server, presenting a spent refresh token ends the whole
     * grant. When false, only that refresh request fails.
     */
    var detectRefreshTokenReuse: Boolean = true

    /** When set, the pushed authorization request endpoint fails with this OAuth error. */
    var parError: String? = null

    /** Runs before each request is handled, for tests that interleave other work. */
    var beforeRequest: (suspend (HttpRequestData) -> Unit)? = null
    var authorizationServerMetadataOverride: (JsonObject) -> JsonObject = { it }
    var tokenSubjectOverride: String? = null
    var grantedScopeOverride: String? = null
    var revocationFails: Boolean = false
    var publishProtectedResourceMetadata: Boolean = true
    var redirectProtectedResourceMetadata: Boolean = false
    var redirectAuthorizationServerMetadata: Boolean = false

    /** When set, the token endpoint fails with this plain-text body instead of answering. */
    var tokenEndpointFailureBody: String? = null

    val requestLog = mutableListOf<String>()
    val revokedTokens = mutableListOf<String>()

    /** Each server origin issues its own nonces, as RFC 9449 section 8 allows. */
    private val nonces = mutableMapOf<String, String>()
    private var nonceCounter = 1
    private var currentOrigin = ""
    private val seenDpopJtis = mutableSetOf<String>()
    private val seenAssertionJtis = mutableSetOf<String>()
    private val pendingRequests = mutableMapOf<String, PendingRequest>()
    private val codes = mutableMapOf<String, IssuedCode>()
    private val grants = mutableListOf<Grant>()
    private var counter = 0

    private val json = Json { ignoreUnknownKeys = true }

    val httpClient: HttpClient =
        HttpClient(
            MockEngine { request ->
                beforeRequest?.invoke(request)
                handle(request)
            },
        )

    val identityResolver =
        DefaultIdentityResolver(
            didResolver = DidPlcResolver(httpClient),
            handleResolver = WellKnownHandleResolver(httpClient),
        )

    /** Every server moves on to a new nonce. */
    fun rotateNonce() {
        nonces.keys.toList().forEach { origin -> nonces[origin] = "nonce-${++nonceCounter}" }
    }

    private fun nonceFor(origin: String): String = nonces.getOrPut(origin) { "nonce-${++nonceCounter}" }

    private fun nonceHeaders(): Array<Pair<String, List<String>>> =
        if (requireNonce) arrayOf(DPOP_NONCE_HEADER to listOf(nonceFor(currentOrigin))) else emptyArray()

    /** Moves [account] to [pdsUrl], as when the person migrates to another data server. */
    fun move(
        account: MockAccount,
        pdsUrl: String,
    ) {
        accounts[accounts.indexOfFirst { it.did == account.did }] = account.copy(pdsUrl = pdsUrl)
    }

    /** Whether [request] is a refresh token grant. */
    fun isRefreshRequest(request: HttpRequestData): Boolean =
        (request.body as? FormDataContent)?.formData?.get("grant_type") == "refresh_token"

    /** The authorization server ends every session for [did], as when the person revokes access. */
    fun revokeAllSessions(did: String) {
        grants.filter { it.did == did }.forEach { it.revoked = true }
    }

    /** Ends every session for [did] by age, as when the session lifetime runs out. */
    fun expireAllSessions(did: String) {
        grants.filter { it.did == did }.forEach { it.sessionExpired = true }
    }

    fun grantsFor(did: String): List<Grant> = grants.filter { it.did == did }

    /**
     * Plays the person approving the request on the authorization page, and returns the callback
     * query parameters the browser would bring back.
     */
    fun approve(
        authorizationUrl: String,
        account: MockAccount = alice,
    ): Map<String, String> {
        val pending = pendingFor(authorizationUrl)
        check(pending.loginHint == null || pending.loginHint == account.handle || pending.loginHint == account.did) {
            "login_hint ${pending.loginHint} does not match ${account.handle}"
        }
        val code = "code-${++counter}"
        codes[code] = IssuedCode(pending, account.did)
        return mapOf("code" to code, "state" to pending.state, "iss" to issuer)
    }

    fun deny(authorizationUrl: String): Map<String, String> {
        val pending = pendingFor(authorizationUrl)
        return mapOf(
            "error" to "access_denied",
            "error_description" to "The user declined",
            "state" to pending.state,
            "iss" to issuer,
        )
    }

    private fun pendingFor(authorizationUrl: String): PendingRequest {
        val url = Url(authorizationUrl)
        check(url.toString().startsWith("$issuer/oauth/authorize")) { "Not this server's authorization endpoint: $url" }
        check(url.parameters.names() == setOf("client_id", "request_uri")) { "PAR redirects carry only client_id and request_uri" }
        val requestUri = url.parameters["request_uri"]!!
        val pending = pendingRequests.remove(requestUri) ?: error("Unknown or used request_uri $requestUri")
        check(pending.clientId == url.parameters["client_id"]) { "client_id differs from the pushed request" }
        return pending
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val url = request.url
        val origin = "${url.protocol.name}://${url.host}"
        currentOrigin = origin
        requestLog += "${request.method.value} $origin${url.encodedPath}"
        return when {
            origin == "https://plc.directory" -> didDocument(url.encodedPath.removePrefix("/"))
            url.encodedPath == "/.well-known/atproto-did" -> handleDid(url.host)
            origin == pdsUrl || origin == otherPdsUrl || origin == secondPdsUrl -> dataServer(origin, request)
            origin == issuer || origin == otherIssuer -> authorizationServer(origin, request)
            else -> respondJson(HttpStatusCode.NotFound, buildJsonObject { put("error", "NotFound") })
        }
    }

    private fun MockRequestHandleScope.didDocument(did: String): HttpResponseData {
        val account = accounts.firstOrNull { it.did == did } ?: return respondJson(HttpStatusCode.NotFound, buildJsonObject { })
        return respondJson(
            HttpStatusCode.OK,
            buildJsonObject {
                putJsonArray("@context") { add(JsonPrimitive("https://www.w3.org/ns/did/v1")) }
                put("id", account.did)
                putJsonArray("alsoKnownAs") {
                    if (account.claimsHandle) add(JsonPrimitive("at://${account.handle}"))
                }
                putJsonArray("service") {
                    add(
                        buildJsonObject {
                            put("id", "#atproto_pds")
                            put("type", "AtprotoPersonalDataServer")
                            put("serviceEndpoint", account.pdsUrl)
                        },
                    )
                }
            },
        )
    }

    private fun MockRequestHandleScope.handleDid(host: String): HttpResponseData {
        val account = accounts.firstOrNull { it.handle == host } ?: return respond("", HttpStatusCode.NotFound)
        return respond(account.did, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
    }

    // Data server ---------------------------------------------------------------------------------

    private fun MockRequestHandleScope.dataServer(
        origin: String,
        request: HttpRequestData,
    ): HttpResponseData {
        val path = request.url.encodedPath
        if (path == "/.well-known/oauth-protected-resource") {
            if (!publishProtectedResourceMetadata) return respondJson(HttpStatusCode.NotFound, buildJsonObject { })
            val authorizationServer = if (origin == otherPdsUrl) otherIssuer else issuer
            if (redirectProtectedResourceMetadata) return redirect("$authorizationServer/.well-known/oauth-protected-resource")
            return respondJson(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("resource", origin)
                    putJsonArray("authorization_servers") { add(JsonPrimitive(authorizationServer)) }
                },
            )
        }
        if (path == "/xrpc/com.example.redirect") return redirect(REDIRECT_TARGET)
        if (path == "/xrpc/com.atproto.server.getSession") {
            val authorization = request.headers[HttpHeaders.Authorization] ?: return unauthorized("invalid_request")
            if (!authorization.startsWith("DPoP ")) return unauthorized("invalid_request")
            val token = authorization.removePrefix("DPoP ")
            val proof =
                when (val result = checkDpop(request, accessToken = token)) {
                    is DpopResult.UseNonce -> return unauthorized("use_dpop_nonce")
                    is DpopResult.Invalid -> return unauthorized("invalid_dpop_proof")
                    is DpopResult.Valid -> result
                    null -> return unauthorized("invalid_dpop_proof")
                }
            val grant = grants.firstOrNull { it.accessToken == token }
            if (grant == null || grant.revoked || grant.accessTokenExpiresAt <= clock.now() || grant.jkt != proof.jkt) {
                return unauthorized("invalid_token")
            }
            val account = accounts.first { it.did == grant.did }
            return respondJson(
                HttpStatusCode.OK,
                buildJsonObject {
                    put("did", account.did)
                    put("handle", account.handle)
                },
            )
        }
        return respondJson(HttpStatusCode.NotFound, buildJsonObject { put("error", "MethodNotImplemented") })
    }

    private fun MockRequestHandleScope.unauthorized(error: String): HttpResponseData =
        respond(
            content = """{"error":"$error"}""",
            status = HttpStatusCode.Unauthorized,
            headers =
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    HttpHeaders.WWWAuthenticate to listOf("DPoP algs=\"ES256\", error=\"$error\""),
                    *nonceHeaders(),
                ),
        )

    // Authorization server ------------------------------------------------------------------------

    private fun MockRequestHandleScope.authorizationServer(
        origin: String,
        request: HttpRequestData,
    ): HttpResponseData {
        val path = request.url.encodedPath
        if (path == "/.well-known/oauth-authorization-server") {
            if (redirectAuthorizationServerMetadata && origin == issuer) {
                return redirect("$otherIssuer/.well-known/oauth-authorization-server")
            }
            return respondJson(HttpStatusCode.OK, authorizationServerMetadataOverride(authorizationServerMetadata(origin)))
        }
        if (origin != issuer) return respondJson(HttpStatusCode.NotFound, buildJsonObject { })
        if (request.method != HttpMethod.Post) return respondJson(HttpStatusCode.NotFound, buildJsonObject { })
        val form =
            (request.body as? FormDataContent)?.formData
                ?: return oauthError(HttpStatusCode.BadRequest, "invalid_request", "form body required")
        val parameters = form.names().associateWith { form[it]!! }

        val proof =
            when (val result = checkDpop(request, accessToken = null)) {
                is DpopResult.UseNonce -> return oauthError(
                    HttpStatusCode.BadRequest,
                    "use_dpop_nonce",
                    "Authorization server requires nonce in DPoP proof",
                )

                is DpopResult.Invalid -> return oauthError(HttpStatusCode.BadRequest, "invalid_dpop_proof", result.reason)

                is DpopResult.Valid -> result

                null -> return oauthError(HttpStatusCode.BadRequest, "invalid_dpop_proof", "DPoP proof required")
            }
        val client =
            when (val result = authenticateClient(parameters)) {
                is ClientAuthResult.Invalid -> return oauthError(HttpStatusCode.BadRequest, "invalid_client", result.reason)
                is ClientAuthResult.Valid -> result
            }

        return when (path) {
            "/oauth/par" -> pushedAuthorizationRequest(parameters, client, proof)
            "/oauth/token" -> token(parameters, client, proof)
            "/oauth/revoke" -> revoke(parameters)
            else -> respondJson(HttpStatusCode.NotFound, buildJsonObject { })
        }
    }

    private fun authorizationServerMetadata(origin: String): JsonObject =
        buildJsonObject {
            put("issuer", origin)
            put("authorization_endpoint", "$origin/oauth/authorize")
            put("token_endpoint", "$origin/oauth/token")
            put("pushed_authorization_request_endpoint", "$origin/oauth/par")
            put("revocation_endpoint", "$origin/oauth/revoke")
            put("response_types_supported", strings("code"))
            put("grant_types_supported", strings("authorization_code", "refresh_token"))
            put("code_challenge_methods_supported", strings("S256"))
            put("token_endpoint_auth_methods_supported", strings("none", "private_key_jwt"))
            put("token_endpoint_auth_signing_alg_values_supported", strings("ES256"))
            put("dpop_signing_alg_values_supported", strings("ES256"))
            put("scopes_supported", strings("atproto", "transition:generic"))
            put("authorization_response_iss_parameter_supported", true)
            put("require_pushed_authorization_requests", true)
            put("client_id_metadata_document_supported", true)
            put("protected_resources", if (origin == issuer) strings(pdsUrl, secondPdsUrl) else strings(otherPdsUrl))
        }

    private fun MockRequestHandleScope.pushedAuthorizationRequest(
        parameters: Map<String, String>,
        client: ClientAuthResult.Valid,
        proof: DpopResult.Valid,
    ): HttpResponseData {
        val metadata = client.metadata
        parError?.let { return oauthError(HttpStatusCode.BadRequest, it, "the request was refused") }
        if (parameters["response_type"] != "code") return oauthError(HttpStatusCode.BadRequest, "unsupported_response_type", "code only")
        if (parameters["code_challenge_method"] != "S256") return oauthError(HttpStatusCode.BadRequest, "invalid_request", "S256 required")
        val challenge =
            parameters["code_challenge"] ?: return oauthError(HttpStatusCode.BadRequest, "invalid_request", "code_challenge required")
        val state = parameters["state"] ?: return oauthError(HttpStatusCode.BadRequest, "invalid_request", "state required")
        val redirectUri = parameters["redirect_uri"]
        if (redirectUri !in metadata.redirectUris) return oauthError(HttpStatusCode.BadRequest, "invalid_request", "unknown redirect_uri")
        val scope = parameters["scope"].orEmpty().split(' ')
        if ("atproto" !in scope || !metadata.scope.split(' ').containsAll(scope)) {
            return oauthError(HttpStatusCode.BadRequest, "invalid_scope", "scope must include atproto and stay within client metadata")
        }
        val requestUri = "urn:ietf:params:oauth:request_uri:req-${++counter}"
        pendingRequests[requestUri] =
            PendingRequest(
                clientId = client.clientId,
                state = state,
                codeChallenge = challenge,
                redirectUri = redirectUri!!,
                scope = scope.joinToString(" "),
                loginHint = parameters["login_hint"],
                jkt = proof.jkt,
                clientKid = client.kid,
            )
        return respondJson(
            HttpStatusCode.Created,
            buildJsonObject {
                put("request_uri", requestUri)
                put("expires_in", 299)
            },
        )
    }

    private fun MockRequestHandleScope.token(
        parameters: Map<String, String>,
        client: ClientAuthResult.Valid,
        proof: DpopResult.Valid,
    ): HttpResponseData =
        when {
            tokenEndpointFailureBody != null -> respond(tokenEndpointFailureBody!!, HttpStatusCode.BadGateway)
            else -> grant(parameters, client, proof)
        }

    private fun MockRequestHandleScope.grant(
        parameters: Map<String, String>,
        client: ClientAuthResult.Valid,
        proof: DpopResult.Valid,
    ): HttpResponseData =
        when (parameters["grant_type"]) {
            "authorization_code" -> authorizationCodeGrant(parameters, client, proof)
            "refresh_token" -> refreshTokenGrant(parameters, client, proof)
            else -> oauthError(HttpStatusCode.BadRequest, "unsupported_grant_type", "unsupported grant")
        }

    private fun MockRequestHandleScope.authorizationCodeGrant(
        parameters: Map<String, String>,
        client: ClientAuthResult.Valid,
        proof: DpopResult.Valid,
    ): HttpResponseData {
        val code = parameters["code"]
        val issued = code?.let { codes[it] } ?: return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Invalid code")
        if (issued.used) {
            grants.filter { it.code == code }.forEach { it.revoked = true }
            return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Code replayed")
        }
        issued.used = true
        val pending = issued.request
        if (pending.jkt != proof.jkt) return oauthError(HttpStatusCode.BadRequest, "invalid_dpop_key_binding", "DPoP key differs from PAR")
        if (pending.clientKid != client.kid) return oauthError(HttpStatusCode.BadRequest, "invalid_client", "client key differs from PAR")
        if (parameters["redirect_uri"] !=
            pending.redirectUri
        ) {
            return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "redirect_uri mismatch")
        }
        val verifier =
            parameters["code_verifier"] ?: return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "code_verifier required")
        if (s256(verifier) !=
            pending.codeChallenge
        ) {
            return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "PKCE verification failed")
        }

        val grant =
            Grant(
                did = issued.did,
                code = code,
                jkt = proof.jkt,
                clientKid = client.kid,
                scope = pending.scope,
                accessToken = "at-${++counter}",
                refreshToken = "rt-${++counter}",
                accessTokenExpiresAt = clock.now() + accessTokenLifetime,
            )
        grants += grant
        return tokenResponse(grant)
    }

    private fun MockRequestHandleScope.refreshTokenGrant(
        parameters: Map<String, String>,
        client: ClientAuthResult.Valid,
        proof: DpopResult.Valid,
    ): HttpResponseData {
        val refreshToken = parameters["refresh_token"]
        val grant =
            grants.firstOrNull { it.refreshToken == refreshToken || refreshToken in it.usedRefreshTokens }
                ?: return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Invalid refresh token")
        if (grant.revoked) return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Invalid refresh token")
        if (refreshToken in grant.usedRefreshTokens) {
            if (detectRefreshTokenReuse) grant.revoked = true
            return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Refresh token replayed")
        }
        if (grant.sessionExpired) return oauthError(HttpStatusCode.BadRequest, "invalid_grant", "Session expired")
        if (grant.jkt !=
            proof.jkt
        ) {
            return oauthError(HttpStatusCode.BadRequest, "invalid_dpop_key_binding", "DPoP key differs from session")
        }
        if (grant.clientKid != client.kid) return oauthError(HttpStatusCode.BadRequest, "invalid_client", "client key differs from session")
        grant.usedRefreshTokens += grant.refreshToken
        grant.refreshToken = "rt-${++counter}"
        grant.accessToken = "at-${++counter}"
        grant.accessTokenExpiresAt = clock.now() + accessTokenLifetime
        return tokenResponse(grant)
    }

    private fun MockRequestHandleScope.tokenResponse(grant: Grant): HttpResponseData =
        respondJson(
            HttpStatusCode.OK,
            buildJsonObject {
                put("access_token", grant.accessToken)
                put("token_type", "DPoP")
                put("expires_in", accessTokenLifetime.inWholeSeconds)
                if (issueRefreshTokens) put("refresh_token", grant.refreshToken)
                put("scope", grantedScopeOverride ?: grant.scope)
                put("sub", tokenSubjectOverride ?: grant.did)
            },
        )

    private fun MockRequestHandleScope.revoke(parameters: Map<String, String>): HttpResponseData {
        if (revocationFails) return oauthError(HttpStatusCode.InternalServerError, "server_error", "revocation is down")
        val token = parameters["token"] ?: return oauthError(HttpStatusCode.BadRequest, "invalid_request", "token required")
        revokedTokens += token
        grants.filter { it.accessToken == token || it.refreshToken == token }.forEach { it.revoked = true }
        return respond("", HttpStatusCode.OK)
    }

    private fun MockRequestHandleScope.oauthError(
        status: HttpStatusCode,
        error: String,
        description: String,
    ): HttpResponseData =
        respond(
            content =
                buildJsonObject {
                    put("error", error)
                    put("error_description", description)
                }.toString(),
            status = status,
            headers =
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    *nonceHeaders(),
                ),
        )

    private fun MockRequestHandleScope.redirect(location: String): HttpResponseData =
        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))

    private fun MockRequestHandleScope.respondJson(
        status: HttpStatusCode,
        body: JsonObject,
    ): HttpResponseData =
        respond(
            content = body.toString(),
            status = status,
            headers =
                headersOf(
                    HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                    *nonceHeaders(),
                ),
        )

    // Verification --------------------------------------------------------------------------------

    private sealed interface DpopResult {
        data class Valid(
            val jkt: String,
        ) : DpopResult

        data object UseNonce : DpopResult

        data class Invalid(
            val reason: String,
        ) : DpopResult
    }

    private fun checkDpop(
        request: HttpRequestData,
        accessToken: String?,
    ): DpopResult? {
        val values = request.headers.getAll(DPOP_HEADER) ?: return null
        if (values.size != 1) return DpopResult.Invalid("exactly one DPoP header required")
        val jwt = runCatching { SignedJWT.parse(values.single()) }.getOrElse { return DpopResult.Invalid("not a JWT") }
        val header = jwt.header
        if (header.type?.type != "dpop+jwt") return DpopResult.Invalid("typ must be dpop+jwt")
        if (header.algorithm != JWSAlgorithm.ES256) return DpopResult.Invalid("alg must be ES256")
        val jwk = header.jwk as? ECKey ?: return DpopResult.Invalid("jwk header must be an EC key")
        if (jwk.isPrivate) return DpopResult.Invalid("jwk header must not contain a private key")
        if (!jwt.verify(ECDSAVerifier(jwk))) return DpopResult.Invalid("bad signature")
        val claims = jwt.jwtClaimsSet
        val jti = claims.jwtid ?: return DpopResult.Invalid("jti required")
        if (claims.getStringClaim("htm") != request.method.value) return DpopResult.Invalid("htm mismatch")
        val expectedHtu = "${request.url.protocol.name}://${request.url.host}${request.url.encodedPath}"
        if (claims.getStringClaim("htu") != expectedHtu) return DpopResult.Invalid("htu mismatch: ${claims.getStringClaim("htu")}")
        val iat = claims.issueTime?.toInstant()?.epochSecond ?: return DpopResult.Invalid("iat required")
        if (kotlin.math.abs(iat - clock.now().epochSeconds) > 10) return DpopResult.Invalid("iat out of range")
        val ath = claims.getStringClaim("ath")
        if (accessToken == null && ath != null) return DpopResult.Invalid("ath not allowed")
        if (accessToken != null && ath != s256(accessToken)) return DpopResult.Invalid("ath mismatch")
        if (!seenDpopJtis.add(jti)) return DpopResult.Invalid("jti replayed")
        val proofNonce = claims.getStringClaim("nonce")
        if (requireNonce && proofNonce != nonceFor(currentOrigin)) return DpopResult.UseNonce
        return DpopResult.Valid(jwk.computeThumbprint().toString())
    }

    private sealed interface ClientAuthResult {
        data class Valid(
            val clientId: String,
            val kid: String,
            val metadata: OAuthClientMetadata,
        ) : ClientAuthResult

        data class Invalid(
            val reason: String,
        ) : ClientAuthResult
    }

    private fun authenticateClient(parameters: Map<String, String>): ClientAuthResult {
        val clientId = parameters["client_id"] ?: return ClientAuthResult.Invalid("client_id required")
        val metadata =
            clientMetadataJson?.let { json.decodeFromString(OAuthClientMetadata.serializer(), it) }
                ?: return ClientAuthResult.Invalid("client metadata unavailable")
        if (metadata.clientId != clientId) return ClientAuthResult.Invalid("client_id does not match its metadata document")
        if (!metadata.dpopBoundAccessTokens) return ClientAuthResult.Invalid("dpop_bound_access_tokens must be true")
        if (metadata.tokenEndpointAuthMethod != "private_key_jwt") return ClientAuthResult.Invalid("confidential client expected")
        if ("authorization_code" !in metadata.grantTypes || "code" !in metadata.responseTypes) {
            return ClientAuthResult.Invalid("invalid client metadata")
        }
        if (parameters["client_assertion_type"] !=
            CLIENT_ASSERTION_TYPE_JWT_BEARER
        ) {
            return ClientAuthResult.Invalid("jwt-bearer assertion required")
        }
        val assertion =
            runCatching {
                SignedJWT.parse(
                    parameters["client_assertion"],
                )
            }.getOrElse { return ClientAuthResult.Invalid("assertion is not a JWT") }
        if (assertion.header.algorithm != JWSAlgorithm.ES256) return ClientAuthResult.Invalid("ES256 required")
        val kid = assertion.header.keyID ?: return ClientAuthResult.Invalid("kid required")
        val publicJwk =
            metadata.jwks?.keys?.firstOrNull { it.kid == kid } ?: return ClientAuthResult.Invalid("unknown kid $kid")
        if (publicJwk.d != null) return ClientAuthResult.Invalid("published key contains private material")
        val key = ECKey.parse(Json.encodeToString(JsonWebKey.serializer(), publicJwk))
        if (!assertion.verify(ECDSAVerifier(key))) return ClientAuthResult.Invalid("bad assertion signature")
        val claims = assertion.jwtClaimsSet
        if (claims.issuer != clientId || claims.subject != clientId) return ClientAuthResult.Invalid("iss and sub must be the client_id")
        if (claims.audience != listOf(issuer)) return ClientAuthResult.Invalid("aud must be the issuer")
        val now = clock.now().epochSeconds
        val iat = claims.issueTime?.toInstant()?.epochSecond ?: return ClientAuthResult.Invalid("iat required")
        if (kotlin.math.abs(iat - now) > 60) return ClientAuthResult.Invalid("iat out of range")
        val exp = claims.expirationTime?.toInstant()?.epochSecond ?: return ClientAuthResult.Invalid("exp required")
        if (exp <= now) return ClientAuthResult.Invalid("assertion expired")
        val jti = claims.jwtid ?: return ClientAuthResult.Invalid("jti required")
        if (!seenAssertionJtis.add(jti)) return ClientAuthResult.Invalid("assertion replayed")
        return ClientAuthResult.Valid(clientId, kid, metadata)
    }

    private fun s256(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private fun strings(vararg values: String) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    companion object {
        /** Where the data server's `com.example.redirect` endpoint sends requests. */
        const val REDIRECT_TARGET: String = "https://collector.example.org/steal"
    }

    internal data class PendingRequest(
        val clientId: String,
        val state: String,
        val codeChallenge: String,
        val redirectUri: String,
        val scope: String,
        val loginHint: String?,
        val jkt: String,
        val clientKid: String,
    )

    internal class IssuedCode(
        val request: PendingRequest,
        val did: String,
        var used: Boolean = false,
    )

    internal class Grant(
        val did: String,
        val code: String,
        val jkt: String,
        val clientKid: String,
        val scope: String,
        var accessToken: String,
        var refreshToken: String,
        var accessTokenExpiresAt: Instant,
        var revoked: Boolean = false,
        var sessionExpired: Boolean = false,
        val usedRefreshTokens: MutableList<String> = mutableListOf(),
    )
}
