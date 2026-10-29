package app.logdate.client.device.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BlueskyAppProofTest {
    @Test
    fun `challenge matches the SHA-256 proof in RFC 7636 Appendix B`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            BlueskyAppProof.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `each app attempt gets a fresh 256-bit URL-safe verifier and matching challenge`() {
        val first = BlueskyAppProof.create()
        val second = BlueskyAppProof.create()
        assertTrue(first.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.verifier, first.challenge)
        assertEquals(BlueskyAppProof.challengeFor(first.verifier), first.challenge)
    }

    @Test
    fun `diagnostic output never contains the verifier`() {
        val proof = BlueskyAppProof.create()
        assertFalse(proof.toString().contains(proof.verifier))
    }
}
