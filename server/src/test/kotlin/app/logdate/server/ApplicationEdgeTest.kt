package app.logdate.server

import app.logdate.server.config.RuntimeProfile
import app.logdate.server.config.profileAwareBoolEnv
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the application's "edge" configurations, focusing on network-level
 * concerns like CORS, HTTPS redirection, and forwarded header trust.
 *
 * This suite verifies that allowed origins are parsed correctly, that CORS
 * headers are only present when configured, and that environment-specific
 * security policies (like production HTTPS enforcement) are active.
 */
class ApplicationEdgeTest {
    @Test
    fun `parseAllowedOrigins returns empty list for null or blank`() {
        assertEquals(emptyList(), parseAllowedOrigins(null))
        assertEquals(emptyList(), parseAllowedOrigins(""))
        assertEquals(emptyList(), parseAllowedOrigins("   "))
    }

    @Test
    fun `parseAllowedOrigins splits and parses a comma-separated list`() {
        val parsed = parseAllowedOrigins("https://app.logdate.com,http://localhost:3000")
        assertEquals(2, parsed.size)

        assertEquals("https", parsed[0].scheme)
        assertEquals("app.logdate.com", parsed[0].host)
        assertNull(parsed[0].port)

        assertEquals("http", parsed[1].scheme)
        assertEquals("localhost", parsed[1].host)
        assertEquals(3000, parsed[1].port)
    }

    @Test
    fun `parseAllowedOrigins skips malformed entries`() {
        val parsed = parseAllowedOrigins("not-a-url, https://valid.example.com, also-bad")
        assertEquals(1, parsed.size)
        assertEquals("valid.example.com", parsed.single().host)
    }

    @Test
    fun `CORS is installed when ALLOWED_ORIGINS is set and reflects the origin back`() =
        testApplication {
            application { edgeModule(env = mapOf("ALLOWED_ORIGINS" to "https://app.logdate.com")) }

            val response =
                client.options("/test") {
                    header(HttpHeaders.Origin, "https://app.logdate.com")
                    header(HttpHeaders.AccessControlRequestMethod, "GET")
                }
            assertEquals(
                "https://app.logdate.com",
                response.headers[HttpHeaders.AccessControlAllowOrigin],
            )
        }

    @Test
    fun `CORS rejects origins not on the allowlist`() =
        testApplication {
            application { edgeModule(env = mapOf("ALLOWED_ORIGINS" to "https://app.logdate.com")) }

            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "https://evil.example.com")
                }
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `CORS is off by default when ALLOWED_ORIGINS is unset`() =
        testApplication {
            application { edgeModule(env = emptyMap()) }

            val response =
                client.get("/test") {
                    header(HttpHeaders.Origin, "https://app.logdate.com")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            // No CORS plugin → no Access-Control-Allow-Origin header on the response.
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }

    @Test
    fun `production profile redirects HTTP to HTTPS by default`() =
        testApplication {
            application { edgeModule(env = mapOf("LOGDATE_ENV" to "production")) }
            val noRedirectClient = createClient { followRedirects = false }

            // Explicitly send X-Forwarded-Proto=http to override the test harness's default scheme
            // so we're unambiguously simulating an LB forwarding a plain-HTTP client request.
            val response =
                noRedirectClient.get("/test") {
                    header("X-Forwarded-Proto", "http")
                }
            assertEquals(
                true,
                response.status.value in 300..399,
                "expected redirect, got ${response.status}",
            )
        }

    @Test
    fun `production profile lets forwarded-https requests through without redirect`() =
        testApplication {
            application { edgeModule(env = mapOf("LOGDATE_ENV" to "production")) }

            val response =
                client.get("/test") {
                    header("X-Forwarded-Proto", "https")
                    header("X-Forwarded-For", "203.0.113.42")
                }
            // XForwardedHeaders makes the request look like HTTPS to the HttpsRedirect plugin,
            // so the request reaches the handler instead of getting redirected.
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
        }

    @Test
    fun `development profile does not install HttpsRedirect`() =
        testApplication {
            application { edgeModule(env = emptyMap()) }

            val response = client.get("/test")
            assertEquals(HttpStatusCode.OK, response.status)
        }

    @Test
    fun `Atmosphere browser clients can preflight protocol requests without credentials`() =
        testApplication {
            application { edgeModule(env = mapOf("ALLOWED_ORIGINS" to "https://app.logdate.com")) }
            configureAuthV1TestApp()

            for (path in listOf("/xrpc/com.atproto.server.getSession", "/oauth/par", "/oauth/token", "/oauth/revoke")) {
                val response =
                    client.options(path) {
                        header(HttpHeaders.Origin, "https://reader.example")
                        header(HttpHeaders.AccessControlRequestMethod, if (path.startsWith("/xrpc/")) "GET" else "POST")
                        header(HttpHeaders.AccessControlRequestHeaders, "authorization, content-type, dpop, atproto-proxy")
                    }
                assertEquals(HttpStatusCode.OK, response.status, path)
                assertEquals("*", response.headers[HttpHeaders.AccessControlAllowOrigin], path)
                assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials], path)
                val allowed = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty().lowercase()
                assertTrue("dpop" in allowed && "atproto-proxy" in allowed, path)
            }
        }

    @Test
    fun `browser metadata and XRPC errors expose protocol response headers`() =
        testApplication {
            application { edgeModule(env = mapOf("ALLOWED_ORIGINS" to "https://app.logdate.com")) }
            configureAuthV1TestApp()
            val browserOrigin = "https://reader.example"

            for (path in listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-authorization-server", "/oauth/jwks")) {
                val metadata = client.get(path) { header(HttpHeaders.Origin, browserOrigin) }
                assertEquals(HttpStatusCode.OK, metadata.status, path)
                assertEquals("*", metadata.headers[HttpHeaders.AccessControlAllowOrigin], path)
            }
            val error = client.get("/xrpc/com.atproto.server.getSession") { header(HttpHeaders.Origin, browserOrigin) }
            assertEquals(HttpStatusCode.Unauthorized, error.status)
            assertEquals("*", error.headers[HttpHeaders.AccessControlAllowOrigin])
            val exposed = error.headers[HttpHeaders.AccessControlExposeHeaders].orEmpty().lowercase()
            assertTrue("dpop-nonce" in exposed && "www-authenticate" in exposed)

            val interactive = client.get("/oauth/authorize") { header(HttpHeaders.Origin, browserOrigin) }
            assertNull(interactive.headers[HttpHeaders.AccessControlAllowOrigin])
            val privateResponse = client.get("/test") { header(HttpHeaders.Origin, "https://app.logdate.com") }
            assertEquals("https://app.logdate.com", privateResponse.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("true", privateResponse.headers[HttpHeaders.AccessControlAllowCredentials])
        }

    private fun Application.edgeModule(env: Map<String, String>) {
        val reader: (String) -> String? = env::get
        val profile = RuntimeProfile.fromEnvironment(reader)
        installNetworkEdge(
            allowedOrigins = env["ALLOWED_ORIGINS"] ?: "",
            trustForwarded = profileAwareBoolEnv("TRUST_FORWARDED_HEADERS", true, true, reader, profile),
            requireHttps = profileAwareBoolEnv("REQUIRE_HTTPS", true, false, reader, profile),
        )
        routing {
            get("/test") { call.respondText("ok") }
        }
    }
}
