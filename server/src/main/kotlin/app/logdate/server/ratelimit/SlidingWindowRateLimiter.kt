package app.logdate.server.ratelimit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock

/** How many [SlidingWindowRateLimiter.allow] calls pass between sweeps of idle keys. */
private const val SLIDING_SWEEP_EVERY_CALLS = 1_024

/**
 * Per-key sliding-window rate limiter.
 *
 * Hands out request quotas that refill continuously over [RateLimitPolicy.windowSeconds]. Each
 * [allow] call records the current timestamp under its key (usually a user ID or an IP hash), drops
 * timestamps older than the window, and rejects when the window already holds
 * [RateLimitPolicy.maxRequests]. Keys whose window has emptied are swept away every
 * [SLIDING_SWEEP_EVERY_CALLS] calls, so memory follows the number of recently active keys.
 *
 * Counts live in this instance's memory, which is enough for the per-instance limits it serves.
 */
class SlidingWindowRateLimiter {
    private class Window(
        var windowMillis: Long,
    ) {
        val timestamps = ArrayDeque<Long>()

        fun trim(nowEpochMillis: Long) {
            val windowStart = nowEpochMillis - windowMillis
            while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
                timestamps.removeFirst()
            }
        }
    }

    private val requestsByKey = ConcurrentHashMap<String, Window>()
    private val calls = AtomicInteger()

    fun allow(
        key: String,
        policy: RateLimitPolicy,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean {
        val windowMillis = policy.windowSeconds * 1000L
        var allowed = false
        requestsByKey.compute(key) { _, current ->
            val window = current ?: Window(windowMillis)
            window.windowMillis = windowMillis
            window.trim(nowEpochMillis)
            if (window.timestamps.size < policy.maxRequests) {
                window.timestamps.addLast(nowEpochMillis)
                allowed = true
            }
            window
        }
        if (calls.incrementAndGet() % SLIDING_SWEEP_EVERY_CALLS == 0) sweep(nowEpochMillis)
        return allowed
    }

    /**
     * Returns how many seconds a caller rejected by [allow] should wait before their oldest
     * tracked request falls out of the window and frees a slot. Used to populate the
     * `Retry-After` response header on 429s.
     */
    fun retryAfterSeconds(
        key: String,
        policy: RateLimitPolicy,
        nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
    ): Long {
        var oldest: Long? = null
        requestsByKey.computeIfPresent(key) { _, window ->
            oldest = window.timestamps.firstOrNull()
            window
        }
        val windowEnd = (oldest ?: return 0) + policy.windowSeconds * 1000L
        val waitMs = (windowEnd - nowEpochMillis).coerceAtLeast(0)
        return (waitMs + 999) / 1000
    }

    /** How many keys have requests in memory. */
    internal fun trackedKeys(): Int = requestsByKey.size

    private fun sweep(nowEpochMillis: Long) {
        requestsByKey.keys.forEach { key ->
            requestsByKey.computeIfPresent(key) { _, window ->
                window.trim(nowEpochMillis)
                window.takeIf { it.timestamps.isNotEmpty() }
            }
        }
    }
}

data class RateLimitPolicy(
    val maxRequests: Int,
    val windowSeconds: Int,
)
