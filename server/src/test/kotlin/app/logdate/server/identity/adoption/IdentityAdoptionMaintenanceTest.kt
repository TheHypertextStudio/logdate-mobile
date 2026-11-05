package app.logdate.server.identity.adoption

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IdentityAdoptionMaintenanceTest {
    @Test fun oneOwnedWorkerClosesWithoutCancelingItsCaller() =
        runBlocking<Unit> {
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val maintenance =
                IdentityAdoptionMaintenance(
                    pending = { listOf("synthetic-upload") },
                    cleanup = {
                        calls.incrementAndGet()
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            finished.complete(Unit)
                        }
                    },
                )
            try {
                maintenance.start()
                maintenance.start()
                withTimeout(2000) { entered.await() }
            } finally {
                maintenance.close()
            }
            assertTrue(finished.isCompleted)
            assertEquals(1, calls.get())
            maintenance.close()
            assertFailsWith<IllegalStateException> { maintenance.start() }
        }

    @Test fun failedCandidatesDoNotStarveLaterPagesAndEachPassIsBounded() =
        runBlocking {
            val complete = CompletableDeferred<Unit>()
            val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
            val ids = (0..100).map { it.toString().padStart(3, '0') }
            val maintenance =
                IdentityAdoptionMaintenance(
                    pending = { after -> ids.filter { after == null || it > after } },
                    cleanup = {
                        seen.add(it)
                        if (it == "100") complete.complete(Unit)
                        if (it == "000") error("synthetic unknown ownership")
                        true
                    },
                    intervalMillis = 10,
                )
            try {
                maintenance.start()
                withTimeout(2000) { complete.await() }
                assertEquals(ids, seen.take(101))
            } finally {
                maintenance.close()
            }
        }
}
