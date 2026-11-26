package app.logdate.client.sync.peer.state

import app.logdate.client.sync.datalayer.v2.Decoded
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests [PeerStateStore], which stores the latest readable document each peer wrote.
 */
class PeerStateStoreTest {
    private val store = PeerStateStore()

    @Test
    fun `a readable document from the expected role is stored`() =
        runTest {
            val watch = StateDocument(role = PeerRole.WATCH, writtenAt = 1, watch = WatchState(features = setOf("receipts.v1")))

            assertEquals(watch, store.accept("watch-node", Decoded.Supported(watch), PeerRole.WATCH))
            assertEquals(setOf("receipts.v1"), store.features(PeerRole.WATCH).first())
        }

    @Test
    fun `a document from the wrong role is ignored`() =
        runTest {
            val phone = StateDocument(role = PeerRole.PHONE, writtenAt = 1, phone = PhoneState())

            assertNull(store.accept("node", Decoded.Supported(phone), PeerRole.WATCH))
            assertNull(store.latest(PeerRole.PHONE).first())
        }

    @Test
    fun `an unsupported or malformed document is ignored`() =
        runTest {
            assertNull(store.accept("node", Decoded.Unsupported(2, "newer"), PeerRole.WATCH))
            assertEquals(2, store.currentUnsupportedSchema("node"))
            assertNull(store.accept("node", Decoded.Malformed("broken"), PeerRole.WATCH))
            assertEquals(emptySet(), store.features(PeerRole.WATCH).first())

            val supported = StateDocument(role = PeerRole.WATCH, writtenAt = 3, watch = WatchState())
            assertEquals(supported, store.accept("node", Decoded.Supported(supported), PeerRole.WATCH))
            assertNull(store.currentUnsupportedSchema("node"))
        }

    @Test
    fun `the newest document of a role wins`() =
        runTest {
            val older = StateDocument(role = PeerRole.PHONE, writtenAt = 1, phone = PhoneState(features = setOf("a")))
            val newer = StateDocument(role = PeerRole.PHONE, writtenAt = 2, phone = PhoneState(features = setOf("b")))
            store.accept("first", Decoded.Supported(newer), PeerRole.PHONE)
            store.accept("second", Decoded.Supported(older), PeerRole.PHONE)

            assertEquals(setOf("b"), store.features(PeerRole.PHONE).first())
        }

    @Test
    fun `documents lists the latest document of each node of a role`() =
        runTest {
            val watch = StateDocument(role = PeerRole.WATCH, writtenAt = 1, watch = WatchState())
            val phone = StateDocument(role = PeerRole.PHONE, writtenAt = 1, phone = PhoneState())
            store.accept("watch-node", Decoded.Supported(watch), PeerRole.WATCH)
            store.accept("phone-node", Decoded.Supported(phone), PeerRole.PHONE)

            assertEquals(mapOf("watch-node" to watch), store.documents(PeerRole.WATCH).first())
        }
}
