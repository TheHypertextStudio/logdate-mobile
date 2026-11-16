package app.logdate.server.routes

import app.logdate.server.ServerDescriptorConfig
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerInfoResponse
import app.logdate.shared.model.ServerProtocolFeature
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the server information and capability discovery endpoints.
 *
 * This class ensures that the `/server/info` route correctly reports the server's
 * deployment type—distinguishing between self-hosted and first-party environments—and
 * accurately lists available capabilities like authentication methods, sync features,
 * and billing support based on the server's active configuration.
 */
class ServerInfoRoutesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server info reports self hosted core capabilities`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    route("/api/v1") {
                        serverInfoRoutes(
                            ServerDescriptorConfig(
                                deploymentKind = DeploymentKind.SELF_HOSTED,
                                displayName = "Alice's LogDate",
                            ).toDescriptor(
                                identityConfig =
                                    AtprotoIdentityConfig(
                                        handleDomain = "journal.example.com",
                                        pdsServiceEndpoint = "https://journal.example.com",
                                    ),
                                webAuthnRpId = "journal.example.com",
                                webAuthnRpName = "Alice's LogDate",
                            ),
                        )
                    }
                }
            }

            val response = client.get("/api/v1/server/info")
            val payload = json.decodeFromString<ServerInfoResponse>(response.bodyAsText())

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(payload.success)
            assertEquals(DeploymentKind.SELF_HOSTED, payload.data.deploymentKind)
            assertEquals("https://journal.example.com", payload.data.serverOrigin)
            assertEquals("https://journal.example.com/api/v1", payload.data.apiBaseUrl)
            assertTrue(payload.data.capabilities.contains(ServerCapability.AUTH_PASSKEY))
            assertTrue(payload.data.protocolFeatures.contains(ServerProtocolFeature.CANONICAL_OWNER_BINDING_V1))
            assertTrue(payload.data.protocolFeatures.contains("journalMergeV1"))
            assertTrue(payload.data.capabilities.contains(ServerCapability.ATPROTO_IDENTITY))
            assertTrue(payload.data.capabilities.contains(ServerCapability.ATPROTO_OAUTH))
            assertTrue(payload.data.capabilities.contains(ServerCapability.SYNC_CONTENT))
            assertTrue(payload.data.capabilities.contains(ServerCapability.SYNC_MEDIA))
            assertTrue(
                payload.data.capabilities
                    .contains(ServerCapability.BILLING_SUBSCRIPTIONS)
                    .not(),
            )
            assertTrue(
                payload.data.capabilities
                    .contains(ServerCapability.MANAGED_QUOTA)
                    .not(),
            )
            assertTrue(
                payload.data.capabilities
                    .contains(ServerCapability.CLOUD_TRANSCRIPTION)
                    .not(),
            )
        }

    @Test
    fun `server info reports first party hosted capabilities`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(json)
                }
                routing {
                    route("/api/v1") {
                        serverInfoRoutes(
                            ServerDescriptorConfig(
                                deploymentKind = DeploymentKind.FIRST_PARTY,
                                displayName = "LogDate Cloud",
                            ).toDescriptor(
                                identityConfig =
                                    AtprotoIdentityConfig(
                                        handleDomain = "logdate.app",
                                        pdsServiceEndpoint = "https://cloud.logdate.app",
                                    ),
                                webAuthnRpId = "logdate.app",
                                webAuthnRpName = "LogDate Cloud",
                            ),
                        )
                    }
                }
            }

            val response = client.get("/api/v1/server/info")
            val payload = json.decodeFromString<ServerInfoResponse>(response.bodyAsText())

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(DeploymentKind.FIRST_PARTY, payload.data.deploymentKind)
            assertTrue(payload.data.capabilities.contains(ServerCapability.BILLING_SUBSCRIPTIONS))
            assertTrue(payload.data.capabilities.contains(ServerCapability.MANAGED_QUOTA))
            assertTrue(payload.data.capabilities.contains(ServerCapability.CLOUD_TRANSCRIPTION))
        }
}
