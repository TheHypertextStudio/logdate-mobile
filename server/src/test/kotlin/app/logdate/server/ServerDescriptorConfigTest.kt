package app.logdate.server

import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.HostedAccountDidMethod
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerProtocolFeature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerDescriptorConfigTest {
    @Test
    fun `Bluesky sign in requires its runtime and both private journal protocols`() {
        for (runtime in listOf(false, true)) {
            for (keys in listOf(false, true)) {
                for (space in listOf(false, true)) {
                    val config = ServerDescriptorConfig(encryptedAccountKeysEnabled = keys, personalSpaceEnabled = space)
                    val descriptor = config.toDescriptor(AtprotoIdentityConfig(), "logdate.app", "LogDate", blueskySignInEnabled = runtime)
                    assertEquals(runtime && keys && space, descriptor.hasProtocolFeature(ServerProtocolFeature.BLUESKY_SIGN_IN_V1))
                }
            }
        }
    }

    @Test
    fun `journal merge protocol is advertised for first party and self hosted deployments`() {
        for (kind in DeploymentKind.entries) {
            val descriptor = ServerDescriptorConfig(deploymentKind = kind).toDescriptor(AtprotoIdentityConfig(), "logdate.app", "LogDate")
            assertTrue(descriptor.hasProtocolFeature("journalMergeV1"))
        }
    }

    @Test
    fun `diagnostic reports use an additive protocol flag without changing known capabilities`() {
        val identity = AtprotoIdentityConfig()
        val config = ServerDescriptorConfig(deploymentKind = DeploymentKind.FIRST_PARTY)
        val baseline = config.toDescriptor(identity, "logdate.app", "LogDate")
        val enabled = config.toDescriptor(identity, "logdate.app", "LogDate", diagnosticReportsEnabled = true)
        val selfHosted =
            ServerDescriptorConfig().toDescriptor(
                identity,
                "logdate.app",
                "LogDate",
                diagnosticReportsEnabled = true,
            )

        assertFalse(baseline.hasProtocolFeature(ServerProtocolFeature.DIAGNOSTIC_REPORTS_V1))
        assertTrue(enabled.hasProtocolFeature(ServerProtocolFeature.DIAGNOSTIC_REPORTS_V1))
        assertTrue(selfHosted.hasProtocolFeature(ServerProtocolFeature.DIAGNOSTIC_REPORTS_V1))
        assertEquals(baseline.capabilities, enabled.capabilities)
    }

    @Test
    fun `encrypted unlock is opt in and never advertises server custody`() {
        val identity = AtprotoIdentityConfig()
        val baseline = ServerDescriptorConfig().toDescriptor(identity, "logdate.app", "LogDate")
        val enabled = ServerDescriptorConfig(encryptedAccountKeysEnabled = true).toDescriptor(identity, "logdate.app", "LogDate")
        assertFalse(baseline.hasProtocolFeature(ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1))
        assertTrue(enabled.hasProtocolFeature(ServerProtocolFeature.ENCRYPTED_ACCOUNT_KEYS_V1))
        assertFalse(baseline.hasProtocolFeature(ServerProtocolFeature.ACCOUNT_KEY_VAULT_V1))
        assertFalse(enabled.hasProtocolFeature(ServerProtocolFeature.ACCOUNT_KEY_VAULT_V1))
        assertEquals(baseline.capabilities, enabled.capabilities)
    }

    @Test
    fun `personal spaces are advertised only when the flag is on`() {
        val identity = AtprotoIdentityConfig()
        val baseline = ServerDescriptorConfig.fromEnvironment(personalSpaceEnabled = false).toDescriptor(identity, "logdate.app", "LogDate")
        val enabled = ServerDescriptorConfig.fromEnvironment(personalSpaceEnabled = true).toDescriptor(identity, "logdate.app", "LogDate")
        assertFalse(ServerDescriptorConfig().personalSpaceEnabled)
        assertFalse(baseline.hasProtocolFeature(ServerProtocolFeature.PERSONAL_SPACE_V1))
        assertTrue(enabled.hasProtocolFeature(ServerProtocolFeature.PERSONAL_SPACE_V1))
        assertEquals(baseline.capabilities, enabled.capabilities)
    }

    @Test
    fun `first-party environment advertises LogDate Cloud defaults`() {
        val config = ServerDescriptorConfig.fromEnvironment(deploymentKind = "first_party")

        assertEquals(DeploymentKind.FIRST_PARTY, config.deploymentKind)
        assertEquals("LogDate Cloud", config.displayName)
        assertEquals("https://logdate.app/privacy", config.privacyPolicyUrl)
        assertEquals("https://logdate.app/terms", config.termsOfServiceUrl)
    }

    @Test
    fun `unset and self-hosted environments advertise server defaults`() {
        val unset = ServerDescriptorConfig.fromEnvironment()
        val explicit = ServerDescriptorConfig.fromEnvironment(deploymentKind = "self_hosted")

        listOf(unset, explicit).forEach { config ->
            assertEquals(DeploymentKind.SELF_HOSTED, config.deploymentKind)
            assertEquals("LogDate Server", config.displayName)
            assertNull(config.privacyPolicyUrl)
            assertNull(config.termsOfServiceUrl)
        }
    }

    @Test
    fun `PLC publishing is advertised only when hosted PLC operations are published`() {
        val publishing = descriptorFor(AtprotoIdentityConfig(publishHostedPlcOperations = true))
        val notPublishing = descriptorFor(AtprotoIdentityConfig(publishHostedPlcOperations = false))

        assertTrue(publishing.hasProtocolFeature(ServerProtocolFeature.ATPROTO_PLC_PUBLISHING_V1))
        assertFalse(notPublishing.hasProtocolFeature(ServerProtocolFeature.ATPROTO_PLC_PUBLISHING_V1))
    }

    @Test
    fun `recovery keys are advertised only when enabled and only for did plc accounts`() {
        val enabled = descriptorFor(AtprotoIdentityConfig(recoveryKeysEnabled = true))
        val publishing = descriptorFor(AtprotoIdentityConfig(publishHostedPlcOperations = true))
        val neither = descriptorFor(AtprotoIdentityConfig())
        val web = descriptorFor(AtprotoIdentityConfig(hostedAccountDidMethod = HostedAccountDidMethod.WEB, recoveryKeysEnabled = true))

        assertTrue(enabled.hasProtocolFeature(ServerProtocolFeature.ATPROTO_RECOVERY_KEYS_V1))
        assertFalse(enabled.hasProtocolFeature(ServerProtocolFeature.ATPROTO_PLC_PUBLISHING_V1))
        assertFalse(publishing.hasProtocolFeature(ServerProtocolFeature.ATPROTO_RECOVERY_KEYS_V1))
        assertFalse(neither.hasProtocolFeature(ServerProtocolFeature.ATPROTO_RECOVERY_KEYS_V1))
        assertFalse(web.hasProtocolFeature(ServerProtocolFeature.ATPROTO_RECOVERY_KEYS_V1))
    }

    @Test
    fun `PLC publishing is not advertised for did web accounts`() {
        val descriptor =
            descriptorFor(
                AtprotoIdentityConfig(
                    hostedAccountDidMethod = HostedAccountDidMethod.WEB,
                    publishHostedPlcOperations = true,
                ),
            )

        assertFalse(descriptor.hasProtocolFeature(ServerProtocolFeature.ATPROTO_PLC_PUBLISHING_V1))
        assertTrue(descriptor.hasProtocolFeature(ServerProtocolFeature.CANONICAL_OWNER_BINDING_V1))
    }

    @Test
    fun `device linking is advertised on every deployment`() {
        val identity = AtprotoIdentityConfig()
        listOf(DeploymentKind.FIRST_PARTY, DeploymentKind.SELF_HOSTED).forEach { kind ->
            val descriptor = ServerDescriptorConfig(deploymentKind = kind).toDescriptor(identity, "logdate.app", "LogDate")
            assertTrue(descriptor.hasProtocolFeature(ServerProtocolFeature.DEVICE_LINKING_V1), kind.name)
        }
    }

    @Test
    fun `strict create-only uploads are advertised on every deployment`() {
        val identity = AtprotoIdentityConfig()
        listOf(DeploymentKind.FIRST_PARTY, DeploymentKind.SELF_HOSTED).forEach { kind ->
            val descriptor = ServerDescriptorConfig(deploymentKind = kind).toDescriptor(identity, "logdate.app", "LogDate")
            assertTrue(descriptor.hasProtocolFeature(ServerProtocolFeature.CONDITIONAL_CREATE_V1), kind.name)
        }
    }

    @Test
    fun `marked moments are advertised on every deployment`() {
        val identity = AtprotoIdentityConfig()
        listOf(DeploymentKind.FIRST_PARTY, DeploymentKind.SELF_HOSTED).forEach { kind ->
            val descriptor = ServerDescriptorConfig(deploymentKind = kind).toDescriptor(identity, "logdate.app", "LogDate")
            assertTrue(descriptor.hasProtocolFeature(ServerProtocolFeature.MOMENTS_V1), kind.name)
        }
    }

    private fun descriptorFor(identityConfig: AtprotoIdentityConfig) =
        ServerDescriptorConfig().toDescriptor(
            identityConfig = identityConfig,
            webAuthnRpId = "logdate.app",
            webAuthnRpName = "LogDate",
        )
}
