package app.logdate.wear.sync.v2.removal

import app.logdate.client.device.storage.SecureStorage
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.peer.removal.RemovalAction
import app.logdate.client.sync.peer.removal.WatchRemovalReply
import app.logdate.client.sync.peer.removal.WatchRemovalRequest
import app.logdate.wear.capture.WatchCaptureWriteGuard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists outside ordinary preferences so a crash during preference erasure cannot restore a link. */
class WatchRemovalLock(
    private val storage: SecureStorage,
) : WatchCaptureWriteGuard {
    private val mutations = Mutex()

    override suspend fun <T> write(block: suspend () -> T): T =
        mutations.withLock {
            check(state() == null) { "This watch is being removed; finish removal before capturing" }
            block()
        }

    suspend fun ingest(block: suspend () -> Unit) =
        mutations.withLock {
            if (state() == null) block()
        }

    suspend fun <T> erase(block: suspend () -> T): T = mutations.withLock { block() }

    suspend fun removed(): Boolean = state() != null

    suspend fun prepare(
        linkId: String,
        pending: suspend () -> Int,
    ): Int? =
        mutations.withLock {
            val state = state()
            if (state != null && state != "prepared:$linkId") return@withLock null
            val count = pending()
            if (count == 0 && state == null) {
                storage.putString(KEY, "prepared:$linkId")
                check(state() == "prepared:$linkId")
            }
            count
        }

    suspend fun isPrepared(linkId: String): Boolean = state() == "prepared:$linkId"

    suspend fun beginWipe(
        linkId: String,
        pending: suspend () -> Int,
    ): Int? =
        mutations.withLock {
            val state = state()
            if (state != "prepared:$linkId" && state != "wiping:$linkId") return@withLock null
            val count = pending()
            if (count == 0) {
                storage.putString(KEY, "wiping:$linkId")
                check(state() == "wiping:$linkId")
            }
            count
        }

    suspend fun beginRevokedWipe(linkId: String): Boolean =
        mutations.withLock {
            val state = state()
            if (state != null && state != "prepared:$linkId" && state != "wiping:$linkId" && state != "wiping-revoked:$linkId") {
                return@withLock false
            }
            storage.putString(KEY, "wiping-revoked:$linkId")
            check(state() == "wiping-revoked:$linkId")
            true
        }

    suspend fun mark() {
        val state = state()
        val next =
            if (state?.startsWith("wiping-revoked:") == true) {
                state
            } else if (state?.startsWith("prepared:") == true) {
                "wiping:${state.removePrefix("prepared:")}"
            } else {
                state?.takeIf { it.startsWith("wiping:") } ?: "wiping"
            }
        storage.putString(KEY, next)
    }

    suspend fun finish() {
        val state = state()
        val linkId =
            when {
                state?.startsWith("wiping-revoked:") == true -> state.removePrefix("wiping-revoked:")
                state?.startsWith("wiping:") == true -> state.removePrefix("wiping:")
                else -> null
            }
        storage.putString(KEY, linkId?.let { "removed:$it" } ?: "removed")
    }

    suspend fun removedFor(linkId: String): Boolean = state() == "removed:$linkId"

    suspend fun state(): String? = storage.getString(KEY)

    suspend fun reset() =
        mutations.withLock {
            if (state()?.startsWith("removed") == true) storage.remove(KEY)
        }

    companion object {
        const val KEY = "watch.removal.lock.v1"
    }
}

class WatchRemovalEndpoint(
    private val current: suspend () -> PeerDeliveryContext?,
    private val lock: WatchRemovalLock,
    private val recording: () -> Boolean,
    private val drain: suspend () -> Unit,
    private val pending: suspend () -> Int,
    private val wipe: suspend () -> Unit,
    private val forcedWipe: suspend () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun answer(
        node: String,
        payload: ByteArray,
    ): ByteArray? =
        mutex.withLock {
            val request =
                (WearJson.decode(payload, 1, WatchRemovalRequest.serializer()) as? Decoded.Supported)?.value
                    ?: return@withLock null
            if (request.schema != 1 || node != request.context.phoneNode) return@withLock null
            val peer =
                current() ?: return@withLock if (lock.removedFor(request.context.linkId)) reply(request, 0, true) else null
            if (request.context != peer) return@withLock null
            if (recording() && request.action != RemovalAction.REVOKED_WIPE) {
                return@withLock reply(request, pending(), false)
            }
            when (request.action) {
                RemovalAction.DRAIN -> {
                    if (!lock.isPrepared(peer.linkId)) drain()
                    val count = lock.prepare(peer.linkId) { pending() } ?: return@withLock reply(request, pending(), false)
                    reply(request, count, count == 0 && current() == peer && !recording() && lock.isPrepared(peer.linkId))
                }

                RemovalAction.WIPE -> {
                    if (current() != peer) return@withLock null
                    val count = lock.beginWipe(peer.linkId) { pending() } ?: return@withLock reply(request, pending(), false)
                    if (count != 0) return@withLock reply(request, count, false)
                    wipe()
                    reply(request, 0, true)
                }

                RemovalAction.REVOKED_WIPE -> {
                    if (current() != peer || !lock.beginRevokedWipe(peer.linkId)) return@withLock null
                    forcedWipe()
                    reply(request, 0, true)
                }
            }
        }

    private fun reply(
        request: WatchRemovalRequest,
        count: Int,
        completed: Boolean,
    ): ByteArray =
        WearJson.encodeToBytes(
            WatchRemovalReply.serializer(),
            WatchRemovalReply(context = request.context, action = request.action, pending = count, completed = completed),
        )
}
