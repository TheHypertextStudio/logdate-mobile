package app.logdate.server.oauth

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [OAuthDpopVerifier], the component responsible for validating
 * Demonstrating Proof-of-Possession (DPoP) proofs at the application layer.
 *
 * This suite provides exhaustive coverage of the DPoP validation logic, including:
 * - Validation of DPoP JWTs across multiple elliptic curves (P-256 and K-256).
 * - Verification of the cryptographic binding between the proof and the request
 *   (HTTP method and URL).
 * - Enforcement of server-issued nonces to prevent replay attacks.
 * - Validation of access token hashes (ath) to bind the proof to a specific token.
 * - Rigorous negative testing against various attack vectors: tampered signatures,
 *   stale timestamps, mismatched algorithms, and malformed header types.
 */
class OAuthDpopVerifierTest {
    @Test
    fun `verifier accepts valid proofs and computes token hashes`() {
        val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
        val verifier = OAuthDpopVerifier(clock = clock)
        val keyPair = generateP256KeyPair()
        val ath = verifier.accessTokenHash("access-token")
        val proof =
            createDpopProof(
                keyPair = keyPair,
                method = "POST",
                htu = "https://logdate.app/oauth/token",
                nonce = "nonce-1",
                ath = ath,
                iat = clock.now().epochSeconds,
            )

        val verified =
            verifier
                .verify(
                    proof = proof,
                    method = "POST",
                    htu = "https://logdate.app/oauth/token",
                    nonceChallenge = OAuthNonceChallenge("nonce-1"),
                    expectedAth = ath,
                ).getOrThrow()

        assertTrue(verified.keyThumbprint.isNotBlank())
        assertEquals(verified.keyThumbprint, verifier.jwkThumbprint(publicJwk(keyPair)))
        assertTrue(verified.jwtId.startsWith("proof-"))
        assertEquals("nonce-1", verified.nonce)
        assertEquals(verified, verified.copy())
    }

    @Test
    fun `verifier accepts ES256K proofs`() {
        val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
        val verifier = OAuthDpopVerifier(clock = clock)
        val keyPair = generateK256KeyPair()
        val proof =
            createDpopProof(
                keyPair = keyPair,
                method = "POST",
                htu = "https://logdate.app/oauth/token",
                iat = clock.now().epochSeconds,
                alg = "ES256K",
                jwk = publicJwk(keyPair, EcCurve.K256),
                curve = EcCurve.K256,
            )

        val verified = verifier.verify(proof, "POST", "https://logdate.app/oauth/token").getOrThrow()

        assertEquals(
            verifier.jwkThumbprint(publicJwk(keyPair, EcCurve.K256)),
            verified.keyThumbprint,
        )
    }

