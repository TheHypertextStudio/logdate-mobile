package app.logdate.wear.sync.v2.delivery

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.wear.sync.v2.link.WatchLinkStore
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException

class WatchDeliveryPeer(
    private val links: WatchLinkStore,
    private val sessions: SessionStorage,
    private val config: LogDateConfigRepository,
    private val localNodeId: suspend () -> String?,
    private val active: suspend () -> Boolean,
    private val removed: suspend () -> Boolean = { false },
) {
    suspend fun current(): PeerDeliveryContext? =
        try {
            capture()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not read the watch's delivery audience; captures wait", e)
            null
        }

    suspend fun forRemoval(): PeerDeliveryContext? = capture(requireActive = false, allowRemoved = true)

    private suspend fun capture(
        requireActive: Boolean = true,
        allowRemoved: Boolean = false,
    ): PeerDeliveryContext? {
        if ((requireActive && !active()) || (!allowRemoved && removed())) return null
        val record = links.current() ?: return null
        if (record.stale || record.installing || record.problem != null) return null
        val bound = sessions.getOriginBoundSession()
        val session = sessions.getSession()
        val backend = config.getCurrentBackendUrl()
        val localNode = localNodeId() ?: return null
        val context =
            PeerDeliveryContext(
                linkId = record.linkId ?: return null,
                phoneNode = record.phoneNode,
                watchNode = localNode,
                mode = record.mode,
                accountId = record.accountId,
                serverOrigin = record.serverOrigin,
            )
        if (!context.isValid()) return null
        val accountMatches =
            when (record.mode) {
                LinkMode.PAIR -> {
                    bound == null && session == null
                }

                LinkMode.CLOUD -> {
                    bound != null &&
                        bound.session == session &&
                        bound.session.accountId == record.accountId &&
                        bound.origin == record.serverOrigin &&
                        bound.origin == backend
                }
            }
        if (!accountMatches ||
            (requireActive && !active()) ||
            links.current() != record ||
            sessions.getOriginBoundSession() != bound ||
            sessions.getSession() != session ||
            config.getCurrentBackendUrl() != backend
        ) {
            return null
        }
        return context
    }

    suspend fun isCurrent(context: PeerDeliveryContext): Boolean = current() == context
}
