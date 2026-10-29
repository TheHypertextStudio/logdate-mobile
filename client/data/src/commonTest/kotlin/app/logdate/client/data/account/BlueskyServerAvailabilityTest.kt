package app.logdate.client.data.account

import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerPasskeyConfig
import app.logdate.shared.model.ServerProtocolFeature
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueskyServerAvailabilityTest {
    @Test
    fun `cold start reloads only the configured server and reuses current metadata`() =
        runTest {
            val config = DefaultLogDateConfigRepository(ORIGIN)
            var requests = 0
            val availability =
                BlueskyServerAvailability(config, { true }) { origin ->
                    requests++
                    assertEquals(ORIGIN, origin)
                    Result.success(descriptor())
                }
            assertTrue(availability.prepare())
            assertEquals(descriptor(), config.getCurrentServerDescriptor())
            assertTrue(availability.prepare())
            assertEquals(1, requests)
        }

    @Test
    fun `a server change while discovery is running cannot install old metadata`() =
        runTest {
            val config = DefaultLogDateConfigRepository(ORIGIN)
            val availability =
                BlueskyServerAvailability(config, { true }) {
                    config.updateBackendUrl("https://other.logdate.app")
                    Result.success(descriptor())
                }
            assertFalse(availability.prepare())
            assertNull(config.getCurrentServerDescriptor())
        }

    @Test
    fun `disabled or explicitly unsupported servers do not trigger discovery`() =
        runTest {
            val config = DefaultLogDateConfigRepository(ORIGIN)
            var requests = 0
            val discover: suspend (String) -> Result<ServerDescriptor> = {
                requests++
                Result.success(descriptor())
            }
            assertFalse(BlueskyServerAvailability(config, { false }, discover).prepare())
            config.updateServerDescriptor(descriptor().copy(protocolFeatures = emptyList()))
            assertFalse(BlueskyServerAvailability(config, { true }, discover).prepare())
            assertEquals(0, requests)
        }

    private fun descriptor() =
        ServerDescriptor(
            serverOrigin = ORIGIN,
            apiBaseUrl = "$ORIGIN/api/v1",
            deploymentKind = DeploymentKind.FIRST_PARTY,
            displayName = "LogDate",
            passkey = ServerPasskeyConfig("logdate.app", "LogDate"),
            capabilities = listOf(ServerCapability.AUTH_PASSKEY),
            protocolFeatures =
                listOf(
                    ServerProtocolFeature.BLUESKY_SIGN_IN_V1,
                    ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1,
                    ServerProtocolFeature.PERSONAL_SPACE_V1,
                ),
        )

    private companion object {
        const val ORIGIN = "https://cloud.logdate.app"
    }
}
