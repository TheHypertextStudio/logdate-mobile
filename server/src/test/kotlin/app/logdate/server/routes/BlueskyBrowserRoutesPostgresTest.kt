package app.logdate.server.routes

import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.bluesky.BlueskyAccountContinuationService
import app.logdate.server.bluesky.BlueskyProtocolHarness
import app.logdate.server.database.withBlueskyOAuthDatabase
import app.logdate.shared.model.BlueskyBrowserReturn
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.BlueskyStartResponse
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.syntax.Handle
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BlueskyBrowserRoutesPostgresTest {
    @Test
    fun `HTTP start and verified callback return only the bound public attempt to the fixed app link`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { provider ->
                val signIn = provider.service()
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        routing {
                            blueskyCallbackRoutes(signIn)
                            route("/api/v1") {
                                blueskyAccountRoutes(mockk(), mockk(), signIn) { error("Browser authorization is not a LogDate session") }
                            }
                        }
                    }
                    val browser = createClient { followRedirects = false }
                    val begin =
                        browser.post("/api/v1/auth/bluesky/start") {
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(BlueskyStartRequest(provider.atmosphere.alice.handle, CHALLENGE)))
                        }
                    assertEquals(HttpStatusCode.OK, begin.status)
                    assertEquals("no-store", begin.headers["Cache-Control"])
                    val start = Json.decodeFromString<BlueskyStartResponse>(begin.bodyAsText())
                    val parameters = provider.atmosphere.approve(start.authorizationUrl)
                    val callback = browser.get("$CALLBACK?${parameters.toList().formUrlEncode()}")
                    assertEquals(HttpStatusCode.SeeOther, callback.status)
                    assertEquals("${BlueskyBrowserReturn.URL}?attempt=${start.attemptId}", callback.headers["Location"])
                    assertEquals("no-referrer", callback.headers["Referrer-Policy"])
                    assertEquals("no-store", callback.headers["Cache-Control"])
                    assertEquals(provider.atmosphere.alice.did, signIn.exchange(start.attemptId, VERIFIER)?.did.toString())
                    assertFalse(callback.bodyAsText().contains(VERIFIER))
                    assertNull(callback.headers["Set-Cookie"])
                }
            }
        }

    @Test
    fun `duplicate and ambiguous callbacks cannot consume a valid pending authorization`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { provider ->
                val signIn = provider.service()
                val start = signIn.start(Handle.require(provider.atmosphere.alice.handle), CHALLENGE)
                val good = provider.atmosphere.approve(start.authorizationUrl).toList()
                val before = provider.atmosphere.requestLog.size
                testApplication {
                    application { routing { blueskyCallbackRoutes(signIn) } }
                    val browser = createClient { followRedirects = false }
                    for (bad in listOf(
                        good + ("state" to "other-state"),
                        good + ("error" to "access_denied"),
                        good + listOf("other" to "one", "other" to "two"),
                    )) {
                        val response = browser.get("$CALLBACK?${bad.formUrlEncode()}")
                        assertEquals(HttpStatusCode.SeeOther, response.status)
                        assertEquals(BlueskyBrowserReturn.ERROR_URL, response.headers["Location"])
                        assertEquals(before, provider.atmosphere.requestLog.size)
                    }
                    val response = browser.get("$CALLBACK?${good.formUrlEncode()}")
                    assertEquals("${BlueskyBrowserReturn.URL}?attempt=${start.attemptId}", response.headers["Location"])
                }
                assertEquals(provider.atmosphere.alice.did, signIn.exchange(start.attemptId, VERIFIER)?.did.toString())
            }
        }

    @Test
    fun `declined consent returns to its app and exchanges a bounded cancellation once`() =
        withBlueskyOAuthDatabase { source ->
            BlueskyProtocolHarness(source).use { provider ->
                val signIn = provider.service()
                val start = signIn.start(Handle.require(provider.atmosphere.alice.handle), CHALLENGE)
                val denied = provider.atmosphere.deny(start.authorizationUrl) + ("error_description" to "private-provider-marker")
                val continuations = BlueskyAccountContinuationService(signIn, InMemoryAccountRepository(), mockk())
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        routing {
                            blueskyCallbackRoutes(signIn)
                            route("/api/v1") { blueskyAccountRoutes(continuations, mockk()) { error("Declining cannot sign in") } }
                        }
                    }
                    val browser = createClient { followRedirects = false }
                    val callback = browser.get("$CALLBACK?${denied.toList().formUrlEncode()}")
                    assertEquals("${BlueskyBrowserReturn.URL}?attempt=${start.attemptId}", callback.headers["Location"])
                    val exchange =
                        browser.post("/api/v1/auth/bluesky/exchange") {
                            contentType(ContentType.Application.Json)
                            setBody(Json.encodeToString(BlueskyExchangeRequest(start.attemptId, VERIFIER)))
                        }
                    assertEquals(HttpStatusCode.BadRequest, exchange.status)
                    assertFalse(exchange.bodyAsText().contains("private-provider-marker"))
                    assertFalse(callback.bodyAsText().contains("private-provider-marker"))
                    kotlin.test.assertTrue(exchange.bodyAsText().contains("BLUESKY_CANCELED"))
                }
                assertNull(signIn.exchange(start.attemptId, VERIFIER))
            }
        }

    private companion object {
        const val CALLBACK = "/oauth/bluesky/callback"
        const val VERIFIER = "native-app-VERIFIER-with-at-least-forty-three-bytes-12345"
        val CHALLENGE = Base64.getUrlEncoder().withoutPadding().encodeToString(JvmOAuthCrypto.sha256(VERIFIER.encodeToByteArray()))
    }
}
