@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.uuid.Uuid

internal class InProcessAccountCredentialMutationLock : AccountCredentialMutationLock {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val guard = Mutex()
    private val entries = mutableMapOf<Uuid, Entry>()

    override suspend fun <T> withLock(
        accountId: Uuid,
        block: suspend () -> T,
    ): T {
        val entry = guard.withLock { entries.getOrPut(accountId) { Entry() }.also { it.users++ } }
        var acquired = false
        try {
            withTimeout(10_000) {
                entry.mutex.lock()
                acquired = true
            }
            return block()
        } finally {
            if (acquired) entry.mutex.unlock()
            withContext(NonCancellable) {
                guard.withLock {
                    entry.users--
                    if (entry.users == 0) entries.remove(accountId)
                }
            }
        }
    }
}
