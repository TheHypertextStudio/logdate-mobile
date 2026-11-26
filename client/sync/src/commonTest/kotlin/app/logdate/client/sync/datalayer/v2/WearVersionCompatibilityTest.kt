package app.logdate.client.sync.datalayer.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearVersionCompatibilityTest {
    @Test
    fun `watch ping info round trips and rejects legacy single-byte ack`() {
        val info =
            WatchPingInfo(
                protocolVersion = 2,
                minPeerProtocolVersion = 2,
                appVersionName = "0.1.9",
                appVersionCode = 90,
            )
        assertEquals(info, WatchPingInfo.decodeOrNull(info.encodeToBytes()))
        assertNull(WatchPingInfo.decodeOrNull(byteArrayOf(1)))
        assertTrue(WatchPingInfo.isLegacyAck(byteArrayOf(1)))
    }

    @Test
    fun `compatible matching and differing app versions stay compatible`() {
        val same =
            evaluateWearCompatibility(
                localAppVersion = "0.1.5",
                peerAppVersion = "0.1.5",
                peerProtocolVersion = 2,
                peerMinPeerProtocolVersion = 2,
            )
        assertEquals(WearCompatibility.Compatible("0.1.5", "0.1.5", versionsDiffer = false), same)

        val diff =
            evaluateWearCompatibility(
                localAppVersion = "0.1.6",
                peerAppVersion = "0.1.5",
                peerProtocolVersion = 2,
                peerMinPeerProtocolVersion = 2,
            )
        assertEquals(WearCompatibility.Compatible("0.1.6", "0.1.5", versionsDiffer = true), diff)
    }

    @Test
    fun `older watch protocol or legacy ping requires watch update`() {
        val olderProtocol =
            evaluateWearCompatibility(
                localAppVersion = "0.2.0",
                peerAppVersion = "0.1.0",
                peerProtocolVersion = 1,
                peerMinPeerProtocolVersion = 1,
            )
        assertIs<WearCompatibility.WatchUpdateRequired>(olderProtocol)

        val legacyAck =
            evaluateWearCompatibility(
                localAppVersion = "0.2.0",
                legacyPeerPing = true,
            )
        assertIs<WearCompatibility.WatchUpdateRequired>(legacyAck)
    }

    @Test
    fun `newer watch min protocol or unsupported schema requires phone update`() {
        val higherMinPeer =
            evaluateWearCompatibility(
                localAppVersion = "0.1.5",
                peerAppVersion = "0.3.0",
                peerProtocolVersion = 3,
                peerMinPeerProtocolVersion = 3,
            )
        assertIs<WearCompatibility.PhoneUpdateRequired>(higherMinPeer)

        val unsupportedSchema =
            evaluateWearCompatibility(
                localAppVersion = "0.1.5",
                peerAppVersion = "0.3.0",
                peerSchemaUnsupported = true,
            )
        assertIs<WearCompatibility.PhoneUpdateRequired>(unsupportedSchema)
    }
}
