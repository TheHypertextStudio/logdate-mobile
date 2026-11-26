package app.logdate.client.watch

import android.content.Context
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsProtocol
import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsRequest
import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsResponse
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.client.sync.datalayer.v2.evaluateWearCompatibility
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import app.logdate.feature.core.settings.ui.watch.WatchConnectionManager
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchPeerSnapshot
import app.logdate.feature.core.settings.ui.watch.WatchPingProbe
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticSnapshot
import app.logdate.feature.core.settings.ui.watch.v2.WatchDiagnosticsAccess
import app.logdate.feature.core.settings.ui.watch.v2.displayWatchName
import com.google.android.gms.wearable.Wearable
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

/** Diagnoses the same verified audience the sync workers use, with a five-second round trip. */
class AndroidWatchDiagnosticsAccess(
    private val context: Context,
    private val manager: WatchConnectionManager,
    private val associations: WatchCompanionAssociationManager,
    private val peers: PhoneDeliveryPeers,
    private val states: PeerStateStore,
    private val gate: WearProtocolGate,
    private val phoneVersion: String? = null,
) : WatchDiagnosticsAccess {
    override suspend fun inspect(): WatchDiagnosticSnapshot {
        manager.refresh()
        val connection =
            withTimeoutOrNull(5_000) { manager.observeConnectionState().first { it != WatchConnectionState.Loading } }
                ?: WatchConnectionState.Loading
        val nodeId = connection.nodeId
        val audience = nodeId?.let { peers.current(it) }
        val rawDocument = nodeId?.let { states.documents(PeerRole.WATCH).first()[it] }
        val document =
            audience
                ?.let { rawDocument?.takeIf { doc -> doc.watch?.linkId == it.linkId } }
        val pingProbe = nodeId?.let { manager.observePeerPing(it).first() }
        val reply = audience?.let { ping(it) }
        val rawWatch = rawDocument?.watch
        val pingInfo = (pingProbe as? WatchPingProbe.Supported)?.info
        val watchVersion = reply?.appVersionName ?: pingInfo?.appVersionName ?: rawWatch?.appVersionName
        val schemaUnsupported =
            nodeId?.let { states.currentUnsupportedSchema(it) != null } == true && reply == null && pingInfo == null
        val compatibility =
            evaluateWearCompatibility(
                localAppVersion = phoneVersion,
                peerAppVersion = watchVersion,
                peerProtocolVersion = reply?.protocolVersion ?: pingInfo?.protocolVersion ?: rawWatch?.protocolVersion,
                peerMinPeerProtocolVersion =
                    reply?.minPeerProtocolVersion ?: pingInfo?.minPeerProtocolVersion ?: rawWatch?.minPeerProtocolVersion,
                peerSchemaUnsupported = schemaUnsupported,
                legacyPeerPing = pingProbe is WatchPingProbe.LegacyAck,
            )
        val peer =
            WatchPeerSnapshot(
                v2Active = gate.isV2Active().first(),
                watch = document?.watch,
                features = document?.watch?.features.orEmpty(),
                phoneVersion = phoneVersion,
                watchVersion = watchVersion,
                compatibility = compatibility,
            )
        return WatchDiagnosticSnapshot(
            connection = connection,
            peer = peer,
            associated = audience != null || associations.observeAssociationState().value is WatchAssociationSnapshot.Associated,
            reply = reply,
            watchName = connection.displayWatchName(),
            phoneVersion = phoneVersion,
            watchVersion = watchVersion,
            compatibility = compatibility,
        )
    }

    private suspend fun ping(current: PeerDeliveryContext): WatchDiagnosticsResponse? =
        try {
            val nonce = Uuid.random().toString()
            val request =
                WearJson.encodeToBytes(
                    WatchDiagnosticsRequest.serializer(),
                    WatchDiagnosticsRequest(context = current, nonce = nonce),
                )
            val payload =
                withTimeoutOrNull(5_000) {
                    Wearable.getMessageClient(context).sendRequest(current.watchNode, WatchDiagnosticsProtocol.PATH, request).await()
                }
            val decoded =
                payload?.let {
                    WearJson.decode(it, 1, WatchDiagnosticsResponse.serializer()) as? Decoded.Supported<WatchDiagnosticsResponse>
                }
            decoded?.value?.takeIf {
                it.context == current && it.nonce == nonce && peers.isCurrent(current) && gate.isV2Active().first()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Napier.w("Watch diagnostic round trip unavailable", error)
            null
        }
}
