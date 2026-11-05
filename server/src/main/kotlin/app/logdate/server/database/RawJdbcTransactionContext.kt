package app.logdate.server.database

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Exposed cannot observe the merge coordinator's raw JDBC transaction across coroutine dispatchers. */
internal class RawJdbcTransactionContext :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<Boolean> {
    override fun updateThreadContext(context: CoroutineContext): Boolean {
        val previous = active.get()
        active.set(true)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: Boolean,
    ) {
        active.set(oldState)
    }

    companion object Key : CoroutineContext.Key<RawJdbcTransactionContext> {
        private val active = ThreadLocal.withInitial { false }

        fun isActive(): Boolean = active.get()
    }
}
