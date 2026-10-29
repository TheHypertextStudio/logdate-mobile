package studio.hypertext.atproto.oauth.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OAuthClaimedHandleTest {
    @Test
    fun aLaterAliasCannotOverrideTheClaimedHandle() =
        runTest {
            val harness = OAuthTestHarness()
            try {
                harness.atmosphere.handleAliasesOverride = listOf("at://other.example.com", "at://${harness.atmosphere.alice.handle}")
                assertFailsWith<OAuthHandleMismatchException> { harness.client.authorize(harness.atmosphere.alice.handle) }
                assertTrue(harness.atmosphere.requestLog.none { it.endsWith("/oauth/par") })
            } finally {
                harness.client.close()
                harness.atmosphere.httpClient.close()
            }
        }
}
