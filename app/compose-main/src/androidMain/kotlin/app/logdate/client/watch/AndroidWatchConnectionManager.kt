package app.logdate.client.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import app.logdate.client.sync.WatchMessageSender
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import app.logdate.client.sync.datalayer.v2.WatchPingInfo
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchLinking
import app.logdate.feature.core.settings.ui.watch.WatchPingProbe
import app.logdate.feature.core.settings.ui.watch.WatchRequestFailure
import app.logdate.feature.core.settings.ui.watch.WatchRequestResult
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val REMOTE_START_TIMEOUT_SECONDS = 15L
private const val WATCH_PING_TIMEOUT_MS = 2_000L

/** Opens LogDate on the watch at Today. The watch's MainActivity declares a matching VIEW filter. */
const val WATCH_TODAY_LINK = "studio.hypertext.logdate://watch/today"

/** LogDate's Play Store listing, which the watch opens in its own Play Store. */
fun playListingLink(packageName: String): String = "market://details?id=$packageName"

/**
 * The only intent shape [RemoteActivityHelper] starts on another device: ACTION_VIEW with a data
 * URI and CATEGORY_BROWSABLE. Anything else fails with IllegalArgumentException before it is sent.
 */
fun watchLinkIntent(link: String): Intent =
    Intent(Intent.ACTION_VIEW)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .setData(Uri.parse(link))

/**
 * Android implementation of [WatchConnectionManager] using Wear transport plus
 * Companion Device association state from the phone app.
 */
