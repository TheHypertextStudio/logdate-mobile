package app.logdate.server.auth

import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Validates the Google ID Token verification logic.
 *
 * This suite tests the validation of OpenID Connect (OIDC) tokens issued by Google,
 * ensuring they meet strict security requirements including issuer validity,
 * audience matching, expiration checks, and nonce verification. It covers both
 * the HTTP-based verifier and a fake implementation for testing.
 */
class GoogleIdTokenVerifierTest {
    @Test
    fun `blank token and empty client-id config return null`() {
        val now = System.currentTimeMillis() / 1000
        val okBody =
            """
            {
              "sub":"sub-1",
              "email":"user@example.com",
              "email_verified":"true",
              "iss":"https://accounts.google.com",
              "aud":"client-1",
              "exp":"${now + 3600}",
              "iat":"${now - 60}"
            }
            """.trimIndent()

        val verifierWithClientIds =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient = StubHttpClient { SimpleStringResponse(200, okBody) },
            )
        val verifierWithoutClientIds =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = emptySet(),
                httpClient = StubHttpClient { SimpleStringResponse(200, okBody) },
            )

        assertNull(runSuspend { verifierWithClientIds.verify("", null) })
        assertTrue(verifierWithClientIds.isConfigured())
        assertTrue(!verifierWithoutClientIds.isConfigured())
        assertNull(runSuspend { verifierWithoutClientIds.verify("token", null) })
    }

    @Test
    fun `http status failures and exceptions return null`() {
        val non200Verifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient = StubHttpClient { SimpleStringResponse(401, "{}") },
            )
        val throwingVerifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient = StubHttpClient { throw IllegalStateException("boom") },
            )

        assertNull(runSuspend { non200Verifier.verify("token", null) })
        assertNull(runSuspend { throwingVerifier.verify("token", null) })
    }

    @Test
    fun `valid response produces claims and invalid constraints are rejected`() {
        val now = System.currentTimeMillis() / 1000
        val baseBody =
            """
            {
              "sub":"sub-2",
              "email":"valid@example.com",
              "email_verified":"true",
              "name":"Valid User",
              "picture":"https://example.com/a.png",
              "iss":"https://accounts.google.com",
              "aud":"client-1",
              "exp":"${now + 7200}",
              "iat":"${now - 120}",
              "nonce":"nonce-1"
            }
            """.trimIndent()

        val verifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient = StubHttpClient { SimpleStringResponse(200, baseBody) },
            )

        val claims = runSuspend { verifier.verify("token", "nonce-1") }
        assertNotNull(claims)
        assertEquals("sub-2", claims.subject)
        assertEquals("valid@example.com", claims.email)
        assertEquals("https://example.com/a.png", claims.picture)
        assertEquals("https://accounts.google.com", claims.issuer)
        assertEquals("client-1", claims.audience)
        assertTrue(claims.expiresAtEpochSeconds > claims.issuedAtEpochSeconds)

        val badAudienceVerifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-x"),
                httpClient = StubHttpClient { SimpleStringResponse(200, baseBody) },
            )
        assertNull(runSuspend { badAudienceVerifier.verify("token", null) })

        val badIssuerBody = baseBody.replace("https://accounts.google.com", "https://issuer.invalid")
        val badIssuerVerifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient = StubHttpClient { SimpleStringResponse(200, badIssuerBody) },
            )
        assertNull(runSuspend { badIssuerVerifier.verify("token", null) })

        val expiredBody = baseBody.replace("\"exp\":\"${now + 7200}\"", "\"exp\":\"${now - 1}\"")
        val expiredVerifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient = StubHttpClient { SimpleStringResponse(200, expiredBody) },
            )
        assertNull(runSuspend { expiredVerifier.verify("token", null) })

        assertNull(runSuspend { verifier.verify("token", "different-nonce") })
    }

    @Test
    fun `a token without a nonce is refused when the sign-in expects one`() {
        val now = System.currentTimeMillis() / 1000
        val bodyWithoutNonce =
            """
            {
              "sub":"sub-3",
              "email":"user@example.com",
              "email_verified":"true",
              "iss":"https://accounts.google.com",
              "aud":"client-1",
              "exp":"${now + 3600}",
              "iat":"${now - 60}"
            }
            """.trimIndent()
        val verifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient = StubHttpClient { SimpleStringResponse(200, bodyWithoutNonce) },
            )

        assertNull(runSuspend { verifier.verify("token", "expected-nonce") })
        assertNotNull(runSuspend { verifier.verify("token", null) })
    }

    @Test
    fun `the tokeninfo call is asynchronous and bounded by a timeout`() {
        val now = System.currentTimeMillis() / 1000
        val body =
            """
            {"sub":"sub-4","email":"user@example.com","email_verified":"true","iss":"https://accounts.google.com",
             "aud":"client-1","exp":"${now + 3600}","iat":"${now - 60}"}
            """.trimIndent()
        var seen: HttpRequest? = null
        val verifier =
            HttpGoogleIdTokenVerifier(
                allowedClientIds = setOf("client-1"),
                httpClient =
                    StubHttpClient(blockingSendAllowed = false) { request ->
                        seen = request
                        SimpleStringResponse(200, body)
                    },
                requestTimeout = Duration.ofSeconds(3),
            )

        assertNotNull(runSuspend { verifier.verify("token", null) })
        assertEquals(Optional.of(Duration.ofSeconds(3)), seen?.timeout())
    }

    @Test
    fun `fake verifier returns mapped token claims`() {
        val claim =
            GoogleIdTokenClaims(
                subject = "sub-fake",
                email = "fake@example.com",
                emailVerified = true,
                name = "Fake",
                picture = null,
                issuer = "https://accounts.google.com",
                audience = "client-1",
                expiresAtEpochSeconds = 9_999_999_999,
                issuedAtEpochSeconds = 1,
            )
        val verifier = FakeGoogleIdTokenVerifier(tokens = mapOf("token-1" to claim))
        val disabledVerifier = FakeGoogleIdTokenVerifier(tokens = emptyMap(), configured = false)

        assertEquals(claim, runSuspend { verifier.verify("token-1", null) })
        assertEquals(claim, runSuspend { verifier.verify("token-1", null) })
        assertNull(runSuspend { verifier.verify("missing", null) })
        assertTrue(verifier.isConfigured())
        assertTrue(!disabledVerifier.isConfigured())

        val serializer = GoogleIdTokenClaims.Companion.serializer()
        assertNotNull(serializer)
    }

    private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }

    private class StubHttpClient(
        private val blockingSendAllowed: Boolean = true,
        private val handler: (HttpRequest) -> HttpResponse<String>,
    ) : HttpClient() {
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<Executor> = Optional.empty()

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            check(blockingSendAllowed) { "The verifier must not block a thread on HttpClient.send" }
            return respond(request)
        }

        // The stub only ever supplies HttpResponse<String>; the cast is necessary because
        // HttpClient.send is generic at the call-site but our handler is String-specialized.
        // Verifier callers always request String bodies.
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any?> respond(request: HttpRequest): HttpResponse<T> = handler(request) as HttpResponse<T>

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> =
            runCatching { respond<T>(request) }
                .fold({ CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture(it) })

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>?,
        ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)
    }

    private class SimpleStringResponse(
        private val status: Int,
        private val responseBody: String,
    ) : HttpResponse<String> {
        override fun statusCode(): Int = status

        override fun request(): HttpRequest =
            HttpRequest
                .newBuilder()
                .uri(URI.create("https://oauth2.googleapis.com/tokeninfo"))
                .GET()
                .build()

        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun body(): String = responseBody

        override fun sslSession(): Optional<javax.net.ssl.SSLSession> = Optional.empty()

        override fun uri(): URI = URI.create("https://oauth2.googleapis.com/tokeninfo")

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }
}
