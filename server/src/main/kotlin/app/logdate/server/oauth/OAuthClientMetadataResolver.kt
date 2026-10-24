package app.logdate.server.oauth

import io.github.aakira.napier.Napier
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Resolves and validates OAuth client metadata documents from `client_id` URLs.
 */
class OAuthClientMetadataResolver(
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: Clock = Clock.System,
    private val cacheTtl: Duration = 10.minutes,
    private val usedClientAssertions: OAuthReplayStore = InMemoryOAuthReplayStore(clock),
) {
    private val cache = ConcurrentHashMap<String, CachedClientMetadata>()

    /**
     * Resolves and validates the metadata document at [clientId].
     */
    suspend fun resolve(clientId: String): Result<OAuthClientMetadata> =
        runCatching {
            val normalizedClientId = normalizeClientId(clientId)
            val cached = cache[normalizedClientId]
            if (cached != null && clock.now() < cached.expiresAt) {
                return@runCatching cached.metadata
            }

            val response = httpClient.get(normalizedClientId)
            if (response.status != HttpStatusCode.OK) {
                throw OAuthInvalidClientException("Client metadata document returned HTTP ${response.status.value}")
            }

            val decoded = decodeClientDocument<OAuthClientMetadata>(response.bodyAsText(), "Client metadata document", normalizedClientId)
            val resolvedJwks =
                when {
                    decoded.jwks != null -> decoded.jwks
                    !decoded.jwks_uri.isNullOrBlank() -> fetchJwks(decoded.jwks_uri)
                    else -> null
                }
            val metadata =
                decoded
                    .copy(jwks = resolvedJwks)
                    .validated(expectedClientId = normalizedClientId)
            cache[normalizedClientId] = CachedClientMetadata(metadata = metadata, expiresAt = clock.now() + cacheTtl)
            metadata
        }

    /**
     * Authenticates the client described by [clientId] for the current request.
     */
    suspend fun authenticateClient(
        clientId: String,
        clientAssertionType: String?,
        clientAssertion: String?,
        authorizationServerIssuer: String,
    ): Result<AuthenticatedOAuthClient> =
        runCatching {
            val metadata = resolve(clientId).getOrThrow()
            when (metadata.token_endpoint_auth_method) {
                TOKEN_ENDPOINT_AUTH_METHOD_NONE -> {
                    AuthenticatedOAuthClient(
                        metadata = metadata,
                        clientKeyId = null,
                        clientKeyThumbprint = null,
                    )
                }

                TOKEN_ENDPOINT_AUTH_METHOD_PRIVATE_KEY_JWT -> {
                    if (clientAssertionType != CLIENT_ASSERTION_TYPE_JWT_BEARER) {
                        throw OAuthInvalidClientException(
                            "Confidential clients must use client_assertion_type=$CLIENT_ASSERTION_TYPE_JWT_BEARER",
                        )
                    }
                    val assertion =
                        clientAssertion?.trim()?.takeIf(String::isNotEmpty)
                            ?: throw OAuthInvalidClientException("Confidential clients must include client_assertion")
                    val verification = verifyClientAssertion(assertion, metadata, authorizationServerIssuer)
                    AuthenticatedOAuthClient(
                        metadata = metadata,
                        clientKeyId = verification.kid,
                        clientKeyThumbprint = verification.thumbprint,
                    )
                }

                else -> {
                    throw OAuthInvalidClientException("Unsupported token_endpoint_auth_method=${metadata.token_endpoint_auth_method}")
                }
            }
        }

    private suspend fun fetchJwks(jwksUri: String): OAuthClientJwkSet {
        val normalizedUri = normalizeHttpsUrl(jwksUri, "jwks_uri")
        val response = httpClient.get(normalizedUri)
        if (response.status != HttpStatusCode.OK) {
            throw OAuthInvalidClientException("Client JWKS document returned HTTP ${response.status.value}")
        }
        return decodeClientDocument(response.bodyAsText(), "Client JWKS document", jwksUri)
    }

    /**
     * Decodes a document the client controls. A malformed one is the client's mistake: it is answered
     * as `invalid_client` and logged as a warning, so a broken or hostile app cannot raise server errors.
     */
    private inline fun <reified T> decodeClientDocument(
        text: String,
        what: String,
        source: String,
    ): T =
        try {
            json.decodeFromString<T>(text)
        } catch (error: IllegalArgumentException) {
            Napier.w("$what from $source is malformed: ${error.message}")
            throw OAuthInvalidClientException("$what is malformed")
        }

    private fun normalizeClientId(clientId: String): String {
        val trimmed = clientId.trim()
        require(trimmed.isNotBlank()) { "client_id is required" }
        val uri =
            runCatching { URI(trimmed) }.getOrNull()
                ?: throw OAuthInvalidClientException("client_id must be a valid URL")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val isLoopbackHttp = scheme == "http" && host == "localhost"
        if (scheme != "https" && !isLoopbackHttp) {
            throw OAuthInvalidClientException("client_id must use https or a localhost http URL")
        }
        return trimmed
    }

    private fun normalizeHttpsUrl(
        value: String,
        fieldName: String,
    ): String {
        val trimmed = value.trim()
        val uri =
            runCatching { URI(trimmed) }.getOrNull()
                ?: throw OAuthInvalidClientException("$fieldName must be a valid URL")
        if (uri.scheme?.lowercase() != "https") {
            throw OAuthInvalidClientException("$fieldName must use https")
        }
        return trimmed
    }

    private fun verifyClientAssertion(
        clientAssertion: String,
        metadata: OAuthClientMetadata,
        authorizationServerIssuer: String,
    ): VerifiedClientAssertion {
        val segments = clientAssertion.split('.')
        if (segments.size != JWT_SEGMENT_COUNT) {
            throw OAuthInvalidClientException("client_assertion must be a compact JWT")
        }
        val header =
            decodeClientDocument<OAuthClientAssertionHeader>(segmentText(segments[0]), "Client assertion header", metadata.client_id)
        val claims =
            decodeClientDocument<OAuthClientAssertionClaims>(segmentText(segments[1]), "Client assertion claims", metadata.client_id)
        val assertionCurve =
            when (header.alg) {
                EcCurve.P256.jwsAlgorithm -> EcCurve.P256
                EcCurve.K256.jwsAlgorithm -> EcCurve.K256
                else -> throw OAuthInvalidClientException("Only ES256 and ES256K client assertions are supported")
            }
        if (claims.iss != metadata.client_id || claims.sub != metadata.client_id) {
            throw OAuthInvalidClientException("Client assertion iss and sub must both equal client_id")
        }
        if (claims.aud != authorizationServerIssuer) {
            throw OAuthInvalidClientException("Client assertion aud must equal the authorization server issuer")
        }
        if (claims.iat > clock.now().epochSeconds + CLIENT_ASSERTION_MAX_FUTURE_SKEW_SECONDS) {
            throw OAuthInvalidClientException("Client assertion iat is too far in the future")
        }
        if (claims.exp <= clock.now().epochSeconds) {
            throw OAuthInvalidClientException("Client assertion has expired")
        }
        if (claims.exp <= claims.iat) {
            throw OAuthInvalidClientException("Client assertion exp must be after iat")
        }
        if (claims.jti.isBlank()) {
            throw OAuthInvalidClientException("Client assertion must include jti")
        }

        val jwk = metadata.resolveAssertionKey(header.kid)
        val jwkCurve =
            jwk.curve()
                ?: throw OAuthInvalidClientException("Unsupported client assertion JWK curve")
        if (assertionCurve != jwkCurve) {
            throw OAuthInvalidClientException("client_assertion alg did not match the configured JWK curve")
        }
        val verified =
            runCatching {
                EcKeySupport.verifySha256(
                    publicKey = jwk.publicKey(),
                    curve = jwkCurve,
                    payload = "${segments[0]}.${segments[1]}".toByteArray(StandardCharsets.UTF_8),
                    signature = base64UrlDecode(segments[2]),
                    // A client assertion is a JWS; JOSE does not require low-S signatures.
                    allowHighS = true,
                )
            }.getOrElse {
                throw OAuthInvalidClientException("Client assertion signature verification failed")
            }
        if (!verified) {
            throw OAuthInvalidClientException("Client assertion signature verification failed")
        }
        markClientAssertionUsed(
            clientId = metadata.client_id,
            jti = claims.jti,
            expiresAtEpochSeconds = claims.exp,
        )
        return VerifiedClientAssertion(
            kid = header.kid ?: jwk.kid,
            thumbprint = jwk.thumbprint(),
        )
    }

    /** Records the assertion's `jti` until the assertion expires, in the store every server instance shares. */
    private fun markClientAssertionUsed(
        clientId: String,
        jti: String,
        expiresAtEpochSeconds: Long,
    ) {
        val remaining = (expiresAtEpochSeconds - clock.now().epochSeconds).coerceAtLeast(1).seconds
        if (!usedClientAssertions.firstUse("client-assertion:$clientId:$jti", remaining)) {
            throw OAuthInvalidClientException("Client assertion jti has already been used")
        }
    }

    private fun OAuthClientMetadata.resolveAssertionKey(kid: String?): OAuthClientJwk {
        val keys =
            jwks
                ?.keys
                .orEmpty()
                .filter { jwk ->
                    jwk.alg == null ||
                        jwk.alg == EcCurve.P256.jwsAlgorithm ||
                        jwk.alg == EcCurve.K256.jwsAlgorithm
                }
        if (keys.isEmpty()) {
            throw OAuthInvalidClientException("Confidential clients must publish at least one ES256 or ES256K signing key")
        }
        return when {
            kid != null -> keys.firstOrNull { it.kid == kid }
            else -> keys.singleOrNull()
        } ?: throw OAuthInvalidClientException("Unable to match client_assertion to a configured signing key")
    }

    private fun OAuthClientJwk.publicKey(): java.security.PublicKey {
        if (kty != "EC") {
            throw OAuthInvalidClientException("Only EC client authentication keys are supported")
        }
        return EcKeySupport.decodePublicKeyFromJwk(
            curve = curve() ?: throw OAuthInvalidClientException("Unsupported client authentication JWK curve"),
            x = base64UrlDecode(x),
            y = base64UrlDecode(y),
        )
    }

    private fun OAuthClientJwk.curve(): EcCurve? = EcCurve.fromJwkCurveName(crv)

    private fun OAuthClientJwk.thumbprint(): String {
        val canonical = """{"crv":"$crv","kty":"$kty","x":"$x","y":"$y"}"""
        return base64UrlEncode(MessageDigest.getInstance(SHA_256_ALGORITHM).digest(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun decodeJsonSegment(segment: String): String = String(base64UrlDecode(segment), StandardCharsets.UTF_8)

    private fun segmentText(segment: String): String =
        runCatching {
            decodeJsonSegment(
                segment,
            )
        }.getOrElse { throw OAuthInvalidClientException("client_assertion is not valid base64url") }

    private fun base64UrlDecode(value: String): ByteArray {
        val padding = "=".repeat((4 - value.length % 4) % 4)
        return Base64.getUrlDecoder().decode(value + padding)
    }

    private fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private data class CachedClientMetadata(
        val metadata: OAuthClientMetadata,
        val expiresAt: kotlin.time.Instant,
    )

    private data class VerifiedClientAssertion(
        val kid: String?,
        val thumbprint: String,
    )

    private companion object {
        private const val TOKEN_ENDPOINT_AUTH_METHOD_NONE = "none"
        private const val TOKEN_ENDPOINT_AUTH_METHOD_PRIVATE_KEY_JWT = "private_key_jwt"
        private const val CLIENT_ASSERTION_TYPE_JWT_BEARER = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
        private const val JWT_SEGMENT_COUNT = 3
        private const val CLIENT_ASSERTION_MAX_FUTURE_SKEW_SECONDS = 60L
        private const val SHA_256_ALGORITHM = "SHA-256"
    }
}

/**
 * Minimal AT Protocol OAuth client metadata document.
 */
@Serializable
data class OAuthClientMetadata(
    val client_id: String,
    val redirect_uris: List<String>,
    val grant_types: List<String> = listOf("authorization_code", "refresh_token"),
    val response_types: List<String> = listOf("code"),
    val scope: String? = null,
    val application_type: String? = null,
    val token_endpoint_auth_method: String = "none",
    val token_endpoint_auth_signing_alg: String? = null,
    val dpop_bound_access_tokens: Boolean = true,
    val client_name: String? = null,
    val jwks: OAuthClientJwkSet? = null,
    val jwks_uri: String? = null,
) {
    /**
     * Returns `true` when [redirectUri] is listed in this metadata document.
     */
    fun supportsRedirect(redirectUri: String): Boolean = redirect_uris.any { it == redirectUri }

    /**
     * Returns the declared scope tokens in canonical order.
     */
    fun scopeSet(): Set<String> =
        scope
            .orEmpty()
            .split(' ')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
}

@Serializable
data class OAuthClientJwkSet(
    val keys: List<OAuthClientJwk>,
)

@Serializable
data class OAuthClientJwk(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
    val kid: String? = null,
    val alg: String? = null,
    val use: String? = null,
)

data class AuthenticatedOAuthClient(
    val metadata: OAuthClientMetadata,
    val clientKeyId: String?,
    val clientKeyThumbprint: String?,
)

@Serializable
private data class OAuthClientAssertionHeader(
    val alg: String,
    val kid: String? = null,
)

@Serializable
private data class OAuthClientAssertionClaims(
    val iss: String,
    val sub: String,
    val aud: String,
    val iat: Long,
    val exp: Long,
    val jti: String,
)

private fun OAuthClientMetadata.validated(expectedClientId: String): OAuthClientMetadata {
    if (client_id != expectedClientId) {
        throw OAuthInvalidClientException("Client metadata client_id must match the resolved client_id URL")
    }
    if (redirect_uris.isEmpty()) {
        throw OAuthInvalidClientException("Client metadata must declare at least one redirect_uri")
    }
    if ("code" !in response_types) {
        throw OAuthInvalidClientException("Client metadata must support response_type=code")
    }
    if ("authorization_code" !in grant_types) {
        throw OAuthInvalidClientException("Client metadata must support the authorization_code grant")
    }
    if ("refresh_token" !in grant_types) {
        throw OAuthInvalidClientException("Client metadata must support the refresh_token grant")
    }
    if (!dpop_bound_access_tokens) {
        throw OAuthInvalidClientException("Client metadata must require DPoP-bound access tokens")
    }
    when (token_endpoint_auth_method) {
        "none" -> {
            if (jwks != null || !jwks_uri.isNullOrBlank()) {
                throw OAuthInvalidClientException("Public clients must not declare confidential-client keys")
            }
        }

        "private_key_jwt" -> {
            val hasInlineJwks = jwks != null
            val hasRemoteJwks = !jwks_uri.isNullOrBlank()
            if (hasInlineJwks == hasRemoteJwks) {
                throw OAuthInvalidClientException("Confidential clients must declare exactly one of jwks or jwks_uri")
            }
            if (
                (token_endpoint_auth_signing_alg ?: EcCurve.P256.jwsAlgorithm) !in
                setOf(EcCurve.P256.jwsAlgorithm, EcCurve.K256.jwsAlgorithm)
            ) {
                throw OAuthInvalidClientException("Only token_endpoint_auth_signing_alg=ES256 or ES256K is supported")
            }
        }

        else -> {
            throw OAuthInvalidClientException("Unsupported token_endpoint_auth_method=$token_endpoint_auth_method")
        }
    }
    return copy(
        redirect_uris = redirect_uris.map(String::trim),
        grant_types = grant_types.map(String::trim),
        response_types = response_types.map(String::trim),
        scope = scope?.trim()?.takeIf(String::isNotEmpty),
        application_type = application_type?.trim()?.takeIf(String::isNotEmpty),
        token_endpoint_auth_signing_alg = token_endpoint_auth_signing_alg?.trim()?.takeIf(String::isNotEmpty),
        client_name = client_name?.trim()?.takeIf(String::isNotEmpty),
        jwks_uri = jwks_uri?.trim()?.takeIf(String::isNotEmpty),
    )
}
