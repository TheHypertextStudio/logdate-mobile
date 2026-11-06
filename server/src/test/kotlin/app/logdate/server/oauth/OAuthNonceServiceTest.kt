package app.logdate.server.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class OAuthNonceServiceTest {
    @Test
    fun `replicas accept the same challenge including across one rotation`() {
        val clock = MutableClock(Instant.fromEpochSeconds(0))
        val issuer = OAuthNonceService(clock = clock, sharedSecret = "shared-test-key")
        val replica = OAuthNonceService(clock = clock, sharedSecret = "shared-test-key")
        val initial = issuer.currentNonce()

        assertEquals(initial, replica.currentNonce())
        assertTrue(replica.challenge().accepts(initial))

        clock.nowValue += 150.seconds
        assertNotEquals(initial, replica.currentNonce())
        assertTrue(replica.challenge().accepts(initial))
        val verifier = OAuthDpopVerifier(clock = clock)
        val url = "https://logdate.app/oauth/token"
        val proof = createDpopProof(generateP256KeyPair(), "POST", url, nonce = initial, iat = clock.now().epochSeconds)
        assertTrue(verifier.verify(proof, "POST", url, nonceChallenge = replica.challenge()).isSuccess)
        assertTrue(verifier.verify(proof, "POST", url, nonceChallenge = replica.challenge()).isFailure)

        clock.nowValue += 150.seconds
        assertFalse(replica.challenge().accepts(initial))
    }

    @Test
    fun `a different secret and missing or malformed challenges are rejected`() {
        val clock = MutableClock(Instant.fromEpochSeconds(0))
        val issuer = OAuthNonceService(clock = clock, sharedSecret = "shared-test-key")
        val replica = OAuthNonceService(clock = clock, sharedSecret = "other-test-key")

        assertFalse(replica.challenge().accepts(issuer.currentNonce()))
        assertFalse(replica.challenge().accepts(null))
        assertFalse(replica.challenge().accepts("invalid"))
    }
}
