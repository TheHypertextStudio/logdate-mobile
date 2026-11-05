package app.logdate.server.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [AtprotoIdentityConfig], ensuring the server's identity management
 * is correctly configured through defaults and environment overrides.
 *
 * This suite verifies:
 * - Proper initialization of default values for the LogDate ecosystem.
 * - Robust environment variable parsing, including normalization of hostnames,
 *   casing, and trailing slashes for PDS and PLC endpoints.
 * - Correct handling of configuration flags for PLC operation publishing and
 *   DID method selection.
 * - Strict validation to prevent unsupported or invalid identity configurations
 *   from being used at runtime.
 */
class AtprotoIdentityConfigTest {
    @Test
    fun `default config uses logdate host defaults`() {
        val config = AtprotoIdentityConfig()

        assertEquals("logdate.app", config.handleDomain)
        assertEquals("logdate.app", config.normalizedHandleDomain)
        assertEquals(HostedAccountDidMethod.PLC, config.hostedAccountDidMethod)
        assertEquals("did:web:logdate.app", config.serverDid)
        assertEquals("https://logdate.app", config.pdsServiceEndpoint)
        assertEquals("https://plc.directory", config.plcDirectoryUrl)
        assertEquals("https://plc.directory", config.normalizedPlcDirectoryUrl)
    }

    @Test
    fun `environment factory normalizes host casing and trailing slashes`() {
        val config = AtprotoIdentityConfig.fromEnvironment(handleDomain = " LogDate.App. ", pdsServiceEndpoint = "https://pds.logdate.app/")

        assertEquals("logdate.app", config.handleDomain)
        assertEquals("https://pds.logdate.app", config.pdsServiceEndpoint)
        assertEquals(HostedAccountDidMethod.PLC, config.hostedAccountDidMethod)
        assertEquals("did:web:logdate.app", config.serverDid)
    }

    @Test
    fun `environment factory supports hosted did method with private provisioning`() {
        val config =
            AtprotoIdentityConfig.fromEnvironment(
                handleDomain = "users.logdate.app",
                pdsServiceEndpoint = "https://pds.logdate.app/",
                hostedAccountDidMethod = "web",
                publishHostedPlcOperations = "false",
                plcDirectoryUrl = "https://plc.example.com/",
            )

        assertEquals(HostedAccountDidMethod.WEB, config.hostedAccountDidMethod)
        assertFalse(config.publishHostedPlcOperations)
        assertEquals("https://plc.example.com", config.normalizedPlcDirectoryUrl)
    }

    @Test
    fun `environment factory refuses retired bulk publication configuration`() {
        for (value in listOf("true", " TrUe ", "1")) {
            assertFailsWith<IllegalArgumentException> {
                AtprotoIdentityConfig.fromEnvironment(publishHostedPlcOperations = value)
            }
        }
    }

    @Test
    fun `environment factory accepts explicit plc hosted did method and disabled publish flag`() {
        val config =
            AtprotoIdentityConfig.fromEnvironment(
                hostedAccountDidMethod = "plc",
                publishHostedPlcOperations = "false",
            )

        assertEquals(HostedAccountDidMethod.PLC, config.hostedAccountDidMethod)
        assertEquals(false, config.publishHostedPlcOperations)
    }

    @Test
    fun `environment factory reads the recovery keys flag and leaves it off by default`() {
        val enabled = AtprotoIdentityConfig.fromEnvironment(recoveryKeysEnabled = " TRUE ")
        val unset = AtprotoIdentityConfig.fromEnvironment(recoveryKeysEnabled = null, publishHostedPlcOperations = null)

        assertTrue(enabled.recoveryKeysEnabled)
        assertTrue(enabled.supportsRecoveryKeys)
        assertEquals(false, unset.recoveryKeysEnabled)
        assertEquals(false, unset.supportsRecoveryKeys)
    }

    @Test
    fun `publishing alone does not turn on recovery keys`() {
        val publishing = AtprotoIdentityConfig(publishHostedPlcOperations = true, recoveryKeysEnabled = false)

        assertTrue(publishing.publishesPlcOperations)
        assertEquals(false, publishing.supportsRecoveryKeys)
    }

    @Test
    fun `a local plc directory may use plain http on a loopback host outside production`() {
        val config = AtprotoIdentityConfig.fromEnvironment(plcDirectoryUrl = "http://127.0.0.1:2582/")

        assertEquals("http://127.0.0.1:2582", config.normalizedPlcDirectoryUrl)
        assertFalse(config.targetsPublicPlcDirectory)
        assertTrue(AtprotoIdentityConfig().targetsPublicPlcDirectory)
        assertFailsWith<IllegalArgumentException> {
            AtprotoIdentityConfig.fromEnvironment(plcDirectoryUrl = "http://plc.example.com")
        }
    }

    @Test
    fun `environment factory rejects unsupported hosted did methods`() {
        assertFailsWith<IllegalArgumentException> {
            AtprotoIdentityConfig.fromEnvironment(hostedAccountDidMethod = "unsupported")
        }
    }
}
