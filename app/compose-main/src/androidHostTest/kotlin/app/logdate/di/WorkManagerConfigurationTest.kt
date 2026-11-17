package app.logdate.di

import androidx.work.Configuration
import app.logdate.client.LogDateApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class WorkManagerConfigurationTest {
    @Test
    fun `a worker asked for before the app has started Koin waits for it`() {
        val started = AtomicBoolean(false)
        val starter = Thread { Thread.sleep(150).also { started.set(true) } }
        starter.start()

        val waited = awaitKoinStarted(started::get, timeoutMillis = 5_000)

        starter.join()
        assertTrue(waited, "The worker was not held until Koin started")
        assertTrue(started.get())
    }

    @Test
    fun `a worker is not held past the time startup is given`() {
        val waited = awaitKoinStarted({ false }, timeoutMillis = 100)

        assertFalse(waited, "A worker must not wait forever for a startup that never comes")
    }

    @Test
    fun `the application hands WorkManager its configuration when a job arrives before startup`() {
        assertTrue(
            Configuration.Provider::class.java.isAssignableFrom(LogDateApplication::class.java),
            "Without this a scheduled job that starts the process finds WorkManager uninitialized and crashes it",
        )
    }

    @Test
    fun `resumed workers waiting for storage leave configuration startup runnable`() =
        runBlocking {
            val configuration = logDateWorkManagerConfiguration()
            val scope = CoroutineScope(SupervisorJob())
            val coreCount =
                System.getProperty("kotlinx.coroutines.scheduler.core.pool.size")?.toInt()
                    ?: maxOf(2, Runtime.getRuntime().availableProcessors())
            val entered = CountDownLatch(coreCount)
            val storageReady = CountDownLatch(1)
            val workers =
                List(coreCount) {
                    scope.launch(configuration.workerCoroutineContext) {
                        entered.countDown()
                        check(storageReady.await(10, TimeUnit.SECONDS))
                    }
                }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS), "Resumed workers must reach the storage gate")
                val configurationLoaded = CompletableDeferred<Unit>()
                scope.launch(Dispatchers.Default) { configurationLoaded.complete(Unit) }
                withTimeout(2_000) { configurationLoaded.await() }
            } finally {
                storageReady.countDown()
                workers.joinAll()
                scope.cancel()
                (configuration.executor as? ExecutorService)?.shutdownNow()
                (configuration.taskExecutor as? ExecutorService)?.shutdownNow()
            }
        }
}
