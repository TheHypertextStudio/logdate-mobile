package app.logdate.server.auth

import app.logdate.shared.model.AccountTokens
import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Production JWT token service for LogDate Cloud authentication.
 *
 * This implementation generates and validates JWT tokens using HMAC-SHA256.
 * Tokens are structured as standard JWT with header, payload, and signature.
 */
@OptIn(ExperimentalUuidApi::class)
class JwtTokenService(
    secret: String = generateSecret(),
    private val issuer: String = "logdate.app",
    private val audience: String = "logdate-api",
) : TokenService {
    companion object {
        private const val ALGORITHM = "HmacSHA256"
        private val ACCESS_TOKEN_DURATION = 1.hours
        private val REFRESH_TOKEN_DURATION = 30.days
        private val SESSION_TOKEN_DURATION = 15.minutes

        // Claims are read only after the signature verifies, so tolerating claims this build does not know
        // lets a later release add one without invalidating tokens during a rollback.
        private val tokenJson = Json { ignoreUnknownKeys = true }

        fun generateSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getEncoder().encodeToString(bytes)
        }
    }

    private val signingKey = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), ALGORITHM)

    // A Mac is not thread-safe, so each thread keeps one initialised with the key. The first is made
    // here so a key the provider refuses stops startup instead of failing every request.
    private val macs: ThreadLocal<Mac> = ThreadLocal.withInitial { Mac.getInstance(ALGORITHM).apply { init(signingKey) } }

    init {
        macs.get()
    }

    @Serializable
    private data class JwtHeader(
        val alg: String = "HS256",
        val typ: String = "JWT",
    )

    @Serializable
    private data class JwtPayload(
        val sub: String, // subject (account ID or session ID)
        val iss: String, // issuer
        val aud: String, // audience
        val exp: Long, // expiration time (seconds since epoch)
        val iat: Long, // issued at (seconds since epoch)
        val type: String, // token type: "access", "refresh", "session"
        val did: String? = null, // AT Protocol DID for user-bound tokens
        val jti: String? = null, // unique ID, so two tokens minted in the same second stay separately revocable
        val sid: String? = null, // linked device session; only on device tokens
        val scp: String? = null, // scope; "sync" on device tokens, absent on full-access tokens
    )

    override fun generateAccessToken(
        accountId: String,
        did: String?,
    ): String = generateToken(payload(accountId, did, "access", ACCESS_TOKEN_DURATION))

    override fun generateRefreshToken(
        accountId: String,
        did: String?,
    ): String = generateToken(payload(accountId, did, "refresh", REFRESH_TOKEN_DURATION))

    override fun generateDeviceAccessToken(
        accountId: String,
        did: String?,
        sessionId: String,
    ): String =
        generateToken(
            payload(accountId, did, "access", ACCESS_TOKEN_DURATION).copy(sid = sessionId, scp = TokenScope.SYNC.claim),
        )

    override fun generateDeviceRefreshToken(
        accountId: String,
        did: String?,
        sessionId: String,
    ): String =
        generateToken(
            payload(accountId, did, "refresh", REFRESH_TOKEN_DURATION).copy(sid = sessionId, scp = TokenScope.SYNC.claim),
        )

    override fun generateEnrollmentTokens(
        accountId: String,
        did: String?,
        enrollmentId: String,
        issuedAt: Instant,
    ): AccountTokens {
        val access =
            payload(accountId, did, "access", ACCESS_TOKEN_DURATION, issuedAt, jti = "device-enrollment-access:$enrollmentId")
        val refresh = payload(accountId, did, "refresh", REFRESH_TOKEN_DURATION, issuedAt, jti = "device-enrollment:$enrollmentId")
        return AccountTokens(generateToken(access), generateToken(refresh))
    }

    override fun generateSessionToken(sessionId: String): String =
        generateToken(payload(sessionId, null, "session", SESSION_TOKEN_DURATION))

    override fun validateAccessToken(token: String): String? = decode(token, "access")?.takeIf { it.scope == TokenScope.FULL }?.accountId

    override fun validateRefreshToken(token: String): String? = decode(token, "refresh")?.takeIf { it.scope == TokenScope.FULL }?.accountId

    override fun decodeAccessToken(token: String): AccessTokenClaims? = decode(token, "access")

    override fun decodeRefreshToken(token: String): AccessTokenClaims? = decode(token, "refresh")

    override fun validateSessionToken(token: String): String? = verifiedPayload(token, "session")?.sub

    private fun payload(
        subject: String,
        did: String?,
        type: String,
        lifetime: Duration,
        issuedAt: Instant = Clock.System.now(),
        jti: String = Uuid.random().toString(),
    ): JwtPayload =
        JwtPayload(
            sub = subject,
            iss = issuer,
            aud = audience,
            exp = (issuedAt + lifetime).epochSeconds,
            iat = issuedAt.epochSeconds,
            type = type,
            did = did,
            jti = jti,
        )

    private fun decode(
        token: String,
        expectedType: String,
    ): AccessTokenClaims? {
        val payload = verifiedPayload(token, expectedType) ?: return null
        val scope = TokenScope.fromClaim(payload.scp) ?: return null
        if ((scope == TokenScope.SYNC) != (payload.sid != null)) return null
        return AccessTokenClaims(payload.sub, payload.did, payload.sid, scope)
    }

    private fun generateToken(payload: JwtPayload): String {
        try {
            val header = JwtHeader()

            // Encode header and payload
            val encodedHeader = base64UrlEncode(Json.encodeToString(header))
            val encodedPayload = base64UrlEncode(Json.encodeToString(payload))

            // Create signature
            val message = "$encodedHeader.$encodedPayload"
            val signature = createSignature(message)

            return "$message.$signature"
        } catch (e: Exception) {
            Napier.e("Failed to generate JWT token")
            throw IllegalStateException("Failed to generate token", e)
        }
    }

    private fun verifiedPayload(
        token: String,
        expectedType: String,
    ): JwtPayload? {
        try {
            val parts = token.split(".")
            if (parts.size != 3) {
                Napier.w("Invalid JWT format")
                return null
            }

            val (encodedHeader, encodedPayload, providedSignature) = parts

            // Verify signature
            val message = "$encodedHeader.$encodedPayload"
            val expectedSignature = createSignature(message)

            val signatureMatches =
                MessageDigest.isEqual(
                    expectedSignature.toByteArray(StandardCharsets.US_ASCII),
                    providedSignature.toByteArray(StandardCharsets.US_ASCII),
                )
            if (!signatureMatches) {
                Napier.w("JWT signature verification failed")
                return null
            }

            // Decode and validate payload
            val payloadJson = base64UrlDecode(encodedPayload)
            val payload = tokenJson.decodeFromString<JwtPayload>(payloadJson)

            // Validate token type
            if (payload.type != expectedType) {
                Napier.w("JWT type mismatch")
                return null
            }

            // Validate issuer and audience
            if (payload.iss != issuer || payload.aud != audience) {
                Napier.w("JWT issuer/audience validation failed")
                return null
            }

            // Check expiration
            val now = Clock.System.now().epochSeconds
            if (payload.exp <= now) {
                Napier.d("JWT token expired")
                return null
            }

            return payload
        } catch (_: Exception) {
            Napier.e("Failed to validate JWT token")
            return null
        }
    }

    private fun createSignature(message: String): String = base64UrlEncode(macs.get().doFinal(message.toByteArray(StandardCharsets.UTF_8)))

    private fun base64UrlEncode(data: String): String = base64UrlEncode(data.toByteArray(StandardCharsets.UTF_8))

    private fun base64UrlEncode(data: ByteArray): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(data)

    private fun base64UrlDecode(data: String): String {
        val decoded = Base64.getUrlDecoder().decode(data)
        return String(decoded, StandardCharsets.UTF_8)
    }
}
