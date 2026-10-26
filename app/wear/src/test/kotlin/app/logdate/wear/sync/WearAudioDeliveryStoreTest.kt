package app.logdate.wear.sync

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.UploadScope
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WearAudioDeliveryStoreTest {
    private val values = mutableMapOf<String, String>()
    private val storage =
        mockk<KeyValueStorage>().also {
            coEvery { it.getString(any()) } answers { values[firstArg()] }
            coEvery { it.putString(any(), any()) } answers { values[firstArg()] = secondArg() }
        }
    private val pending =
        PendingUpload("note", PendingOperation.CREATE, scope = UploadScope("owner", "https://server.test"), operationId = "operation")

    @Test
    fun `transmission identity and snapshot survive a recreated store`() =
        runTest {
            val delivery = WearAudioDeliveryStore(storage).capture(pending, mapOf("payload" to "original"))

            val restored = WearAudioDeliveryStore(storage).deliveries("note").single()

            assertEquals(delivery.token, restored.token)
            assertTrue(restored.matches(pending))
            assertTrue(restored.matchesSnapshot(mapOf("payload" to "original")))
            assertFalse(restored.matchesSnapshot(mapOf("payload" to "edited")))
        }

    @Test
    fun `an unchanged operation reuses its token while edits and scope changes get new tokens`() =
        runTest {
            val store = WearAudioDeliveryStore(storage)
            val original = store.capture(pending, mapOf("payload" to "original"))

            assertEquals(original.token, store.capture(pending, mapOf("payload" to "original")).token)
            assertNotEquals(original.token, store.capture(pending.copy(operationId = "newer"), mapOf("payload" to "original")).token)
            assertNotEquals(
                original.token,
                store
                    .capture(
                        pending.copy(scope = UploadScope("another", "https://server.test")),
                        mapOf("payload" to "original"),
                    ).token,
            )
            assertNotEquals(original.token, store.capture(pending, mapOf("payload" to "edited")).token)
        }

    @Test
    fun `completed receipts prevent ambiguous legacy acknowledgements of later changes`() =
        runTest {
            val store = WearAudioDeliveryStore(storage)
            val legacy = PendingUpload("note", PendingOperation.CREATE)
            val first = store.capture(legacy, mapOf("payload" to "original"))
            assertTrue(first.allowsLegacy)
            store.remove(first.token)

            val next = WearAudioDeliveryStore(storage).capture(legacy, mapOf("payload" to "edited"))

            assertFalse(next.allowsLegacy)
            assertFalse(store.capture(pending, mapOf("payload" to "scoped")).allowsLegacy)
        }

    @Test
    fun `acknowledging one transfer retains another operation`() =
        runTest {
            val store = WearAudioDeliveryStore(storage)
            val first = store.capture(pending, mapOf("payload" to "original"))
            val second = store.capture(pending.copy(operationId = "newer"), mapOf("payload" to "edited"))

            store.remove(first.token)

            assertEquals(listOf(second.token), WearAudioDeliveryStore(storage).deliveries("note").map { it.token })
        }
}
