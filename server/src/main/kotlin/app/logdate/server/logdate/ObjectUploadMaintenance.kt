package app.logdate.server.logdate

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

internal class ObjectUploadMaintenance(
    private val uploads: ObjectUploads,
    private val storage: LogDateBlobStorage,
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
            check(!closed) { "Object upload maintenance is closed" }
            if (worker != null) return@synchronized
            worker =
                scope.launch {
                    var after: String? = null
                    while (isActive) {
                        try {
                            val candidates = uploads.pendingCleanup(after).take(100)
                            for (upload in candidates) {
                                try {
                                    uploads.recover(upload) { path ->
                                        storage.deleteBlobIfExists(path)
                                        true
                                    }
                                } catch (canceled: CancellationException) {
                                    throw canceled
                                } catch (_: Exception) {
                                    Napier.w("Unattached upload cleanup could not finish")
                                }
                            }
                            after = candidates.lastOrNull()?.id
                        } catch (canceled: CancellationException) {
                            throw canceled
                        } catch (_: Exception) {
                            Napier.w("Unattached upload cleanup could not be checked")
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
