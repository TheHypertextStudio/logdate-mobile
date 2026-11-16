package app.logdate.client.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs [action] the first time [run] is called and never again in this process once it completes.
 * Concurrent callers wait for the first run; a run that throws is attempted again by the next call.
 */
internal class OncePerProcess(
    private val action: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var completed = false

    suspend fun run() =
        mutex.withLock {
            if (completed) return@withLock
            action()
            completed = true
        }
}
