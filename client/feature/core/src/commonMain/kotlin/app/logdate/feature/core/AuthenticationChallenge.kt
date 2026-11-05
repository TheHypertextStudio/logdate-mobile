package app.logdate.feature.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet

/** Owns one native challenge and its success while the host returns from system authentication. */
internal class AuthenticationChallenge {
    private var generation = 0L
    private var current: Long? = null
    private var successful = false
    private var hostPaused = false

    fun begin(): Long {
        generation += 1
        current = generation
        successful = false
        hostPaused = false
        return generation
    }

    fun isCurrent(id: Long): Boolean = current == id

    fun markHostPaused() {
        if (current != null) hostPaused = true
    }

    fun recordSuccess(
        id: Long,
        hostResumed: Boolean = true,
        deviceCredential: Boolean = false,
    ): Boolean {
        if (!isCurrent(id)) return false
        if ((!hostResumed || hostPaused) && !deviceCredential) {
            invalidate()
            return false
        }
        successful = true
        return true
    }

    fun consumeSuccess(): Boolean {
        if (!successful || current == null) return false
        invalidate()
        return true
    }

    fun invalidate() {
        current = null
        successful = false
    }
}

/** Records provider feedback independently of the application's lifecycle-owned unlock gate. */
internal fun completeAuthenticationChallenge(
    globalState: MutableStateFlow<AppAuthState>,
    result: AppAuthState,
    onResult: (AppAuthState) -> Unit,
) {
    globalState.value = result
    onResult(result)
}

/** One native prompt owns its callback until a terminal result, across all authentication purposes. */
internal class AuthenticationChallengeOwner(
    private val publish: (AppAuthState) -> Unit,
) {
    private val generation = MutableStateFlow(0L)
    private val active = MutableStateFlow<Long?>(null)

    fun begin(onResult: (AppAuthState) -> Unit): ((AppAuthState) -> Unit)? {
        val request = generation.updateAndGet { it + 1 }
        if (!active.compareAndSet(null, request)) {
            onResult(AppAuthState.REQUIRE_PROMPT)
            return null
        }
        return { result ->
            if (active.compareAndSet(request, null)) {
                publish(result)
                onResult(result)
            }
        }
    }
}
