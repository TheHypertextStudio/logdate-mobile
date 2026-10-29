package app.logdate.server.routes

import app.logdate.server.bluesky.BlueskyOAuthRuntime
import app.logdate.server.di.installServerKoin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.identity.IdentityResolver
import studio.hypertext.atproto.oauth.client.AtprotoOAuthClient
import studio.hypertext.atproto.oauth.client.InMemoryOAuthSessionStore
import studio.hypertext.atproto.oauth.client.InMemoryOAuthStateStore
import studio.hypertext.atproto.oauth.client.JvmOAuthCrypto
import studio.hypertext.atproto.oauth.client.OAuthClientConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueskyMetadataRoutesTest {
    @Test
    fun `disabled sign-in has no client metadata endpoint`() =
        testApplication {
            application {
                installServerKoin(false) { null }
                blueskyApiRoutes()
            }
            assertEquals(HttpStatusCode.NotFound, client.get(PATH).status)
        }

    @Test
    fun `anonymous metadata declares only sign-in scope and public keys`() {
        val key = JvmOAuthCrypto.generateClientKey("fixture-metadata-key")
        val http = HttpClient(MockEngine { error("Serving metadata must not contact a provider") })
        val oauth =
            AtprotoOAuthClient(
                config = OAuthClientConfig(CLIENT_ID, listOf("https://cloud.logdate.app/oauth/bluesky/callback"), listOf(key)),
                identityResolver = mockk<IdentityResolver>(),
                httpClient = http,
                crypto = JvmOAuthCrypto,
                stateStore = InMemoryOAuthStateStore(),
                sessionStore = InMemoryOAuthSessionStore(),
            )
        val runtime = mockk<BlueskyOAuthRuntime>(relaxed = true)
        every { runtime.client } returns oauth
        try {
            testApplication {
                application {
                    installServerKoin(false) { runtime }
                    blueskyApiRoutes()
                }
                val response = client.get(PATH)
                assertEquals(HttpStatusCode.OK, response.status)
                assertTrue(response.headers[HttpHeaders.ContentType]!!.startsWith("application/json"))
                assertEquals("public, max-age=300", response.headers[HttpHeaders.CacheControl])
                val body = response.bodyAsText()
                val metadata = Json.parseToJsonElement(body).jsonObject
                assertEquals(CLIENT_ID, metadata["client_id"]!!.jsonPrimitive.content)
                assertEquals("atproto", metadata["scope"]!!.jsonPrimitive.content)
                assertEquals("private_key_jwt", metadata["token_endpoint_auth_method"]!!.jsonPrimitive.content)
                val published =
                    metadata["jwks"]!!
                        .jsonObject["keys"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(key.kid, published["kid"]!!.jsonPrimitive.content)
                assertEquals(key.publicJwk.x, published["x"]!!.jsonPrimitive.content)
                assertFalse(published.containsKey("d"))
                assertFalse(body.contains(requireNotNull(key.privateJwk.d)))
            }
        } finally {
            oauth.close()
            http.close()
        }
    }

    private companion object {
        const val PATH = "/oauth/client-metadata.json"
        const val CLIENT_ID = "https://cloud.logdate.app$PATH"
    }
}
