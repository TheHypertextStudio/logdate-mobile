package app.logdate.client.database

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class RoomDatabaseDispatcherTest {
    @Test
    fun `journal queries still run while other work holds every shared IO thread`() {
        val ioThreads = ioParallelism()
        val scope = CoroutineScope(Job())
        val release = CountDownLatch(1)
        val started = CountDownLatch(ioThreads)
        repeat(ioThreads) {
            scope.launch(Dispatchers.IO) {
                started.countDown()
                release.await()
            }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS), "The shared IO threads were not all taken")
            val ran = CountDownLatch(1)
            scope.launch(roomDatabaseDispatcher) { ran.countDown() }

            assertTrue(ran.await(5, TimeUnit.SECONDS), "Journal queries waited for the shared IO threads")
        } finally {
            release.countDown()
            scope.coroutineContext[Job]?.cancel()
        }
    }

    /** How many threads [Dispatchers.IO] allows, worked out the way kotlinx.coroutines does. */
    private fun ioParallelism(): Int =
        System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull()
            ?: maxOf(64, Runtime.getRuntime().availableProcessors())
}
