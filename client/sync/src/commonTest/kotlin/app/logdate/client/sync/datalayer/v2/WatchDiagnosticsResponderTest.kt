package app.logdate.client.sync.datalayer.v2

import app.logdate.client.sync.peer.link.LinkWire
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WatchDiagnosticsResponderTest {
    private val peer = PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 1 }), "phone", "watch", LinkMode.PAIR)
    private val payload =
        WearJson.encodeToBytes(
            WatchDiagnosticsRequest.serializer(),
            WatchDiagnosticsRequest(context = peer, nonce = "test"),
        )

    @Test fun `verified phone gets actual permissions and nonce echo`() =
        runTest {
            val responder =
                WatchDiagnosticsResponder(
                    current = { peer },
                    enabled = { true },
                    notificationsAllowed = { false },
                    microphoneAllowed = { true },
                    appVersionName = "0.2.0",
                    appVersionCode = 200,
                )
            val bytes = assertNotNull(responder.answer("phone", payload))
            val response =
                (
                    WearJson.decode(
                        bytes,
                        1,
                        WatchDiagnosticsResponse.serializer(),
                    ) as Decoded.Supported<WatchDiagnosticsResponse>
                ).value
            assertEquals("test", response.nonce)
            assertEquals(false, response.notificationsAllowed)
            assertEquals(true, response.microphoneAllowed)
            assertEquals("0.2.0", response.appVersionName)
            assertEquals(200, response.appVersionCode)
            assertEquals(WearProtocol.PROTOCOL_VERSION, response.protocolVersion)
        }

    @Test fun `other phone and disabled rollout never receive permissions`() =
        runTest {
            var reads = 0
            val responder =
                WatchDiagnosticsResponder({ peer }, { false }, {
                    reads++
                    true
                }, {
                    reads++
                    true
                })
            assertNull(responder.answer("phone", payload))
            assertEquals(0, reads)
            assertNull(WatchDiagnosticsResponder({ peer }, { true }, { true }, { true }).answer("other", payload))
        }

    @Test fun `revoked peer while reading permissions cannot receive a reply`() =
        runTest {
            var current: PeerDeliveryContext? = peer
            val responder =
                WatchDiagnosticsResponder({ current }, { true }, {
                    current = null
                    true
                }, { true })
            assertNull(responder.answer("phone", payload))
        }
}
