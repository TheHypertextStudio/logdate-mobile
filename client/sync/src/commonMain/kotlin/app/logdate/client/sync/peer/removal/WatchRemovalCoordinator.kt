package app.logdate.client.sync.peer.removal

import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WatchRemovalCoordinator(
    private val current: suspend (PeerDeliveryContext) -> Boolean,
    private val request: suspend (WatchRemovalRequest) -> WatchRemovalReply?,
    private val deferWipe: suspend (WatchRemovalRequest) -> Boolean,
    private val revoke: suspend (WatchRemovalTarget) -> Boolean,
    private val disassociate: suspend (WatchRemovalTarget) -> Boolean,
    private val forget: suspend (WatchRemovalTarget) -> Boolean,
) {
    private val mutex = Mutex()

    /**
     * Removes [target] without ever leaving the person stuck. A watch in reach gets one chance to sync what it
     * holds; whatever it couldn't send is dropped with the rest of its LogDate data rather than blocking removal.
     * A watch out of reach loses its access now and erases itself the next time it connects.
     */
    suspend fun remove(target: WatchRemovalTarget): WatchRemovalResult =
        mutex.withLock {
            if (!current(target.context)) return@withLock WatchRemovalResult.Failed("link changed")
            val drained = request(WatchRemovalRequest(context = target.context, action = RemovalAction.DRAIN))
            if (!current(target.context)) return@withLock WatchRemovalResult.Failed("link changed")
            if (!revoke(target)) return@withLock WatchRemovalResult.Failed("revoke")
            if (drained == null) return@withLock deferAndForget(target)
            var left = unsent(drained, target, RemovalAction.DRAIN)
            if (left == 0) {
                val erased =
                    request(WatchRemovalRequest(context = target.context, action = RemovalAction.WIPE))
                        ?: return@withLock deferAndForget(target)
                left = unsent(erased, target, RemovalAction.WIPE)
                if (left == 0) return@withLock finish(target, discarded = 0)
            }
            // Something is still unsent, or a recording is running: drop it rather than make the person wait.
            val dropped =
                request(WatchRemovalRequest(context = target.context, action = RemovalAction.REVOKED_WIPE))
                    ?: return@withLock deferAndForget(target)
            if (!matches(dropped, target, RemovalAction.REVOKED_WIPE) || !dropped.completed) {
                return@withLock deferAndForget(target)
            }
            finish(target, discarded = left)
        }

    private suspend fun finish(
        target: WatchRemovalTarget,
        discarded: Int?,
    ): WatchRemovalResult {
        if (!disassociate(target)) return WatchRemovalResult.Failed("disassociate")
        if (!forget(target)) return WatchRemovalResult.Failed("clear link")
        return WatchRemovalResult.Removed(discarded)
    }

    private suspend fun deferAndForget(target: WatchRemovalTarget): WatchRemovalResult {
        val deferred = WatchRemovalRequest(context = target.context, action = RemovalAction.REVOKED_WIPE)
        if (!deferWipe(deferred)) return WatchRemovalResult.Failed("queue wipe")
        if (!forget(target)) return WatchRemovalResult.Failed("clear link")
        return WatchRemovalResult.RemovedWillWipeOnNextConnect
    }

    /** How many moments [reply] says are still unsent: 0 when it finished cleanly, null when it can't be trusted. */
    private fun unsent(
        reply: WatchRemovalReply,
        target: WatchRemovalTarget,
        action: RemovalAction,
    ): Int? =
        when {
            !matches(reply, target, action) -> null
            reply.completed && reply.pending == 0 -> 0
            reply.pending > 0 -> reply.pending
            else -> null
        }

    private fun matches(
        reply: WatchRemovalReply,
        target: WatchRemovalTarget,
        action: RemovalAction,
    ): Boolean = reply.schema == 1 && reply.context == target.context && reply.action == action
}
