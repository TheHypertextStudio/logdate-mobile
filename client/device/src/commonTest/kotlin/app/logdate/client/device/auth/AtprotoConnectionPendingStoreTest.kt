package app.logdate.client.device.auth

import app.logdate.client.device.storage.FakeSecureStorage
import app.logdate.client.device.storage.SecureStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtprotoConnectionPendingStoreTest {
    @Test
    fun `restart keeps the same purpose proof and authentication binding without diagnostics leaking secrets`() =
        runTest {
            val storage = FakeSecureStorage()
            val saved =
                AtprotoConnectionPendingStore(
                    storage,
                ) { 1000 }.remember(BINDING, AtprotoConnectionPendingAction.Reconnect(ID, VERIFIER))
            val restored = assertNotNull(AtprotoConnectionPendingStore(storage) { 2000 }.current(BINDING))
            assertEquals(saved.id, restored.id)
            val proof = assertIs<AtprotoConnectionPendingAction.Reconnect>(restored.action)
            assertEquals(VERIFIER, proof.verifier)
            assertFalse(restored.toString().contains(VERIFIER))
            assertFalse(proof.toString().contains(VERIFIER))
        }

    @Test
    fun `other origin account or authentication grant cannot resume a pending action`() =
        runTest {
            for (changed in listOf(
                BINDING.copy(backendOrigin = "https://staging.logdate.app"),
                BINDING.copy(accountId = OTHER_OWNER),
                BINDING.copy(authenticationGeneration = "B".repeat(43)),
            )) {
                val storage = FakeSecureStorage()
                val store = AtprotoConnectionPendingStore(storage) { 1000 }
                store.remember(BINDING, AtprotoConnectionPendingAction.Reconnect(ID, VERIFIER))
                assertNull(store.current(changed))
                assertNull(store.current(BINDING))
            }
        }

    @Test
    fun `disconnect remains retryable for a day while browser proof expires after ten minutes`() =
        runTest {
            val storage = FakeSecureStorage()
            val store = AtprotoConnectionPendingStore(storage) { 1000 }
            store.remember(BINDING, AtprotoConnectionPendingAction.Reconnect(ID, VERIFIER))
            assertNull(AtprotoConnectionPendingStore(storage) { 601000 }.current(BINDING))
            val saved = store.remember(BINDING, AtprotoConnectionPendingAction.Disconnect(ID, "frozen-proof"))
            val restored = assertNotNull(AtprotoConnectionPendingStore(storage) { 601000 }.current(BINDING))
            assertEquals("frozen-proof", assertIs<AtprotoConnectionPendingAction.Disconnect>(restored.action).encodedProof)
            assertEquals(saved.id, restored.id)
            assertNull(AtprotoConnectionPendingStore(storage) { 86401000 }.current(BINDING))
        }

    @Test
    fun `an old completion never clears a newer request and pending actions cannot be silently replaced`() =
        runTest {
            val storage = FakeSecureStorage()
            val store = AtprotoConnectionPendingStore(storage) { 1000 }
            val old = store.remember(BINDING, AtprotoConnectionPendingAction.Disconnect(ID, "old-proof"))
            assertFailsWith<IllegalStateException> { store.remember(BINDING, AtprotoConnectionPendingAction.Reconnect(ID, VERIFIER)) }
            assertTrue(store.forget(old))
            val current = store.remember(BINDING, AtprotoConnectionPendingAction.Reconnect(ID, VERIFIER))
            assertFalse(store.forget(old))
            assertEquals(current.id, store.current(BINDING)?.id)
        }

    @Test
    fun `silent secure write failure and oversized proofs fail closed with bounded errors`() =
        runTest {
            val storage =
                object : SecureStorage by FakeSecureStorage() {
                    override suspend fun putString(
                        key: String,
                        value: String,
                    ) = Unit
                }
            val store = AtprotoConnectionPendingStore(storage) { 1000 }
            val failure =
                assertFailsWith<IllegalStateException> { store.remember(BINDING, AtprotoConnectionPendingAction.Reconnect(ID, VERIFIER)) }
            assertFalse(failure.toString().contains(VERIFIER))
            assertNull(store.current(BINDING))
            assertFailsWith<IllegalStateException> {
                AtprotoConnectionPendingStore(
                    FakeSecureStorage(),
                ).remember(BINDING, AtprotoConnectionPendingAction.Disconnect(ID, "x".repeat(65537)))
            }
        }

    private companion object {
        const val OWNER = "00000000-0000-4000-8000-000000000001"
        const val OTHER_OWNER = "00000000-0000-4000-8000-000000000002"
        val ID = "A".repeat(43)
        val VERIFIER = "private".repeat(10)
        val BINDING = AtprotoConnectionBinding("https://cloud.logdate.app", OWNER, "C".repeat(43))
    }
}
