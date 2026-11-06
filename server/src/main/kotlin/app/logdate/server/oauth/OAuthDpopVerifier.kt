package app.logdate.server.oauth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Verifies ES256 and ES256K DPoP proofs and computes JWK thumbprints used for token binding.
 */
class OAuthDpopVerifier(
    private val clock: Clock = Clock.System,
    private val acceptedClockSkew: Duration = 5.minutes,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val usedProofs: OAuthReplayStore = InMemoryOAuthReplayStore(clock),
) {
    /**
     * Validates [proof] against the request described by [method] and [htu], and records it so it
     * cannot be used again.
     *
     * A proof that fails a check is a failed [Result]. A failure to reach the replay store is not
     * about the proof, so it is thrown for the caller to report as a server error.
     */
    fun verify(
        proof: String,
        method: String,
        htu: String,
        nonceChallenge: OAuthNonceChallenge? = null,
        expectedAth: String? = null,
    ): Result<VerifiedDpopProof> {
        val checked = checkProof(proof, method, htu, nonceChallenge, expectedAth).getOrElse { return Result.failure(it) }
        // A proof stays acceptable for the clock-skew window on either side of its iat.
        if (!usedProofs.firstUse("dpop:${checked.keyThumbprint}:${checked.jwtId}", acceptedClockSkew * 2)) {
            return Result.failure(OAuthInvalidDpopProofException("DPoP proof has already been used"))
        }
        return Result.success(checked)
    }

    private fun checkProof(
        proof: String,
        method: String,
        htu: String,
        nonceChallenge: OAuthNonceChallenge?,
        expectedAth: String?,
    ): Result<VerifiedDpopProof> =
        runCatching {
            val segments = proof.split('.')
            if (segments.size != JWT_SEGMENT_COUNT) {
                throw OAuthInvalidDpopProofException("DPoP proofs must be compact JWTs")
            }

            val header = json.decodeFromString<DpopHeader>(decodeJsonSegment(segments[0]))
            val claims = json.decodeFromString<DpopClaims>(decodeJsonSegment(segments[1]))
            if (header.typ != "dpop+jwt") {
                throw OAuthInvalidDpopProofException("DPoP proofs must declare typ=dpop+jwt")
            }
            val curve =
                when (header.alg) {
                    EcCurve.P256.jwsAlgorithm -> EcCurve.P256
                    EcCurve.K256.jwsAlgorithm -> EcCurve.K256
                    else -> throw OAuthInvalidDpopProofException("DPoP proofs must be signed with ES256 or ES256K")
                }
            if (claims.htm.uppercase() != method.uppercase()) {
                throw OAuthInvalidDpopProofException("DPoP htm did not match the request method")
            }
            if (normalizeHtu(claims.htu) != normalizeHtu(htu)) {
                throw OAuthInvalidDpopProofException("DPoP htu did not match the request URL")
            }
            if (claims.jti.isBlank()) {
                throw OAuthInvalidDpopProofException("DPoP proofs must include a jti")
            }
            val now = clock.now().epochSeconds
            if (abs(now - claims.iat) > acceptedClockSkew.inWholeSeconds) {
                throw OAuthInvalidDpopProofException("DPoP proofs must be issued recently")
            }
            if (nonceChallenge != null && !nonceChallenge.accepts(claims.nonce)) {
                throw OAuthUseDpopNonceException(nonceChallenge.current)
            }
            if (expectedAth != null && claims.ath != expectedAth) {
                throw OAuthInvalidDpopProofException("DPoP ath did not match the access token")
            }

            val publicKey = header.jwk.toPublicKey()
            val verified =
                runCatching {
                    EcKeySupport.verifySha256(
                        publicKey = publicKey,
                        curve = curve,
                        payload = "${segments[0]}.${segments[1]}".toByteArray(StandardCharsets.UTF_8),
                        signature = base64UrlDecode(segments[2]),
                        // A DPoP proof is a JWS; JOSE does not require low-S signatures.
                        allowHighS = true,
                    )
                }.getOrElse {
                    throw OAuthInvalidDpopProofException("DPoP proof signature verification failed")
                }
            if (!verified) {
                throw OAuthInvalidDpopProofException("DPoP proof signature verification failed")
            }
            VerifiedDpopProof(
                keyThumbprint = jwkThumbprint(header.jwk),
                jwtId = claims.jti,
                nonce = claims.nonce,
            )
        }

    /**
     * Computes the DPoP `ath` value for [accessToken].
     */
    fun accessTokenHash(accessToken: String): String =
        base64UrlEncode(MessageDigest.getInstance(SHA_256_ALGORITHM).digest(accessToken.toByteArray(StandardCharsets.UTF_8)))

    /**
     * Computes the RFC 7638 thumbprint for [jwk].
     */
    fun jwkThumbprint(jwk: DpopPublicJwk): String {
        val canonical = """{"crv":"${jwk.crv}","kty":"${jwk.kty}","x":"${jwk.x}","y":"${jwk.y}"}"""
        return base64UrlEncode(MessageDigest.getInstance(SHA_256_ALGORITHM).digest(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun DpopPublicJwk.toPublicKey(): java.security.PublicKey {
        if (kty != "EC") {
            throw OAuthInvalidDpopProofException("Only EC DPoP keys are supported")
        }
        val curve =
            EcCurve.fromJwkCurveName(crv)
                ?: throw OAuthInvalidDpopProofException("Unsupported DPoP JWK curve")
        return EcKeySupport.decodePublicKeyFromJwk(curve, base64UrlDecode(x), base64UrlDecode(y))
    }

    private fun decodeJsonSegment(segment: String): String = String(base64UrlDecode(segment), StandardCharsets.UTF_8)

    private fun base64UrlDecode(value: String): ByteArray {
        val padding = "=".repeat((4 - value.length % 4) % 4)
        return Base64.getUrlDecoder().decode(value + padding)
    }

    private fun base64UrlEncode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun normalizeHtu(value: String): String {
        val uri =
            runCatching { URI(value) }.getOrNull()
                ?: throw OAuthInvalidDpopProofException("DPoP htu must be a valid absolute URL")
        val scheme =
            uri.scheme?.lowercase()
                ?: throw OAuthInvalidDpopProofException("DPoP htu must include a scheme")
        val host =
            uri.host?.lowercase()
                ?: throw OAuthInvalidDpopProofException("DPoP htu must include a host")
        val port =
            when {
                uri.port == -1 -> ""
                scheme == "https" && uri.port == 443 -> ""
                scheme == "http" && uri.port == 80 -> ""
                else -> ":${uri.port}"
            }
        val path = uri.rawPath?.takeIf(String::isNotEmpty) ?: "/"
        return "$scheme://$host$port$path"
    }

    private companion object {
        private const val JWT_SEGMENT_COUNT = 3
        private const val SHA_256_ALGORITHM = "SHA-256"
    }
}

/**
 * Parsed and verified DPoP proof metadata.
 */
data class VerifiedDpopProof(
    val keyThumbprint: String,
    val jwtId: String,
    val nonce: String?,
)

@Serializable
data class DpopPublicJwk(
    val kty: String,
    val crv: String,
    val x: String,
    val y: String,
)

@Serializable
private data class DpopHeader(
    val typ: String,
    val alg: String,
    val jwk: DpopPublicJwk,
)

@Serializable
private data class DpopClaims(
    val jti: String,
    val htm: String,
    val htu: String,
    val iat: Long,
    val nonce: String? = null,
    val ath: String? = null,
)
