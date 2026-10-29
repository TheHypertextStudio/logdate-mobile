package app.logdate.server.bluesky

import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import studio.hypertext.atproto.oauth.client.JvmOAuthHttp
import studio.hypertext.atproto.syntax.Handle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in public resolution only: no authorization request, credentials or remote writes. */
class BlueskyIdentityResolutionLiveTest {
    @Test
    fun `the production resolver confirms a public Atmosphere identity`() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("LOGDATE_BLUESKY_RESOLUTION_LIVE_TEST") == "1")
            val handle = Handle.require(System.getenv("LOGDATE_LIVE_HANDLE") ?: "bsky.app")
            val http =
                JvmOAuthHttp.publicInternetClient {
                    followRedirects = false
                    install(HttpTimeout) {
                        connectTimeoutMillis = 5000
                        requestTimeoutMillis = 15_000
                        socketTimeoutMillis = 15_000
                    }
                }
            try {
                val txt = JndiBlueskyTxtLookup()
                val document = BlueskyIdentityResolver(http, txt).resolveDocument(handle).getOrThrow()
                System.getenv("LOGDATE_LIVE_EXPECTED_DID")?.let { assertEquals(it, document.id.toString()) }
                if (System.getenv("LOGDATE_LIVE_EXPECT_DNS") == "1") {
                    assertTrue(txt.records("_atproto.${handle.normalized}").contains("did=${document.id}"))
                }
            } finally {
                http.close()
            }
        }
}
