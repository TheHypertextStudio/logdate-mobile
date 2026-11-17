package app.logdate.client.sync.v2.link

import app.logdate.client.datastore.OriginBoundSession
import app.logdate.client.datastore.UserSession
import app.logdate.client.networking.DeviceLinkApiClientContract
import app.logdate.client.networking.DeviceLinkApiException
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.v2.FakeSessions
import app.logdate.shared.config.DefaultLogDateConfigRepository
import app.logdate.shared.model.DeploymentKind
import app.logdate.shared.model.ServerDescriptor
import app.logdate.shared.model.ServerProtocolFeature
import app.logdate.shared.model.devices.DeviceLinkCodeResponse
import app.logdate.shared.model.devices.DeviceLinkErrorCodes
import app.logdate.shared.model.devices.DeviceLinkRedeemResponse
import app.logdate.shared.model.devices.LinkedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Tests [CloudLinkBundleSource]: the phone links a capable watch to its account only when it is signed in
 * to a server that links devices, and the bundle contains what the watch needs and nothing more.
 */
class CloudLinkBundleSourceTest {
    private val sessions = FakeSessions(OriginBoundSession(ORIGIN, UserSession("access", "refresh", ACCOUNT)))
    private val config = DefaultLogDateConfigRepository(initialBackendUrl = ORIGIN)
    private val links = MintOnly()
    private val keys = FakeKeys()
    private val source = CloudLinkBundleSource(sessions, config, LinkCodeMinter(links, sessions, null), keys, v2Enabled = { true })