    @Test
    fun `verifier rejects malformed or mismatched proofs`() {
        val clock = MutableClock(Instant.parse("2026-03-08T00:00:00Z"))
        val verifier = OAuthDpopVerifier(clock = clock)
        val keyPair = generateP256KeyPair()
        val validProof = createDpopProof(keyPair, "POST", "https://logdate.app/oauth/token", iat = clock.now().epochSeconds)
        val wrongMethod = createDpopProof(keyPair, "GET", "https://logdate.app/oauth/token", iat = clock.now().epochSeconds)
        val wrongUrl = createDpopProof(keyPair, "POST", "https://logdate.app/oauth/revoke", iat = clock.now().epochSeconds)
        val wrongType = createDpopProof(keyPair, "POST", "https://logdate.app/oauth/token", iat = clock.now().epochSeconds, typ = "JWT")
        val wrongAlg = createDpopProof(keyPair, "POST", "https://logdate.app/oauth/token", iat = clock.now().epochSeconds, alg = "HS256")
        val staleProof = createDpopProof(keyPair, "POST", "https://logdate.app/oauth/token", iat = clock.now().epochSeconds - 600)
        val missingSegments = "not-a-jwt"
        val tamperedSignature = validProof.substringBeforeLast('.') + ".AAAA"
        val wrongNonce =
            createDpopProof(keyPair, "POST", "https://logdate.app/oauth/token", nonce = "nonce-2", iat = clock.now().epochSeconds)
        val wrongAth = createDpopProof(keyPair, "POST", "https://logdate.app/oauth/token", ath = "bad", iat = clock.now().epochSeconds)

        assertIs<OAuthInvalidDpopProofException>(
            verifier.verify(missingSegments, "POST", "https://logdate.app/oauth/token").exceptionOrNull(),
        )
        assertIs<OAuthInvalidDpopProofException>(verifier.verify(wrongType, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertIs<OAuthInvalidDpopProofException>(verifier.verify(wrongAlg, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertIs<OAuthInvalidDpopProofException>(verifier.verify(wrongMethod, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertIs<OAuthInvalidDpopProofException>(verifier.verify(wrongUrl, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertIs<OAuthInvalidDpopProofException>(verifier.verify(staleProof, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertIs<OAuthInvalidDpopProofException>(
            verifier.verify(tamperedSignature, "POST", "https://logdate.app/oauth/token").exceptionOrNull(),
        )
        assertIs<OAuthUseDpopNonceException>(
            verifier
                .verify(wrongNonce, "POST", "https://logdate.app/oauth/token", nonceChallenge = OAuthNonceChallenge("nonce-1"))
                .exceptionOrNull(),
        )
        assertIs<OAuthInvalidDpopProofException>(
            verifier.verify(wrongAth, "POST", "https://logdate.app/oauth/token", expectedAth = "expected").exceptionOrNull(),
        )
    }

    @Test
    fun `verifier rejects invalid key material and relative urls`() {
        val verifier = OAuthDpopVerifier()
        val keyPair = generateP256KeyPair()
        val relativeUrlProof = createDpopProof(keyPair, "POST", "/oauth/token")
        val invalidKeyHeader =
            createDpopProof(
                keyPair = keyPair,
                method = "POST",
                htu = "https://logdate.app/oauth/token",
                jwk =
                    DpopPublicJwk(
                        kty = "RSA",
                        crv = "P-256",
                        x = publicJwk(keyPair).x,
                        y = publicJwk(keyPair).y,
                    ),
            )

        assertIs<OAuthInvalidDpopProofException>(
            verifier.verify(relativeUrlProof, "POST", "https://logdate.app/oauth/token").exceptionOrNull(),
        )
        assertIs<OAuthInvalidDpopProofException>(
            verifier.verify(invalidKeyHeader, "POST", "https://logdate.app/oauth/token").exceptionOrNull(),
        )
    }

    @Test
    fun `verifier rejects blank jti malformed htu and supports serialized jwks`() {
        val verifier = OAuthDpopVerifier()
        val keyPair = generateP256KeyPair()
        val blankJti =
            createDpopProof(
                keyPair = keyPair,
                method = "POST",
                htu = "https://logdate.app/oauth/token",
                jti = "",
            )
        val malformedHtu = createDpopProof(keyPair, "POST", ":::")
        val missingHost = createDpopProof(keyPair, "POST", "https:///oauth/token")
        val customPort = createDpopProof(keyPair, "POST", "https://logdate.app:444/oauth/token")
        val jwk = publicJwk(keyPair)
        val encoded = Json.encodeToString(DpopPublicJwk.serializer(), jwk)
        val decoded = Json.decodeFromString(DpopPublicJwk.serializer(), encoded)

        assertIs<OAuthInvalidDpopProofException>(verifier.verify(blankJti, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertIs<OAuthInvalidDpopProofException>(verifier.verify(malformedHtu, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertIs<OAuthInvalidDpopProofException>(verifier.verify(missingHost, "POST", "https://logdate.app/oauth/token").exceptionOrNull())
        assertTrue(verifier.verify(customPort, "POST", "https://logdate.app:444/oauth/token").isSuccess)
        assertEquals(jwk, decoded)
    }

    @Test
    fun `dpop private header and claims models expose getters`() {
        val jwk = publicJwk(generateP256KeyPair())
        val header = instantiatePrivate("app.logdate.server.oauth.DpopHeader", "dpop+jwt", "ES256", jwk)
        val claims =
            instantiatePrivate(
                "app.logdate.server.oauth.DpopClaims",
                "proof-1",
                "POST",
                "https://logdate.app/oauth/token",
                1_741_391_200L,
                "nonce-1",
                "ath-1",
            )
        val claimsSerializer = serializerFor(claims.javaClass)
        val decodedMinimalClaims =
            Json.decodeFromString(
                claimsSerializer,
                """
                {
                  "jti": "proof-2",
                  "htm": "POST",
                  "htu": "https://logdate.app/oauth/token",
                  "iat": 1741391200
                }
                """.trimIndent(),
            )

        assertEquals("dpop+jwt", invokeGetter(header, "getTyp"))
        assertEquals("ES256", invokeGetter(header, "getAlg"))
        assertEquals(jwk, invokeGetter(header, "getJwk"))
        assertEquals("proof-1", invokeGetter(claims, "getJti"))
        assertEquals("POST", invokeGetter(claims, "getHtm"))
        assertEquals("https://logdate.app/oauth/token", invokeGetter(claims, "getHtu"))
        assertEquals(1_741_391_200L, invokeGetter(claims, "getIat"))
        assertEquals("nonce-1", invokeGetter(claims, "getNonce"))
        assertEquals("ath-1", invokeGetter(claims, "getAth"))
        assertTrue(claims.toString().contains("proof-1"))
        assertEquals(null, invokeGetter(decodedMinimalClaims, "getNonce"))
        assertEquals(null, invokeGetter(decodedMinimalClaims, "getAth"))
        assertTrue(decodedMinimalClaims.toString().contains("proof-2"))
        val copiedClaims =
            claims.javaClass
                .getDeclaredMethod(
                    "copy",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    java.lang.Long.TYPE,
                    String::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
                .invoke(claims, "proof-3", "POST", "https://logdate.app/oauth/token", 1_741_391_201L, null, null)
        assertTrue(copiedClaims.toString().contains("proof-3"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(clazz: Class<*>): KSerializer<Any> =
        clazz
            .getDeclaredField("Companion")
            .apply { isAccessible = true }
            .get(null)
            .let { companion ->
                companion.javaClass
                    .getDeclaredMethod("serializer")
                    .apply { isAccessible = true }
                    .invoke(companion)
            } as KSerializer<Any>

    private fun instantiatePrivate(
        className: String,
        vararg args: Any?,
    ): Any {
        val clazz = Class.forName(className)
        val constructor =
            clazz.declaredConstructors.single { candidate ->
                val parameterTypes = candidate.parameterTypes
                parameterTypes.size == args.size &&
                    parameterTypes.withIndex().all { (index, type) ->
                        val value = args[index]
                        value == null || parameterMatches(type, value)
                    }
            }
        constructor.isAccessible = true
        return constructor.newInstance(*args)
    }

    private fun invokeGetter(
        target: Any,
        methodName: String,
    ): Any? =
        target.javaClass
            .getDeclaredMethod(methodName)
            .apply { isAccessible = true }
            .invoke(target)

    private fun parameterMatches(
        type: Class<*>,
        value: Any,
    ): Boolean =
        when {
            type.isPrimitive && type == java.lang.Long.TYPE -> value is Long
            else -> type.isAssignableFrom(value.javaClass)
        }

    @Test
    fun `dpop claims synthetic default constructor applies kotlin defaults`() {
        val constructor =
            Class
                .forName("app.logdate.server.oauth.DpopClaims")
                .getDeclaredConstructor(
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    java.lang.Long.TYPE,
                    String::class.java,
                    String::class.java,
                    Integer.TYPE,
                    DefaultConstructorMarker::class.java,
                )
        constructor.isAccessible = true

        val claims =
            constructor.newInstance(
                "proof-4",
                "POST",
                "https://logdate.app/oauth/token",
                1_741_391_202L,
                "ignored",
                "ignored",
                0b00110000,
                null,
            )

        assertEquals("proof-4", invokeGetter(claims, "getJti"))
        assertEquals(null, invokeGetter(claims, "getNonce"))
        assertEquals(null, invokeGetter(claims, "getAth"))
    }
}
