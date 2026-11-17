package app.logdate.client.data.restore

import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class RetainedJournalReconciliationLockTest {
    @Test
    fun `cold database and media admission complete before native merge hold`() =
        runTest {
            val mutex = Mutex()
            val gate =
                object : NativeRestoreDeliveryGate {
                    override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
                }
            val events = mutableListOf<String>()
            withTimeout(1000) {
                RetainedJournalReconciliationLock.prepareThenWithDeliveryGate(
                    gate,
                    prepare = {
                        gate.withLock { events += "database-open" }
                        gate.withLock { events += "media-root-admission" }
                    },
                    block = {
                        assertEquals(true, mutex.isLocked)
                        events += "merge"
                    },
                )
            }
            assertEquals(listOf("database-open", "media-root-admission", "merge"), events)
        }

    @Test
    fun `cold database initializer can acquire native gate before worker transaction`() =
        runTest {
            val mutex = Mutex()
            val gate =
                object : NativeRestoreDeliveryGate {
                    override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
                }
            val events = mutableListOf<String>()
            withTimeout(1000) {
                RetainedJournalReconciliationLock.prepareThenWithDeliveryGate(
                    gate,
                    prepare = {
                        gate.withLock {
                            events += "database-open"
                            "ready-database"
                        }
                    },
                    block = { database ->
                        assertEquals("ready-database", database)
                        events += "capture-current-workspace"
                        events += "merge"
                    },
                )
            }
            assertEquals(listOf("database-open", "capture-current-workspace", "merge"), events)
        }

    @Test
    fun `workspace replacement waits until worker merge and scratch cleanup complete`() =
        runTest {
            val mutex = Mutex()
            val gate =
                object : NativeRestoreDeliveryGate {
                    override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
                }
            val events = mutableListOf<String>()
            val worker =
                async {
                    RetainedJournalReconciliationLock.prepareThenWithDeliveryGate(gate, prepare = {}) {
                        events += "capture"
                        delay(20)
                        events += "merge"
                        events += "scratch-cleanup"
                    }
                }
            val replacement = async { gate.withLock { events += "prepare-next-workspace" } }
            worker.await()
            replacement.await()
            assertEquals(listOf("capture", "merge", "scratch-cleanup", "prepare-next-workspace"), events)
        }

    @Test
    fun `replacement job waits for previous reconcile and scratch cleanup`() =
        runTest {
            var active = 0
            var maxActive = 0
            val first =
                async {
                    RetainedJournalReconciliationLock.withLock {
                        active++
                        maxActive = maxOf(maxActive, active)
                        delay(20)
                        active--
                    }
                }
            val second =
                async {
                    RetainedJournalReconciliationLock.withLock {
                        active++
                        maxActive = maxOf(maxActive, active)
                        active--
                    }
                }

            first.await()
            second.await()
            assertEquals(1, maxActive)
        }
}
