package app.logdate.server.transfer

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How many file transfers one server instance runs at once, and how long a transfer may wait for its
 * client before giving up its slot.
 *
 * The caps come from the memory budget of the smallest deployed instance: 512 MiB with a heap of 75%,
 * about 384 MiB, leaving about 128 MiB for everything outside the heap, including `/tmp`, which Cloud
 * Run keeps in memory.
 *
 * - A streamed upload goes from the request straight to object storage. It holds one Cloud Storage
 *   write buffer (2 MiB) and one read chunk (64 KiB) in the heap, and nothing in `/tmp`. Eight of them
 *   take about 17 MiB, under 5% of the heap.
 * - A download streams from object storage through one Cloud Storage read buffer (2 MiB) and one
 *   chunk (64 KiB). Sixteen of them take about 33 MiB, under 10% of the heap.
 * - A buffered transfer, such as a backup, still goes through files in `/tmp` and can hold far more
 *   than the memory outside the heap, so only one runs at a time.
 *
 * Together the streamed caps stay near 50 MiB, leaving most of the heap for the other requests an
 * instance serves at once.
 *
 * @property streamedUploads uploads written straight to object storage.
 * @property downloads downloads read straight from object storage.
 * @property bufferedTransfers uploads and downloads that pass through temporary files.
 * @property idleTimeout how long a transfer waits for its client to send or accept more bytes.
 * @property retryAfterSeconds the `Retry-After` given with a refused transfer.
 * @property backupPartBytes the length of every part but the last of a backup sent in parts.
 */
data class TransferLimits(
    val streamedUploads: Int = 8,
    val downloads: Int = 16,
    val bufferedTransfers: Int = 1,
    val idleTimeout: Duration = 20.seconds,
    val retryAfterSeconds: Long = 5,
    val backupPartBytes: Long = 32L * 1024 * 1024,
)

/** The transfer slots of one server instance, shared by every route that moves files. */
class TransferSlots(
    val limits: TransferLimits = TransferLimits(),
) {
    private val streamedUploads = Semaphore(limits.streamedUploads)
    private val downloads = Semaphore(limits.downloads)
    private val buffered = Semaphore(limits.bufferedTransfers)

    /** A streamed upload slot, or null when every one is taken. */
    fun tryStreamedUpload(): TransferSlot? = take(streamedUploads)

    /** A download slot, or null when every one is taken. */
    fun tryDownload(): TransferSlot? = take(downloads)

    /** A slot for a transfer through temporary files, or null when every one is taken. */
    fun tryBuffered(): TransferSlot? = take(buffered)

    private fun take(slots: Semaphore): TransferSlot? = if (slots.tryAcquire()) TransferSlot(slots) else null
}

/**
 * A held transfer slot. A transfer can outlive the handler that started it, as when the engine writes
 * a response body after the handler returns, so whatever finishes the transfer calls [release].
 * Releasing it again does nothing.
 */
class TransferSlot internal constructor(
    private val slots: Semaphore,
) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) slots.release()
    }
}
