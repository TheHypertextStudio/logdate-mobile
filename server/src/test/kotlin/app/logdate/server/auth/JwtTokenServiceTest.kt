package app.logdate.server.auth

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Comprehensive tests for the JWT-based [TokenService].
 *
 * Validates the generation and verification of various token types (access, refresh,
 * and session tokens), ensuring that JWT claims, signatures, and expiration are
 * handled correctly. It also tests security-critical features such as HMAC-SHA256
 * signature validation and protection against token tampering.
 */
class JwtTokenServiceTest {
    private val hmacKey = "test-secret-0123456789"
    private val issuer = "issuer-a"
    private val audience = "aud-a"

    @Test
    fun `enrollment session is reproducible across issuer restarts and separately revocable`() {
        val issuedAt = Clock.System.now()
        val first = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        val replay = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        val original = first.generateEnrollmentTokens("account", null, "request-one", issuedAt)
        assertEquals(original, replay.generateEnrollmentTokens("account", null, "request-one", issuedAt))
        assertEquals("account", replay.validateAccessToken(original.accessToken))
        assertEquals("account", replay.validateRefreshToken(original.refreshToken))
        assertNotEquals(original.refreshToken, first.generateEnrollmentTokens("account", null, "request-two", issuedAt).refreshToken)
        assertNotEquals(original.refreshToken, first.generateRefreshToken("account"))
    }