    @Test
    fun `a signed-in phone on a server that links devices links a capable watch to its account`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))

            assertEquals(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN), source.plan(setOf(WearFeatures.LINK_CLOUD)))
        }

    @Test
    fun `a watch that cannot link to an account is paired`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))

            assertEquals(LinkPlan(LinkMode.PAIR), source.plan(emptySet()))
        }

    @Test
    fun `a server that does not link devices means a pairing`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = false))

            assertEquals(LinkPlan(LinkMode.PAIR), source.plan(setOf(WearFeatures.LINK_CLOUD)))
        }

    @Test
    fun `a phone with no account pairs`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            sessions.bound = null

            assertEquals(LinkPlan(LinkMode.PAIR), source.plan(setOf(WearFeatures.LINK_CLOUD)))
        }

    @Test
    fun `an account bundle contains the code the server and the keys`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            links.result = Result.success(DeviceLinkCodeResponse(code = "code-1", expiresAt = 9_000))

            val bundle = assertIs<LinkBundleResult.Ready>(source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN))).bundle

            assertEquals(LinkMode.CLOUD, bundle.mode)
            assertEquals(ACCOUNT, bundle.accountId)
            assertEquals(ORIGIN, bundle.serverOrigin)
            assertNotNull(bundle.serverDescriptor)
            assertEquals("code-1", bundle.linkCode)
            assertEquals(9_000, bundle.linkCodeExpiresAt)
            assertContentEquals(keys.identity, LinkWire.decodeKey(assertNotNull(bundle.identityKey)))
            assertEquals("fingerprint", bundle.identityKeyFingerprint)
            assertContentEquals(keys.media, LinkWire.decodeKey(assertNotNull(bundle.mediaKey)))
            assertEquals(mapOf("watch_experience_v2_enabled" to true), bundle.flags)
        }

    @Test
    fun `an account with no room for another device is reported`() =
        runTest {
            links.result = Result.failure(DeviceLinkApiException(409, DeviceLinkErrorCodes.DEVICE_LIMIT_REACHED))

            assertEquals(
                LinkBundleResult.Unavailable(PhoneLinkFailure.DEVICE_LIMIT_REACHED),
                source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)),
            )
        }

    @Test
    fun `a phone that signed out since the plan sends nothing`() =
        runTest {
            sessions.bound = null

            assertEquals(
                LinkBundleResult.Unavailable(PhoneLinkFailure.SIGNED_OUT),
                source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)),
            )
        }

    @Test
    fun `signing out while keys are read prevents code minting`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            keys.onMedia = { sessions.bound = null }
            links.result = Result.success(DeviceLinkCodeResponse("code", 9_000))

            assertIs<LinkBundleResult.Unavailable>(source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)))
            assertEquals(0, links.calls)
        }

    @Test
    fun `changing accounts during mint discards the bundle`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            links.result = Result.success(DeviceLinkCodeResponse("code", 9_000))
            links.onMint = { sessions.bound = OriginBoundSession(ORIGIN, UserSession("other", "other", "another-account")) }

            assertIs<LinkBundleResult.Unavailable>(source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)))
        }

    @Test
    fun `changing server during mint discards the bundle`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            links.result = Result.success(DeviceLinkCodeResponse("code", 9_000))
            links.onMint = { config.updateBackendUrl("https://other.test") }

            assertIs<LinkBundleResult.Unavailable>(source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)))
        }

    @Test
    fun `keys owned by another account cannot be offered`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            keys.allowed = false

            assertEquals(LinkPlan(LinkMode.PAIR), source.plan(setOf(WearFeatures.LINK_CLOUD)))
            assertEquals(
                LinkBundleResult.Unavailable(PhoneLinkFailure.KEYS_UNAVAILABLE),
                source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)),
            )
            assertEquals(0, links.calls)
        }

    @Test
    fun `an old server plan cannot receive the current server bundle`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            links.result = Result.success(DeviceLinkCodeResponse("code", 9_000))

            assertEquals(
                LinkBundleResult.Unavailable(PhoneLinkFailure.SIGNED_OUT),
                source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, "https://old.test")),
            )
            assertEquals(0, links.calls)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `installing an identity triggers a new cloud link plan`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            keys.available = false
            val plans = mutableListOf<LinkPlan>()
            backgroundScope.launch { source.changes().collect { plans += source.plan(setOf(WearFeatures.LINK_CLOUD)) } }
            runCurrent()
            assertEquals(LinkPlan(LinkMode.PAIR), plans.last())
            keys.available = true
            keys.signals.value++
            runCurrent()
            assertEquals(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN), plans.last())
        }

    @Test
    fun `unreadable identity does not crash linking or mint a code`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            keys.identityFailure = IllegalStateException("locked")
            assertEquals(LinkPlan(LinkMode.PAIR), source.plan(setOf(WearFeatures.LINK_CLOUD)))
            assertEquals(
                LinkBundleResult.Unavailable(PhoneLinkFailure.KEYS_UNAVAILABLE),
                source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)),
            )
            assertEquals(0, links.calls)
        }

    @Test
    fun `identity read cancellation propagates`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            keys.identityFailure = CancellationException("cancel")
            assertFailsWith<CancellationException> { source.plan(setOf(WearFeatures.LINK_CLOUD)) }
            assertFailsWith<CancellationException> { source.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)) }
        }

    @Test
    fun `signing out during key read discards the cloud plan`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            keys.onIdentity = { sessions.bound = null }
            assertEquals(LinkPlan(LinkMode.PAIR), source.plan(setOf(WearFeatures.LINK_CLOUD)))
        }

    @Test
    fun `a selected server different from the session cannot plan an account link`() =
        runTest {
            config.updateBackendUrl("https://second.test")
            config.updateServerDescriptor(descriptor(linking = true).copy(serverOrigin = "https://second.test"))
            assertEquals(LinkPlan(LinkMode.PAIR), source.plan(setOf(WearFeatures.LINK_CLOUD)))
        }

    @Test
    fun `a disabled rollout never creates an account bundle`() =
        runTest {
            config.updateServerDescriptor(descriptor(linking = true))
            links.result = Result.success(DeviceLinkCodeResponse("code", 9_000))
            val disabled = CloudLinkBundleSource(sessions, config, LinkCodeMinter(links, sessions, null), keys, v2Enabled = { false })
            assertEquals(
                LinkBundleResult.Unavailable(PhoneLinkFailure.WATCH_CANNOT_LINK),
                disabled.bundle(LinkPlan(LinkMode.CLOUD, ACCOUNT, ORIGIN)),
            )
            assertEquals(0, links.calls)
        }

    private fun descriptor(linking: Boolean) =
        ServerDescriptor(
            serverOrigin = ORIGIN,
            apiBaseUrl = "$ORIGIN/api/v1",
            deploymentKind = DeploymentKind.FIRST_PARTY,
            displayName = "LogDate",
            protocolFeatures = if (linking) listOf(ServerProtocolFeature.DEVICE_LINKING_V1) else emptyList(),
        )

    private class FakeKeys : AccountKeySource {
        var identityFailure: Exception? = null
        var onIdentity: () -> Unit = {}
        var available = true
        val signals = MutableStateFlow(0)

        override fun changes() = signals.map { }

        var allowed = true

        override suspend fun canUseForAccount(accountId: String): Boolean = allowed

        var onMedia: () -> Unit = {}
        val identity = ByteArray(32) { 1 }
        val media = ByteArray(32) { 2 }

        override suspend fun identityKey(): ByteArray? {
            onIdentity()
            identityFailure?.let { throw it }
            return identity.takeIf { available }
        }

        override fun fingerprint(identityKey: ByteArray): String = "fingerprint"

        override suspend fun mediaKey(): ByteArray {
            onMedia()
            return media
        }
    }

    private class MintOnly : DeviceLinkApiClientContract {
        var calls = 0
        var onMint: suspend () -> Unit = {}
        var result: Result<DeviceLinkCodeResponse> = Result.failure(IllegalStateException("No code expected"))

        override suspend fun mintLinkCode(accessToken: String): Result<DeviceLinkCodeResponse> {
            calls++
            onMint()
            return result
        }

        override suspend fun redeem(
            code: String,
            deviceName: String,
            platform: String,
        ): Result<DeviceLinkRedeemResponse> = error("The phone never redeems")

        override suspend fun listDevices(accessToken: String): Result<List<LinkedDevice>> = error("Not used")

        override suspend fun revoke(
            deviceId: String,
            accessToken: String,
        ): Result<Unit> = error("Not used")

        override suspend fun unlinkSelf(accessToken: String): Result<Unit> = error("Not used")
    }

    private companion object {
        const val ORIGIN = "https://logdate.test"
        const val ACCOUNT = "5f0c3a52-0000-4000-8000-000000000001"
    }
}
