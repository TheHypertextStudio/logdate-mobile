package app.logdate.server.ratelimit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/** How many [FixedWindowRateLimiter.allow] calls pass between sweeps of ended windows. */
private const val SWEEP_EVERY_CALLS = 1_024

/**
 * Per-key rate limiter that counts requests in fixed windows of [RateLimitPolicy.windowSeconds],
 * each starting at a key's first request after the last one ended. Each key costs one small counter
 * however high its budget, and windows that have ended are swept away every [SWEEP_EVERY_CALLS]
 * calls, so memory follows the number of recently active keys rather than the requests they made.
 *
 * A caller can spend a whole budget at the end of one window and again at the start of the next, so
 * a burst can reach twice [RateLimitPolicy.maxRequests]; budgets meant to stop runaway clients, not
 * to meter exactly, can afford that. Counts live in this instance's memory.
 */
class FixedWindowRateLimiter {
    private class Window(
        val endMillis: Long,
    ) {
        var count = 0
    }

    private val windows = ConcurrentHashMap<String, Window>()
    private val calls = AtomicInteger()

    /** Counts one request under [key] and returns whether it fits [policy]'s budget. */
    fun allow(
        key: String,
        policy: RateLimitPolicy,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        var allowed = false
        windows.compute(key) { _, current ->
            val window = current?.takeIf { nowEpochMillis < it.endMillis } ?: Window(nowEpochMillis + policy.windowSeconds * 1000L)
            if (window.count < policy.maxRequests) {
                window.count++
                allowed = true
            }
            window
        }
        if (calls.incrementAndGet() % SWEEP_EVERY_CALLS == 0) sweep(nowEpochMillis)
        return allowed
    }

    /** Seconds until [key]'s current window ends and its budget is whole again; 0 when it has none. */
    fun retryAfterSeconds(
        key: String,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Long {
        val window = windows[key] ?: return 0
        val waitMs = (window.endMillis - nowEpochMillis).coerceAtLeast(0)
        return (waitMs + 999) / 1000
    }

    /**
     * How many keys have a window in memory.
     *
     * Read by FixedWindowRateLimiterTest to prove the sweep keeps the number of in-memory windows bounded.
     */
    @Suppress("unused")
    internal fun trackedKeys(): Int = windows.size

    private fun sweep(nowEpochMillis: Long) {
        windows.keys.forEach { key -> windows.computeIfPresent(key) { _, window -> window.takeIf { nowEpochMillis < it.endMillis } } }
    }
}
