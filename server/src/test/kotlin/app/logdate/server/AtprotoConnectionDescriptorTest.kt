package app.logdate.server

import app.logdate.server.identity.AtprotoIdentityConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtprotoConnectionDescriptorTest {
    @Test
    fun `a compatible enabled runtime advertises the connection contract`() {
        assertTrue(descriptor(connection = true, signIn = true).protocolFeatures.contains("atprotoConnectionV1"))
    }

    @Test
    fun `connection operations remain undiscoverable by default`() {
        assertFalse(descriptor(connection = false, signIn = true).protocolFeatures.contains("atprotoConnectionV1"))
    }

    @Test
    fun `a connection flag without the confidential sign in runtime grants no capability`() {
        assertFalse(descriptor(connection = true, signIn = false).protocolFeatures.contains("atprotoConnectionV1"))
    }

    @Test
    fun `incomplete account key or journal support grants no connection capability`() {
        assertFalse(descriptor(connection = true, signIn = true, keys = false).protocolFeatures.contains("atprotoConnectionV1"))
        assertFalse(descriptor(connection = true, signIn = true, space = false).protocolFeatures.contains("atprotoConnectionV1"))
    }

    private fun descriptor(
        connection: Boolean,
        signIn: Boolean,
        keys: Boolean = true,
        space: Boolean = true,
    ) = ServerDescriptorConfig(encryptedAccountKeysEnabled = keys, personalSpaceEnabled = space).toDescriptor(
        identityConfig = AtprotoIdentityConfig("logdate.app", "https://cloud.logdate.app"),
        webAuthnRpId = "logdate.app",
        webAuthnRpName = "LogDate",
        blueskySignInEnabled = signIn,
        atprotoConnectionEnabled = connection,
    )
}
