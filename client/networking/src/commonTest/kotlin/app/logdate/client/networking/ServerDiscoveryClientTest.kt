package app.logdate.client.networking

import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerCapability
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests the server discovery mechanism used to bootstrap communication with a LogDate server.
 *
 * This suite verifies that [DefaultServerDiscoveryClient] can successfully retrieve and
 * parse server metadata, including deployment details and supported capabilities, from
 * a well-known discovery endpoint.
 */
class ServerDiscoveryClientTest {
    private fun clientAnswering(engine: MockEngine) =
        DefaultServerDiscoveryClient(HttpClient(engine) { install(ContentNegotiation) { json() } })

    private suspend fun problemFrom(engine: MockEngine): DiscoveryFailure {
        val error = clientAnswering(engine).discoverServer("https://cloud.logdate.app").exceptionOrNull()
        return assertIs<ServerDiscoveryException>(error).kind
    }

    @Test
    fun `a gateway timeout means the server is not responding`() =
        runTest {
            val engine =
                MockEngine {
                    respond("upstream request timeout", HttpStatusCode.GatewayTimeout, headersOf(HttpHeaders.ContentType, "text/plain"))
                }

            assertEquals(DiscoveryFailure.NOT_RESPONDING, problemFrom(engine))
        }

    @Test
    fun `a request that cannot connect means the server is unreachable`() =
        runTest {
            val engine = MockEngine { throw IOException("No route to host") }

            assertEquals(DiscoveryFailure.UNREACHABLE, problemFrom(engine))
        }

    @Test
    fun `an answer that is not server information is unreadable`() =
        runTest {
            val engine =
                MockEngine {
                    respond("<html>LogDate</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }

            assertEquals(DiscoveryFailure.UNREADABLE, problemFrom(engine))
        }

    @Test
    fun `discoverServer reads descriptor from selected origin`() =
        runTest {
            val mockEngine =
                MockEngine { request ->
                    assertEquals("https://journal.example.com/api/v1/server/info", request.url.toString())
                    respond(
                        content =
                            """
                            {
                              "success": true,
                              "data": {
                                "serverOrigin": "https://journal.example.com",
                                "apiBaseUrl": "https://journal.example.com/api/v1",
                                "apiVersion": "v1",
                                "deploymentKind": "SELF_HOSTED",
                                "displayName": "Alice's LogDate",
                                "handleDomain": "journal.example.com",
                                "passkey": {
                                  "rpId": "journal.example.com",
                                  "rpName": "Alice's LogDate"
                                },
                                "capabilities": [
                                  "AUTH_PASSKEY",
                                  "SYNC_CONTENT",
                                  "SYNC_MEDIA",
                                  "ATPROTO_IDENTITY",
                                  "ATPROTO_OAUTH"
                                ]
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            val httpClient =
                HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                }

            val result = DefaultServerDiscoveryClient(httpClient).discoverServer("https://journal.example.com")

            assertTrue(result.isSuccess)
            val descriptor = result.getOrThrow()
            assertEquals(DeploymentKind.SELF_HOSTED, descriptor.deploymentKind)
            assertEquals("https://journal.example.com/api/v1", descriptor.apiBaseUrl)
            assertTrue(descriptor.capabilities.contains(ServerCapability.ATPROTO_OAUTH))
        }
}