    @Test
    fun `generate and validate access refresh and session tokens`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)

        val access = service.generateAccessToken("acc-1")
        val refresh = service.generateRefreshToken("acc-1")
        val session = service.generateSessionToken("sess-1")

        assertNotNull(service.validateAccessToken(access))
        assertNotNull(service.validateRefreshToken(refresh))
        assertNotNull(service.validateSessionToken(session))

        assertNull(service.validateRefreshToken(access))
        assertNull(service.validateSessionToken(access))
        assertNull(service.validateAccessToken(refresh))
    }

    @Test
    fun `access and refresh tokens include did claim when provided`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)

        val access = service.generateAccessToken(accountId = "acc-1", did = "did:plc:alice123")
        val refresh = service.generateRefreshToken(accountId = "acc-1", did = "did:plc:alice123")

        assertEquals("did:plc:alice123", payloadOf(access)["did"]?.jsonPrimitive?.content)
        assertEquals("did:plc:alice123", payloadOf(refresh)["did"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid format and tampered signature return null`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        assertNull(service.validateAccessToken("not-a-jwt"))

        val token = service.generateAccessToken("acc-2")
        val tampered = token + "tampered"
        assertNull(service.validateAccessToken(tampered))
    }

    @Test
    fun `issuer and audience mismatches are rejected`() {
        val producer = JwtTokenService(secret = hmacKey, issuer = "issuer-a", audience = "aud-a")
        val token = producer.generateAccessToken("acc-3")

        val wrongIssuer = JwtTokenService(secret = hmacKey, issuer = "issuer-b", audience = "aud-a")
        val wrongAudience = JwtTokenService(secret = hmacKey, issuer = "issuer-a", audience = "aud-b")

        assertNull(wrongIssuer.validateAccessToken(token))
        assertNull(wrongAudience.validateAccessToken(token))
    }

    @Test
    fun `expired token is rejected`() {
        val now = Clock.System.now().epochSeconds
        val expiredPayload =
            "{" +
                "\"sub\":\"acc-expired\"," +
                "\"iss\":\"$issuer\"," +
                "\"aud\":\"$audience\"," +
                "\"exp\":${now - 60}," +
                "\"iat\":${now - 120}," +
                "\"type\":\"access\"" +
                "}"
        val header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
        val token = buildToken(hmacKey, header, expiredPayload)

        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        assertNull(service.validateAccessToken(token))
    }

    @Test
    fun `refresh tokens minted together for one account stay distinct`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)

        val first = service.generateRefreshToken("acc-same-second", "did:plc:alice123")
        val second = service.generateRefreshToken("acc-same-second", "did:plc:alice123")

        assertNotEquals(first, second)
        assertEquals("acc-same-second", service.validateRefreshToken(first))
        assertEquals("acc-same-second", service.validateRefreshToken(second))
    }

    @Test
    fun `every token carries its own jti`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        val enrollment = service.generateEnrollmentTokens("acc-jti", null, "request-jti", Clock.System.now())
        val tokens =
            listOf(
                service.generateAccessToken("acc-jti"),
                service.generateRefreshToken("acc-jti"),
                service.generateSessionToken("session-jti"),
                enrollment.accessToken,
                enrollment.refreshToken,
            )

        val ids = tokens.map { payloadOf(it)["jti"]?.jsonPrimitive?.content }

        assertTrue(ids.none { it.isNullOrBlank() }, "every token needs a jti: $ids")
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `access tokens minted together for one account stay distinct`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)

        val first = service.generateAccessToken("acc-same-second", "did:plc:alice123")
        val second = service.generateAccessToken("acc-same-second", "did:plc:alice123")

        assertNotEquals(first, second)
        assertEquals("acc-same-second", service.validateAccessToken(first))
        assertEquals("acc-same-second", service.validateAccessToken(second))
    }

    @Test
    fun `claims this build does not know are ignored once the signature verifies`() {
        val now = Clock.System.now().epochSeconds
        val payload =
            "{\"sub\":\"acc-later\",\"iss\":\"$issuer\",\"aud\":\"$audience\",\"exp\":${now + 600},\"iat\":$now," +
                "\"type\":\"access\",\"jti\":\"later-1\",\"x-later\":{\"nested\":true},\"cnf\":[1,2]}"
        val token = buildToken(hmacKey, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payload)

        assertEquals("acc-later", JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience).validateAccessToken(token))
        assertNull(JwtTokenService(secret = "another-secret", issuer = issuer, audience = audience).validateAccessToken(token))
    }

    @Test
    fun `linked device tokens carry their session and sync scope`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)

        val access = service.generateDeviceAccessToken("acc-watch", "did:plc:alice123", "session-1")
        val refresh = service.generateDeviceRefreshToken("acc-watch", "did:plc:alice123", "session-1")

        val expected = AccessTokenClaims("acc-watch", "did:plc:alice123", "session-1", TokenScope.SYNC)
        assertEquals(expected, service.decodeAccessToken(access))
        assertEquals(expected, service.decodeRefreshToken(refresh))
        assertEquals("session-1", payloadOf(access)["sid"]?.jsonPrimitive?.content)
        assertEquals("sync", payloadOf(access)["scp"]?.jsonPrimitive?.content)
        assertNull(service.decodeAccessToken(refresh))
        assertNull(service.decodeRefreshToken(access))
    }

    @Test
    fun `full access checks refuse linked device tokens`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)

        assertNull(service.validateAccessToken(service.generateDeviceAccessToken("acc-watch", null, "session-1")))
        assertNull(service.validateRefreshToken(service.generateDeviceRefreshToken("acc-watch", null, "session-1")))
        assertEquals(
            AccessTokenClaims.full("acc-phone", "did:plc:alice123"),
            service.decodeAccessToken(service.generateAccessToken("acc-phone", "did:plc:alice123")),
        )
        assertEquals(AccessTokenClaims.full("acc-phone"), service.decodeRefreshToken(service.generateRefreshToken("acc-phone")))
    }

    @Test
    fun `unknown scopes and half formed device claims are refused`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        val now = Clock.System.now().epochSeconds
        val header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"

        val claims = "{\"sub\":\"acc-x\",\"iss\":\"$issuer\",\"aud\":\"$audience\",\"exp\":${now + 600},\"iat\":$now"

        listOf(",\"scp\":\"admin\",\"sid\":\"s\"", ",\"scp\":\"sync\"", ",\"sid\":\"s\"").forEach { extra ->
            val forged = buildToken(hmacKey, header, "$claims,\"type\":\"access\"$extra}")
            assertNull(service.validateAccessToken(forged), extra)
            assertNull(service.decodeAccessToken(forged), extra)
        }
    }

    @Test
    fun `secret generation yields non-empty random values`() {
        val first = JwtTokenService.generateSecret()
        val second = JwtTokenService.generateSecret()

        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun `a signing key the provider refuses stops the service from being created`() {
        mockkStatic(Mac::class)
        try {
            every { Mac.getInstance(any<String>()) } throws IllegalStateException("mac-failure")
            assertFailsWith<IllegalStateException> {
                JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
            }
        } finally {
            unmockkStatic(Mac::class)
        }
    }

    @Test
    fun `tokens are signed and checked without building a new Mac for each one`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)

        mockkStatic(Mac::class)
        try {
            every { Mac.getInstance(any<String>()) } throws IllegalStateException("mac-failure")
            val token = service.generateAccessToken("acc-reused")
            assertEquals("acc-reused", service.validateAccessToken(token))
        } finally {
            unmockkStatic(Mac::class)
        }
    }

    @Test
    fun `signatures are compared in constant time`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        val token = service.generateAccessToken("acc-constant-time")
        val forged = token.substringBeforeLast('.') + "." + "A".repeat(token.substringAfterLast('.').length)

        mockkStatic(MessageDigest::class)
        try {
            assertEquals("acc-constant-time", service.validateAccessToken(token))
            assertNull(service.validateAccessToken(forged))
            verify(exactly = 2) { MessageDigest.isEqual(any(), any()) }
        } finally {
            unmockkStatic(MessageDigest::class)
        }
    }

    @Test
    fun `invalid payload encoding returns null during validation`() {
        val service = JwtTokenService(secret = hmacKey, issuer = issuer, audience = audience)
        val tokenWithInvalidPayload = "a.b@d.c"
        assertNull(service.validateAccessToken(tokenWithInvalidPayload))
    }

    @Test
    fun `service supports default secret constructor`() {
        val service = JwtTokenService()
        val token = service.generateAccessToken("acc-default-secret")
        assertNotNull(service.validateAccessToken(token))
    }

    @Test
    fun `jwt internal header and payload getters are accessible`() {
        val headerClass = Class.forName("app.logdate.server.auth.JwtTokenService\$JwtHeader")
        val headerConstructor = headerClass.getDeclaredConstructor(String::class.java, String::class.java)
        headerConstructor.isAccessible = true
        val header = headerConstructor.newInstance("HS256", "JWT")
        assertEquals("HS256", headerClass.getMethod("getAlg").invoke(header))
        assertEquals("JWT", headerClass.getMethod("getTyp").invoke(header))

        val payloadClass = Class.forName("app.logdate.server.auth.JwtTokenService\$JwtPayload")
        val payloadConstructor =
            payloadClass.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                String::class.java,
                Long::class.java,
                Long::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java,
            )
        payloadConstructor.isAccessible = true
        val payload =
            payloadConstructor.newInstance("acc-1", issuer, audience, 1000L, 900L, "access", "did:plc:alice123", null, "session-1", "sync")
        assertEquals(900L, payloadClass.getMethod("getIat").invoke(payload))
        assertEquals("did:plc:alice123", payloadClass.getMethod("getDid").invoke(payload))
        assertEquals("session-1", payloadClass.getMethod("getSid").invoke(payload))
        assertEquals("sync", payloadClass.getMethod("getScp").invoke(payload))
    }

    private fun buildToken(
        secret: String,
        headerJson: String,
        payloadJson: String,
    ): String {
        val header = base64Url(headerJson.toByteArray(StandardCharsets.UTF_8))
        val payload = base64Url(payloadJson.toByteArray(StandardCharsets.UTF_8))
        val message = "$header.$payload"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val signature = base64Url(mac.doFinal(message.toByteArray(StandardCharsets.UTF_8)))
        return "$message.$signature"
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun payloadOf(token: String) =
        Json
            .parseToJsonElement(
                String(
                    Base64.getUrlDecoder().decode(token.split(".")[1]),
                    StandardCharsets.UTF_8,
                ),
            ).jsonObject
}
