package app.logdate.client.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class RoomDatabaseDispatcherTest {
    @Test
    fun `journal queries still run while other work holds every shared IO thread`() =
        runBlocking {
            val release = CountDownLatch(1)
            val started = CountDownLatch(IO_THREADS)
            val blockers =
                List(IO_THREADS) {
                    launch(Dispatchers.IO) {
                        started.countDown()
                        release.await()
                    }
                }
            try {
                assertTrue(started.await(10, TimeUnit.SECONDS), "The shared IO threads were not all taken")

                val ran = withTimeout(5_000) { withContext(roomDatabaseDispatcher) { true } }

                assertTrue(ran)
            } finally {
                release.countDown()
                blockers.forEach { it.join() }
            }
        }

    private companion object {
        const val IO_THREADS = 64
    }
}
