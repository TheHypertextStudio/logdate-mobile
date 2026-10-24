package app.logdate.client.sync.v2.receipts

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.v2.link.PhoneWatchLinkStore
import app.logdate.shared.config.LogDateConfigRepository
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Resolves a receipt or capture audience from this phone's current verified link and account. */
class PhoneDeliveryPeers(
    private val links: PhoneWatchLinkStore,
    private val sessions: SessionStorage,
    private val config: LogDateConfigRepository,
    private val localNodeId: suspend () -> String?,
    private val active: suspend () -> Boolean,
) {
    suspend fun current(watchNode: String): PeerDeliveryContext? =
        try {
            capture(watchNode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not read the current watch delivery audience", e)
            null
        }

    suspend fun forRemoval(watchNode: String): PeerDeliveryContext? = capture(watchNode, requireActive = false)

    suspend fun isCurrentRemoval(context: PeerDeliveryContext): Boolean = forRemoval(context.watchNode) == context

    suspend fun isCurrent(context: PeerDeliveryContext): Boolean = current(context.watchNode) == context

    suspend fun all(): List<PeerDeliveryContext> =
        try {
            links
                .observe()
                .first()
                .keys
                .mapNotNull { current(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not read linked watch delivery audiences", e)
            emptyList()
        }

    private suspend fun capture(
        watchNode: String,
        requireActive: Boolean = true,
    ): PeerDeliveryContext? {
        if (requireActive && !active()) return null
        val bound = sessions.getOriginBoundSession()
        val session = sessions.getSession()
        val backend = config.getCurrentBackendUrl()
        val record = links.getForReconciliation(watchNode) ?: return null
        if (record.nodeId != watchNode || record.linkedAt == null || record.failure != null) return null
        val localNode = localNodeId() ?: return null
        val context =
            PeerDeliveryContext(
                linkId = record.linkId ?: return null,
                phoneNode = localNode,
                watchNode = record.nodeId,
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
            sessions.getOriginBoundSession() != bound ||
            sessions.getSession() != session ||
            config.getCurrentBackendUrl() != backend ||
            links.getForReconciliation(watchNode) != record
        ) {
            return null
        }
        return context
    }
}
