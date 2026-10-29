package app.logdate.server.routes

import app.logdate.server.bluesky.BlueskyAccountContinuationService
import app.logdate.server.di.installServerKoin
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class BlueskyAccountRoutesFailureTest {
    @Test
    fun `disabled runtime registers no account continuation routes`() =
        testApplication {
            application {
                installServerKoin(false) { null }
                blueskyAccountApiRoutes()
            }
            assertEquals(HttpStatusCode.NotFound, client.post("/api/v1$PATH").status)
        }

    @Test
    fun `malformed input and provider errors stay bounded and never issue a session`() =
        testApplication {
            val continuations = mockk<BlueskyAccountContinuationService>()
            coEvery { continuations.exchange(any(), any()) } throws IllegalArgumentException("private-provider-token")
            application {
                install(ContentNegotiation) { json() }
                routing { blueskyAccountRoutes(continuations, mockk()) { error("A failed proof must not issue a session") } }
            }
            val unavailable =
                client.post(PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(REQUEST)
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
            assertEquals("no-store", unavailable.headers["Cache-Control"])
            assertFalse(unavailable.bodyAsText().contains("private-provider-token"))
            val malformed =
                client.post(PATH) {
                    contentType(ContentType.Application.Json)
                    setBody("{invalid-private-marker")
                }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertFalse(malformed.bodyAsText().contains("private-marker"))
        }

    @Test
    fun `expired proofs cannot issue sessions and repeated requests are limited`() =
        testApplication {
            val continuations = mockk<BlueskyAccountContinuationService>()
            coEvery { continuations.exchange(any(), any()) } returns null
            application {
                install(ContentNegotiation) { json() }
                routing { blueskyAccountRoutes(continuations, mockk()) { error("An expired proof must not issue a session") } }
            }
            repeat(10) {
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client
                        .post(PATH) {
                            contentType(ContentType.Application.Json)
                            setBody(REQUEST)
                        }.status,
                )
            }
            val limited =
                client.post(PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(REQUEST)
                }
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertNotNull(limited.headers["Retry-After"])
            assertEquals("no-store", limited.headers["Cache-Control"])
        }

    private companion object {
        const val PATH = "/auth/bluesky/exchange"
        const val REQUEST = """{"attemptId":"attempt","verifier":"verifier"}"""
    }
}
