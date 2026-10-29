package app.logdate.client.data.account

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerCapability
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerPasskeyConfig
import app.logdate.shared.model.ServerProtocolFeature
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlueskySignInAvailabilityTest {
    @Test
    fun `local opt in and the exact current server must offer the complete sign in protocol`() {
        assertFalse(FeatureFlag.BLUESKY_SIGN_IN.defaultEnabled)
        assertTrue(blueskySignInAvailable(true, ORIGIN, descriptor()))
        assertFalse(blueskySignInAvailable(false, ORIGIN, descriptor()))
        assertFalse(blueskySignInAvailable(true, ORIGIN, null))
        assertFalse(blueskySignInAvailable(true, "https://other.logdate.app", descriptor()))
        assertFalse(blueskySignInAvailable(true, ORIGIN, descriptor().copy(apiBaseUrl = "https://other.logdate.app/api/v1")))
        assertFalse(blueskySignInAvailable(true, ORIGIN, descriptor().copy(passkey = null)))
        assertFalse(blueskySignInAvailable(true, ORIGIN, descriptor().copy(capabilities = emptyList())))
        for (feature in FEATURES) {
            assertFalse(blueskySignInAvailable(true, ORIGIN, descriptor().copy(protocolFeatures = FEATURES - feature)))
        }
    }

    private fun descriptor() =
        ServerDescriptor(
            serverOrigin = ORIGIN,
            apiBaseUrl = "$ORIGIN/api/v1",
            deploymentKind = DeploymentKind.FIRST_PARTY,
            displayName = "LogDate",
            passkey = ServerPasskeyConfig("logdate.app", "LogDate"),
            capabilities = listOf(ServerCapability.AUTH_PASSKEY),
            protocolFeatures = FEATURES,
        )

    private companion object {
        const val ORIGIN = "https://cloud.logdate.app"
        val FEATURES =
            listOf(
                ServerProtocolFeature.BLUESKY_SIGN_IN_V1,
                ServerProtocolFeature.PERSONAL_SPACE_V1,
                ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1,
            )
    }
}