class AndroidWatchConnectionManager(
    private val context: Context,
    private val associationManager: WatchCompanionAssociationManager,
    private val messageSender: WatchMessageSender,
    private val ioDispatcher: CoroutineDispatcher,
    private val nodeClient: NodeClient = Wearable.getNodeClient(context),
    private val capabilityClient: CapabilityClient = Wearable.getCapabilityClient(context),
    private val remoteActivityHelper: RemoteActivityHelper = RemoteActivityHelper(context),
    private val linkIntent: (String) -> Intent = ::watchLinkIntent,
    private val prepareWatch: suspend (String) -> Unit = {},
    private val verifiedAppNodes: Flow<Set<String>?> = flowOf(null),
    private val linkStatus: suspend (String) -> WatchLinking = { WatchLinking() },
    private val retryWatch: suspend (String) -> Unit = {},
    private val linkingScope: CoroutineScope? = null,
    private val pingWatch: suspend (String) -> Boolean = { nodeId ->
        withTimeoutOrNull(WATCH_PING_TIMEOUT_MS) {
            try {
                Wearable
                    .getMessageClient(context)
                    .sendRequest(nodeId, WearPaths.WATCH_PING, byteArrayOf())
                    .await()
                    .isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.d(e) { "Watch presence ping did not reach $nodeId" }
                false
            }
        } ?: false
    },
    private val probeWatchInfo: (suspend (String) -> WatchPingProbe)? = null,
) : WatchConnectionManager {
    private val transportSnapshots =
        MutableSharedFlow<WatchTransportSnapshot>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val selectedNodeId = MutableStateFlow<String?>(null)
    private val snapshots = Mutex()
    private val knownAppNodes = ConcurrentHashMap<String, WatchNodeSnapshot>()
    private val retriedNodes = ConcurrentHashMap.newKeySet<String>()
    private val peerPings = MutableStateFlow<Map<String, WatchPingProbe>>(emptyMap())

    override fun observePeerPing(nodeId: String): Flow<WatchPingProbe?> = peerPings.map { it[nodeId] }.distinctUntilChanged()

    override fun selectWatch(nodeId: String) {
        selectedNodeId.value = nodeId
    }

    override suspend fun refresh() {
        resolveTransportSnapshot()
    }

    override suspend fun onWatchRemoved(nodeId: String) {
        evictNode(nodeId)
        if (selectedNodeId.value == nodeId) {
            selectedNodeId.value = null
        }
        refresh()
    }

    override fun observeConnectionState(): Flow<WatchConnectionState> =
        combine(
            associationManager.observeAssociationState(),
            observeTransportSnapshot(),
            verifiedAppNodes,
            selectedNodeId,
        ) { associationState, transportSnapshot, appNodes, selected ->
            if (appNodes != null) {
                withLinkStatus(resolveWearAppConnection(transportSnapshot, appNodes, selected))
            } else {
                resolveWatchConnectionState(associationState, transportSnapshot, selected)
            }
        }

    /**
     * Says how linking a watch that has LogDate is going, and links it at once the first time the phone
     * sees it unlinked, instead of leaving it behind a wait from an earlier failure. Only a failure that passes
     * by itself is retried this way; one that needs the person, or a watch they removed, waits for them.
     */
    private suspend fun withLinkStatus(state: WatchConnectionState): WatchConnectionState {
        if (state !is WatchConnectionState.NeedsAssociation || state.linking == null) return state
        val node = state.nodeId ?: return state
        val linking =
            try {
                linkStatus(node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.w("Could not read how linking ${state.watchName} is going", e)
                WatchLinking()
            }
        val passesByItself = linking.problem == null || linking.problem == WatchLinkProblem.RETRYING
        if (passesByItself && retriedNodes.add(node)) {
            linkingScope?.launch {
                try {
                    retryWatch(node)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    retriedNodes.remove(node)
                    Napier.w("Could not start linking ${state.watchName}; the phone tries again on the next change", e)
                }
            }
        }
        return state.copy(linking = linking)
    }

    override suspend fun prepareSetup(nodeId: String?) {
        nodeId?.let { prepareWatch(it) }
    }

    override suspend fun beginAssociation() {
        if (verifiedAppNodes.first() != null) {
            // Every watch running LogDate, not only a lone connected node: a second node made this return silently.
            val watches = checkNotNull(connectedWatches()) { "Couldn't look up the connected watches" }
            val chosen = selectedNodeId.value
            val targets =
                chosen
                    ?.let { id -> (watches.withApp + watches.withoutApp).filter { it.id == id } }
                    ?.ifEmpty { null }
                    ?: watches.withApp.ifEmpty { watches.withoutApp }
            targets.forEach { prepareWatch(it.id) }
        } else {
            associationManager.beginAssociation(selectedNodeId.value)
        }
    }

    /** Asks every reachable watch running LogDate to send what it has waiting. */
    override suspend fun requestSync() {
        check(messageSender.send(WearAudioRequestPaths.SYNC_REQUEST_PATH, ByteArray(0))) {
            "No watch running LogDate received the sync request"
        }
    }

    override suspend fun installAppOnWatch(): WatchRequestResult {
        val watches = connectedWatches() ?: return WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE)
        if (watches.isEmpty()) return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        return WatchRequestResult.Failed(
            if (watches.withApp.isNotEmpty()) WatchRequestFailure.ALREADY_INSTALLED else WatchRequestFailure.INSTALLATION_UNKNOWN,
        )
    }

    override suspend fun confirmMissingAppAndInstall(nodeId: String): WatchRequestResult {
        val watches = connectedWatches() ?: return WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE)
        if (watches.withApp.any { it.id == nodeId }) return WatchRequestResult.Failed(WatchRequestFailure.ALREADY_INSTALLED)
        val target =
            watches.withoutApp.firstOrNull { it.id == nodeId }
                ?: return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        return startOnWatch(playListingLink(context.packageName), target)
    }

    override suspend fun updateAppOnWatch(nodeId: String?): WatchRequestResult {
        val watches = connectedWatches() ?: return WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE)
        if (watches.isEmpty()) return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        val chosen = nodeId ?: selectedNodeId.value
        val target =
            chosen?.let { id -> (watches.withApp + watches.withoutApp).firstOrNull { it.id == id } }
                ?: watches.withApp.firstOrNull()
                ?: watches.withoutApp.first()
        return startOnWatch(playListingLink(context.packageName), target)
    }

    override suspend fun openAppOnWatch(): WatchRequestResult {
        val watches = connectedWatches() ?: return WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE)
        if (watches.isEmpty()) return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        val chosen = selectedNodeId.value
        val target =
            chosen?.let { id -> (watches.withApp + watches.withoutApp).firstOrNull { it.id == id } }
                ?: watches.withApp.firstOrNull()
                ?: watches.withoutApp.first()
        return openAndPrepare(target)
    }

    override suspend fun installAppOnWatch(nodeId: String?): WatchRequestResult {
        if (nodeId == null) return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        val watches = connectedWatches() ?: return WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE)
        if ((watches.withApp + watches.withoutApp).none { it.id == nodeId }) {
            return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        }
        return WatchRequestResult.Failed(
            if (watches.withApp.any { it.id == nodeId }) {
                WatchRequestFailure.ALREADY_INSTALLED
            } else {
                WatchRequestFailure.INSTALLATION_UNKNOWN
            },
        )
    }

    override suspend fun openAppOnWatch(nodeId: String?): WatchRequestResult {
        if (nodeId == null) return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        val watches = connectedWatches() ?: return WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE)
        val target =
            (watches.withApp + watches.withoutApp).firstOrNull { it.id == nodeId }
                ?: return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        return openAndPrepare(target)
    }

    private suspend fun openAndPrepare(target: Node): WatchRequestResult =
        startOnWatch(WATCH_TODAY_LINK, target).also { result ->
            if (result == WatchRequestResult.Sent) {
                try {
                    prepareWatch(target.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.w(e) { "Opened LogDate on ${target.displayName}, but preparing the watch link failed" }
                }
                refresh()
            }
        }

    override suspend fun requestSync(nodeId: String?) {
        checkNotNull(nodeId) { "No watch selected" }
        val watches = checkNotNull(connectedWatches()) { "Could not look up the selected watch" }
        check(watches.withApp.any { it.id == nodeId }) { "Selected watch is not reachable" }
        Wearable.getMessageClient(context).sendMessage(nodeId, WearAudioRequestPaths.SYNC_REQUEST_PATH, ByteArray(0)).await()
    }

    private suspend fun startOnWatch(
        link: String,
        watch: Node,
    ): WatchRequestResult =
        try {
            withContext(ioDispatcher) {
                remoteActivityHelper
                    .startRemoteActivity(linkIntent(link), watch.id)
                    .get(REMOTE_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            WatchRequestResult.Sent
        } catch (e: CancellationException) {
            throw e
        } catch (e: TimeoutException) {
            WatchRequestResult.Failed(WatchRequestFailure.WATCH_DID_NOT_RESPOND)
        } catch (e: Exception) {
            Napier.e(e) { "${watch.displayName} did not open $link" }
            WatchRequestResult.Failed(WatchRequestFailure.OPEN_FAILED)
        }

    /** The connected watches, split by live capability/ping truth, never stale cached verification. */
    private suspend fun connectedWatches(): ConnectedWatches? =
        withTimeoutOrNull(5_000) {
            try {
                val connected = nodeClient.connectedNodes.await()
                val withAppIds =
                    capabilityClient
                        .getCapability(WatchNodeSelector.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
                        .await()
                        .nodes
                        .map { it.id }
                        .toSet()
                val (withApp, withoutApp) =
                    connected.partition { node ->
                        if (node.id in withAppIds) {
                            true
                        } else {
                            val probe = probeNode(node.id)
                            val reachable = probe.isInstalled()
                            if (reachable) {
                                peerPings.update { it + (node.id to probe) }
                            } else {
                                evictNode(node.id)
                            }
                            reachable
                        }
                    }
                withApp.forEach { node ->
                    knownAppNodes[node.id] = WatchNodeSnapshot(node.id, node.displayName, node.isNearby)
                }
                withoutApp.forEach { node -> evictNode(node.id) }
                ConnectedWatches(withApp = withApp, withoutApp = withoutApp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e(e) { "Could not list the watches connected to this phone" }
                null
            }
        }

    private class ConnectedWatches(
        val withApp: List<Node>,
        val withoutApp: List<Node>,
    ) {
        fun isEmpty(): Boolean = withApp.isEmpty() && withoutApp.isEmpty()
    }

    /** Watches Wear capability changes and refreshes the current transport snapshot. */
    private fun observeTransportSnapshot(): Flow<WatchTransportSnapshot> =
        callbackFlow {
            suspend fun emitSnapshot() {
                trySend(resolveTransportSnapshot())
            }

            val capabilityListener =
                CapabilityClient.OnCapabilityChangedListener {
                    launch {
                        emitSnapshot()
                    }
                }
            capabilityClient.addListener(capabilityListener, WatchNodeSelector.WATCH_CAPABILITY)
            capabilityClient.addListener(capabilityListener, WearProtocol.WATCH_CAPABILITY_V2)
            // Node reachability can change without an app capability changing. Stop with the collector.
            val polling =
                launch {
                    while (isActive) {
                        emitSnapshot()
                        delay(3_000)
                    }
                }
            val requested = launch { transportSnapshots.collect { trySend(it) } }

            awaitClose {
                polling.cancel()
                requested.cancel()
                capabilityClient.removeListener(capabilityListener, WatchNodeSelector.WATCH_CAPABILITY)
                capabilityClient.removeListener(capabilityListener, WearProtocol.WATCH_CAPABILITY_V2)
            }
        }

    /** Captures the current set of connected nodes and installed LogDate watch nodes. */
    private suspend fun resolveTransportSnapshot(): WatchTransportSnapshot =
        snapshots.withLock {
            val snapshot =
                withTimeoutOrNull(5_000) {
                    try {
                        val connectedNodes =
                            nodeClient.connectedNodes.await().map { node ->
                                WatchNodeSnapshot(
                                    id = node.id,
                                    displayName = node.displayName,
                                    isNearby = node.isNearby,
                                )
                            }
                        val appNodes =
                            capabilityClient
                                .getCapability(WatchNodeSelector.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL)
                                .await()
                                .nodes
                                .map { node ->
                                    WatchNodeSnapshot(
                                        id = node.id,
                                        displayName = node.displayName,
                                        isNearby = node.isNearby,
                                    )
                                }

                        val detectedAppNodes = probeAndTrackAppNodes(connectedNodes, advertisedNodes = appNodes)
                        WatchTransportSnapshot(
                            connectedNodes = connectedNodes,
                            appNodes = detectedAppNodes,
                            knownAppNodes = knownAppNodes.values.toList(),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Napier.e(e) { "Failed to resolve watch transport state" }
                        WatchTransportSnapshot(available = false)
                    }
                } ?: WatchTransportSnapshot(available = false)
            transportSnapshots.tryEmit(snapshot)
            snapshot
        }

    private suspend fun probeAndTrackAppNodes(
        connectedNodes: List<WatchNodeSnapshot>,
        advertisedNodes: List<WatchNodeSnapshot>,
    ): List<WatchNodeSnapshot> {
        val advertisedIds = advertisedNodes.mapTo(mutableSetOf()) { it.id }
        val probedNodes = mutableListOf<WatchNodeSnapshot>()
        connectedNodes.forEach { node ->
            val hasCapability = node.id in advertisedIds
            val existingProbe = peerPings.value[node.id]
            // Probe nodes missing capability (fallback detection) and connected capability nodes without cached version info.
            if (!hasCapability || existingProbe == null || existingProbe is WatchPingProbe.Unreachable) {
                val probe = probeNode(node.id)
                if (probe.isInstalled()) {
                    peerPings.update { it + (node.id to probe) }
                    if (!hasCapability) probedNodes += node
                } else if (!hasCapability) {
                    evictNode(node.id)
                }
            }
        }
        val detectedAppNodes = (advertisedNodes + probedNodes).distinctBy { it.id }
        val detectedIds = detectedAppNodes.mapTo(mutableSetOf()) { it.id }
        detectedAppNodes.forEach { knownAppNodes[it.id] = it }
        // Evict connected nodes that stopped reporting LogDate (e.g. after uninstall), while keeping
        // out-of-range node metadata intact until that watch reconnects.
        connectedNodes.forEach { node ->
            if (node.id !in detectedIds) {
                evictNode(node.id)
            }
        }
        return detectedAppNodes
    }

    private suspend fun probeNode(nodeId: String): WatchPingProbe =
        if (probeWatchInfo != null) {
            probeWatchInfo(nodeId)
        } else if (pingWatch(nodeId)) {
            WatchPingProbe.ReachableUnknown
        } else {
            WatchPingProbe.Unreachable
        }

    private fun evictNode(nodeId: String) {
        knownAppNodes.remove(nodeId)
        retriedNodes.remove(nodeId)
        peerPings.update { it - nodeId }
    }

    private fun WatchPingProbe.isInstalled(): Boolean =
        when (this) {
            is WatchPingProbe.Supported,
            WatchPingProbe.LegacyAck,
            WatchPingProbe.ReachableUnknown,
            -> true

            WatchPingProbe.Unreachable -> false
        }
}
