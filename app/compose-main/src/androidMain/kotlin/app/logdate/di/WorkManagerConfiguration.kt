package app.logdate.di

import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.GlobalContext

/**
 * How WorkManager runs LogDate's background work.
 *
 * The application hands this to WorkManager whenever WorkManager first needs it, which can be
 * before startup has reached Koin: Android starts the process to run a scheduled job and creates
 * the job's service straight after `Application.onCreate`, while startup is still waiting for the
 * device to be unlocked or clearing an abandoned transfer off the main thread. So nothing here may
 * need Koin until a worker is actually created, and [StartedKoinWorkerFactory] waits for it then.
 */
internal fun logDateWorkManagerConfiguration(): Configuration =
    Configuration
        .Builder()
        .setWorkerFactory(DelegatingWorkerFactory().apply { addFactory(StartedKoinWorkerFactory()) })
        // Restored workers can synchronously wait for encrypted storage in Koin.
        // Leave the CPU dispatcher available to complete configuration/DataStore startup.
        .setWorkerCoroutineContext(Dispatchers.IO)
        .build()

internal fun initializeLogDateWorkManager(context: Context) {
    if (!WorkManager.isInitialized()) {
        WorkManager.initialize(context, logDateWorkManagerConfiguration())
    }
}

/**
 * Creates workers through Koin, once the app has started it.
 *
 * WorkManager creates workers on its own background thread, so waiting here holds up background
 * work only, and only for as long as startup takes to get to Koin. A worker asked for on the main
 * thread is not held, because Koin is started on the main thread and waiting there would stop it.
 */
internal class StartedKoinWorkerFactory : WorkerFactory() {
    private val koin = KoinWorkerFactory()

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (Looper.myLooper() != Looper.getMainLooper() && !awaitKoinStarted()) {
            Napier.e("Startup did not reach Koin in time to create $workerClassName", tag = WORK_TAG)
            return null
        }
        return koin.createWorker(appContext, workerClassName, workerParameters)
    }
}

/**
 * Blocks the calling thread until Koin has started, and says whether it did within [timeoutMillis].
 */
internal fun awaitKoinStarted(
    started: () -> Boolean = { GlobalContext.getOrNull() != null },
    timeoutMillis: Long = KOIN_START_TIMEOUT_MILLIS,
): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    while (!started()) {
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(KOIN_START_POLL_MILLIS)
    }
    return true
}

private const val WORK_TAG = "LogDateWork"

// Startup reaches Koin within a moment of the process starting once the device is unlocked. This
// is how long a worker is held before it is given up on, which WorkManager reports as a failure.
private const val KOIN_START_TIMEOUT_MILLIS = 60_000L
private const val KOIN_START_POLL_MILLIS = 20L
private const val NANOS_PER_MILLI = 1_000_000L
