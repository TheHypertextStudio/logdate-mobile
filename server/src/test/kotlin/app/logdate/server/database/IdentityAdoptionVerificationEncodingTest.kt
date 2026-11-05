package app.logdate.server.database

import kotlin.test.Test
import kotlin.test.assertEquals

class IdentityAdoptionVerificationEncodingTest {
    @Test fun envelopeDigestMatchesTheNativeAndSharedKnownAnswer() {
        val manifest = "67d9ddfb558605caa6e155d4c746dd65a383c93f61f45f4173e9893db7cb8fcb"
        val expected = "d2777d90bc65cd0bb26187dd58f7fecaa4f94f0ec12bcbea293383a76caa90f6"
        assertEquals(expected, identityAdoptionVerificationDigest(manifest, "a".repeat(64)))
    }
}
