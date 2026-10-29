package app.logdate.server.bluesky

import app.logdate.server.bluesky.connection.AtprotoConnectionMaintenance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtprotoConnectionMaintenanceTest {
    @Test
    fun `only one worker starts and close waits for its cancellation before resources close`() =
        runBlocking<Unit> {
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val maintenance =
                AtprotoConnectionMaintenance(
                    expireDecisions = {
                        calls.incrementAndGet()
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            finished.complete(Unit)
                        }
                    },
                    removeUnclaimed = { error("The blocked pass must finish first") },
                )
            try {
                maintenance.start()
                maintenance.start()
                withTimeout(2000) { entered.await() }
                assertEquals(1, calls.get())
            } finally {
                maintenance.close()
            }
            assertTrue(finished.isCompleted)
            maintenance.close()
            assertFailsWith<IllegalStateException> { maintenance.start() }
        }

    @Test
    fun `a transient failure permits the next bounded cleanup pass`() =
        runBlocking {
            val complete = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val maintenance =
                AtprotoConnectionMaintenance(
                    expireDecisions = { if (calls.incrementAndGet() == 1) error("synthetic transient failure") },
                    removeUnclaimed = { complete.complete(Unit) },
                    intervalMillis = 10,
                )
            try {
                maintenance.start()
                withTimeout(2000) { complete.await() }
                assertTrue(calls.get() >= 2)
            } finally {
                maintenance.close()
            }
        }
}
