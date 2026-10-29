package app.logdate.client.device.auth

import app.logdate.shared.model.BlueskyBrowserReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlueskyAppReturnTest {
    @Test
    fun `canonical verified and custom return URLs carry the same public attempt`() {
        for (base in listOf(BlueskyBrowserReturn.URL, BlueskyBrowserReturn.NATIVE_URL)) {
            assertEquals(ATTEMPT, BlueskyAppReturn.attemptId("$base?attempt=$ATTEMPT"))
        }
    }

    @Test
    fun `other origins paths schemes and credentials cannot resume authorization`() {
        for (base in listOf(
            "http://app.logdate.app/auth/bluesky/return",
            "https://logdate.app/auth/bluesky/return",
            "https://alice.logdate.app/auth/bluesky/return",
            "https://app.logdate.app.evil.example/auth/bluesky/return",
            "https://evil.example@app.logdate.app/auth/bluesky/return",
            "https://app.logdate.app:443/auth/bluesky/return",
            "https://app.logdate.app/auth/bluesky/error",
            "https://app.logdate.app/auth/bluesky/return/",
            "logdate://auth/bluesky/return",
            "studio.hypertext.logdate://other/bluesky/return",
        )) {
            assertNull(BlueskyAppReturn.attemptId("$base?attempt=$ATTEMPT"), base)
        }
    }

    @Test
    fun `duplicate ambiguous encoded or malformed input is refused`() {
        for (suffix in listOf(
            "",
            "?attempt=short",
            "?attempt=${"a".repeat(44)}",
            "?attempt=${"a".repeat(42)}+",
            "?attempt=$ATTEMPT&attempt=$ATTEMPT",
            "?attempt=$ATTEMPT&redirect=elsewhere",
            "?attempt=$ATTEMPT#fragment",
            "?attempt=$ATTEMPT#",
            "?attempt=$ATTEMPT\n",
            "?%61ttempt=$ATTEMPT",
            "?attempt=%61${"a".repeat(42)}",
        )) {
            assertNull(BlueskyAppReturn.attemptId(BlueskyBrowserReturn.URL + suffix), suffix)
        }
    }

    private companion object {
        val ATTEMPT = "a".repeat(43)
    }
}
