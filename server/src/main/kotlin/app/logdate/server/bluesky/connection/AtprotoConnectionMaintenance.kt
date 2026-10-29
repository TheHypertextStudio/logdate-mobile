package app.logdate.server.bluesky.connection

import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class AtprotoConnectionMaintenance(
    private val expireDecisions: suspend () -> Unit,
    private val removeUnclaimed: suspend () -> Unit,
    private val intervalMillis: Long = 60_000,
) : AutoCloseable {
    private val monitor = Any()
    private val lifetime = SupervisorJob()
    private val scope = CoroutineScope(lifetime + Dispatchers.IO)
    private var worker: Job? = null
    private var closed = false

    init {
        require(intervalMillis > 0)
    }

    fun start() =
        synchronized(monitor) {
            check(!closed) { "Connection maintenance is closed" }
            if (worker != null) return@synchronized
            worker =
                scope.launch {
                    while (isActive) {
                        try {
                            expireDecisions()
                            removeUnclaimed()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Napier.w("Account connection cleanup could not finish")
                        }
                        delay(intervalMillis)
                    }
                }
        }

    override fun close() {
        synchronized(monitor) { closed = true }
        runBlocking { lifetime.cancelAndJoin() }
    }
}
