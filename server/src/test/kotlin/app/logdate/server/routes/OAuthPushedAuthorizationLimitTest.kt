package app.logdate.server.routes

import app.logdate.server.oauth.OAuthAuthorizationService
import app.logdate.server.oauth.OAuthConfig
import app.logdate.server.oauth.OAuthKeyService
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins that `/oauth/par`, which anyone can call, is limited per client address before the server
 * does any work for the request.
 */
class OAuthPushedAuthorizationLimitTest {
    @Test
    fun `pushed authorization requests beyond the per-address budget are answered with 429`() =
        testApplication {
            val limiter = SlidingWindowRateLimiter()
            application {
                install(ContentNegotiation) { json() }
                routing {
                    oauthRoutes(
                        config = OAuthConfig(issuer = "https://logdate.app"),
                        keyService = OAuthKeyService(),
                        authorizationService = mockk<OAuthAuthorizationService>(relaxed = true),
                        pushedAuthorizationRateLimiter = limiter,
                    )
                }
            }

            val statuses =
                (1..31).map {
                    client.post("/oauth/par") { setBody(FormDataContent(Parameters.Empty)) }
                }

            // Without a DPoP proof each request within the budget is refused as invalid.
            statuses.take(30).forEach { assertEquals(HttpStatusCode.BadRequest, it.status) }
            val limited = statuses.last()
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers[HttpHeaders.RetryAfter])
        }
}
