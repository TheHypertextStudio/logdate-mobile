package app.logdate.client.data.restore

import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import app.logdate.client.media.storage.MediaClaimFence
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes the full reconcile-and-scratch-cleanup transaction across JobService instances. */
internal object RetainedJournalReconciliationLock {
    private val mutex = Mutex()

    suspend fun <D, T> prepareThenWithDeliveryGate(
        gate: NativeRestoreDeliveryGate,
        prepare: suspend () -> D,
        block: suspend (D) -> T,
    ): T =
        withLock {
            val dependencies = prepare()
            gate.withLock { block(dependencies) }
        }

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> withMediaClaim(
        fence: MediaClaimFence,
        block: suspend () -> T,
    ): T = fence.withClaim(block)
}
