package app.logdate.wear.sync.v2.link

import app.logdate.client.sync.peer.link.LinkProblem
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.wear.sync.v2.MemoryKeyValueStorage
import app.logdate.wear.sync.v2.MemoryPairKeyVault
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A watch erases itself only when it was removed; a sign-in that ran out asks the phone to link it again. */
class WatchSessionEndHandlerTest {
    private val store = WatchLinkStore(MemoryKeyValueStorage(), MemoryPairKeyVault())
    private var erased = 0
    private val handler = WatchSessionEndHandler(store) { erased++ }

    @Test
    fun `a sign-in that ran out keeps the watch and asks its phone to link it again`() =
        runTest {
            store.saveCloudLink("phone", "account", "https://linked.test", linkId = "exchange")

            handler.onDeviceSessionEnded()

            assertEquals(0, erased)
            assertEquals(LinkProblem.SESSION_EXPIRED.name, store.current()?.problem)
            assertEquals(PeerLinkState.NEEDS_RELINK, store.current().peerLinkState())
            assertEquals("exchange", store.current()?.linkId)
        }

    @Test
    fun `a removal erases the watch`() =
        runTest {
            store.saveCloudLink("phone", "account", "https://linked.test", linkId = "exchange")

            handler.onDeviceSessionRevoked()

            assertEquals(1, erased)
        }

    @Test
    fun `an ended sign-in leaves a watch without a working account link alone`() =
        runTest {
            handler.onDeviceSessionEnded()
            assertNull(store.current())

            store.savePairing("phone", ByteArray(32), linkId = "exchange")
            handler.onDeviceSessionEnded()
            assertNull(store.current()?.problem)

            store.beginCloudInstall("phone", ByteArray(32), "account", "https://linked.test", linkId = "exchange")
            store.recordProblem("phone", LinkProblem.NETWORK)
            handler.onDeviceSessionEnded()
            assertEquals(LinkProblem.NETWORK.name, store.current()?.problem)
        }
}
