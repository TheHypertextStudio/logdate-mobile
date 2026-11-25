package app.logdate.client.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import app.logdate.client.sync.WatchMessageSender
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchLinkProblem
import app.logdate.feature.core.settings.ui.watch.WatchLinking
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
) : WatchConnectionManager {
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val snapshots = Mutex()
    private val knownAppNodes = ConcurrentHashMap<String, WatchNodeSnapshot>()
    private val retriedNodes = ConcurrentHashMap.newKeySet<String>()

    override suspend fun refresh() {
        refreshes.emit(Unit)
    }

    override fun observeConnectionState(): Flow<WatchConnectionState> =
        combine(
            associationManager.observeAssociationState(),
            observeTransportSnapshot(),
            verifiedAppNodes,
        ) { associationState, transportSnapshot, appNodes ->
            if (appNodes != null) {
                withLinkStatus(resolveWearAppConnection(transportSnapshot, appNodes))
            } else {
                resolveWatchConnectionState(associationState, transportSnapshot)
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
            watches.withApp.ifEmpty { watches.withoutApp }.forEach { prepareWatch(it.id) }
        } else {
            associationManager.beginAssociation()
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

    override suspend fun openAppOnWatch(): WatchRequestResult {
        val watches = connectedWatches() ?: return WatchRequestResult.Failed(WatchRequestFailure.LOOKUP_UNAVAILABLE)
        if (watches.isEmpty()) return WatchRequestResult.Failed(WatchRequestFailure.NO_WATCH_CONNECTED)
        val target = watches.withApp.firstOrNull() ?: watches.withoutApp.first()
        return startOnWatch(WATCH_TODAY_LINK, target).also { result ->
            if (result == WatchRequestResult.Sent) refresh()
        }
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
        return startOnWatch(WATCH_TODAY_LINK, target).also { result ->
            if (result == WatchRequestResult.Sent) refresh()
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

    /** The connected watches, split by whether they have LogDate, or null when the Wearable API failed. */
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
                val verified = verifiedAppNodes.first().orEmpty()
                val (withApp, withoutApp) =
                    connected.partition {
                        it.id in withAppIds ||
                            it.id in verified ||
                            knownAppNodes.containsKey(it.id) ||
                            pingWatch(it.id)
                    }
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
            val requested = launch { refreshes.collect { emitSnapshot() } }

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

                    val advertisedIds = appNodes.mapTo(mutableSetOf()) { it.id }
                    val probedNodes =
                        connectedNodes.filter { node ->
                            node.id !in advertisedIds && pingWatch(node.id)
                        }
                    val detectedAppNodes = (appNodes + probedNodes).distinctBy { it.id }
                    detectedAppNodes.forEach { knownAppNodes[it.id] = it }
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
        }
}
