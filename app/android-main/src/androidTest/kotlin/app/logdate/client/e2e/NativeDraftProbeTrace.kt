package app.logdate.client.e2e

import android.util.Log
import java.io.Closeable
import java.util.Timer
import java.util.TimerTask

/** Bounded thread evidence; callers retain their original probe deadlines. */
internal class NativeDraftProbeTrace(
    private val probeThread: Thread,
    private val tag: String = TAG,
    private val includeRuntimeWorkers: Boolean = false,
    private val sampleRuntime: () -> Unit = {},
) : Closeable {
    private val timer = Timer("native-draft-probe-trace", true)

    init {
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    runCatching {
                        captureThreads()
                    }.onFailure { Log.w(tag, "Stack capture unavailable: ${it.javaClass.name}") }
                }
            },
            90_000L,
        )
    }

    private fun captureThreads() {
        runCatching(sampleRuntime).onFailure { Log.w(tag, "Runtime state unavailable: ${it.javaClass.name}") }
        val all = Thread.getAllStackTraces()
        val selected =
            all.entries
                .filter { (thread, stack) -> priority(thread, stack) < 4 }
                .sortedBy { (thread, stack) -> "${priority(thread, stack)}-${thread.name}" }
                .take(48)
        if (includeRuntimeWorkers) Log.i(tag, "thread_count=${all.size} captured=${selected.size}")
        selected.forEach { (thread, stack) ->
            Log.i(tag, "thread=${thread.name} state=${thread.state}")
            stack.take(64).forEach { Log.i(tag, it.toString()) }
        }
    }

    private fun priority(
        thread: Thread,
        stack: Array<StackTraceElement>,
    ): Int =
        when {
            thread === probeThread || thread.name == "main" -> 0
            !includeRuntimeWorkers -> 4
            stack.any { it.className.startsWith("app.logdate.") } -> 1
            stack.any { it.className.startsWith("androidx.datastore.") || it.className.startsWith("androidx.room.") } -> 2
            thread.name.startsWith("DefaultDispatcher-worker-") -> 3
            else -> 4
        }

    fun reportFailure(failure: Throwable) {
        runCatching {
            Log.e(tag, "Probe body failed: ${failure.javaClass.name}")
            failure.stackTrace.take(64).forEach { Log.e(tag, it.toString()) }
        }
    }

    override fun close() {
        timer.cancel()
    }

    private companion object {
        const val TAG = "NativeCurrentDraftTrace"
    }
}
