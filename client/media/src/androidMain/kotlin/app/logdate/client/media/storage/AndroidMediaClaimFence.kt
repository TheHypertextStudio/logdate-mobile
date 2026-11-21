package app.logdate.client.media.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Cross-process media claim fence rooted in app-private, excluded-from-backup storage.
 *
 * Claims are shared: any number of them run at once, within this process and across processes, and
 * every Android database transaction takes one first. Reclamation is exclusive: it waits until no
 * claim is held anywhere and blocks new claims while it runs. A claim never waits on another claim,
 * so a claim that waits for work done elsewhere, or a slow media copy, cannot stall unrelated saves.
 * A steady stream of overlapping claims can delay reclamation; that only postpones cleanup.
 */
class AndroidMediaClaimFence(
    private val noBackupFilesDir: File,
) : MediaClaimFence {
    private val directory = File(noBackupFilesDir, "media-reclamation")
    private val lockPath = File(directory, ".claims.lock").absolutePath
    private val gate = gates.computeIfAbsent(lockPath) { ClaimGate() }

    override suspend fun <T> withClaim(block: suspend () -> T): T = fenced(shared = true, block)

    override suspend fun <T> withExclusiveReclamation(block: suspend () -> T): T = fenced(shared = false, block)

    private suspend fun <T> fenced(
        shared: Boolean,
        block: suspend () -> T,
    ): T {
        val current = currentCoroutineContext()[HeldFence]
        if (current?.path == lockPath) {
            check(shared && current.shared) { "Media reclamation cannot run inside a claim mutation" }
            return block()
        }
        return withContext(Dispatchers.IO) {
            acquire(shared)
            try {
                withContext(HeldFence(lockPath, shared)) { block() }
            } finally {
                withContext(NonCancellable) { release() }
            }
        }
    }

    private suspend fun acquire(shared: Boolean) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (gate.state.withLock { tryEnter(shared) }) return
            delay(25)
        }
    }

    /** Joins the held shared lock, or takes the OS lock when the fence is idle in this process. */
    private fun tryEnter(shared: Boolean): Boolean {
        if (gate.exclusive) return false
        if (gate.shares > 0) {
            if (!shared) return false
            gate.shares++
            return true
        }
        val channel = openChannel()
        val lock =
            try {
                channel.tryLock(0L, Long.MAX_VALUE, shared)
            } catch (_: OverlappingFileLockException) {
                null
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        if (lock == null) {
            channel.close()
            return false
        }
        gate.channel = channel
        gate.lock = lock
        if (shared) gate.shares = 1 else gate.exclusive = true
        return true
    }

    private suspend fun release() {
        gate.state.withLock {
            if (gate.exclusive) {
                gate.exclusive = false
            } else {
                gate.shares--
                if (gate.shares > 0) return@withLock
            }
            val lock = gate.lock
            val channel = gate.channel
            gate.lock = null
            gate.channel = null
            try {
                lock?.release()
            } finally {
                channel?.close()
            }
        }
    }

    private fun openChannel(): FileChannel {
        check(directory.isDirectory || directory.mkdirs() || directory.isDirectory) {
            "Could not create media claim fence directory"
        }
        check(!Files.isSymbolicLink(directory.toPath())) { "Media claim fence directory is a symlink" }
        return FileChannel.open(
            File(directory, ".claims.lock").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
    }

    /** Process-wide state for one lock file; guarded by [state]. */
    private class ClaimGate {
        val state = Mutex()
        var shares = 0
        var exclusive = false
        var channel: FileChannel? = null
        var lock: FileLock? = null
    }

    private class HeldFence(
        val path: String,
        val shared: Boolean,
    ) : AbstractCoroutineContextElement(HeldFence) {
        companion object : CoroutineContext.Key<HeldFence>
    }

    private companion object {
        val gates = ConcurrentHashMap<String, ClaimGate>()
    }
}
