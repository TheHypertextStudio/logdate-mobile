package app.logdate.feature.core.settings.ui.watch

import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.client.sync.datalayer.v2.evaluateWearCompatibility
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.StateMerger
import app.logdate.client.sync.peer.state.WatchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/**
 * Reads the watch's report from the state documents the phone received, through [StateMerger] so a
 * phone-owned section a watch document claims is ignored and a stale presence reads as unknown.
 */
class PeerStateWatchSnapshotSource(
    private val gate: WearProtocolGate,
    private val peerStates: PeerStateStore,
    private val merger: StateMerger = StateMerger(),
    private val accepts: suspend (String, WatchState) -> Boolean = { _, _ -> true },
    private val relinkFailing: (String) -> Flow<Boolean> = { flowOf(false) },
    private val phoneVersion: String? = null,
    private val pingProbe: (String) -> Flow<WatchPingProbe?> = { flowOf(null) },
) : WatchPeerSnapshotSource {
    override fun observeFor(nodeId: String): Flow<WatchPeerSnapshot> =
        combine(
            gate.isV2Active(),
            peerStates.documents(PeerRole.WATCH),
            peerStates.unsupportedSchema(nodeId),
            relinkFailing(nodeId),
            pingProbe(nodeId),
        ) { active, documents, unsupportedSchema, failing, ping ->
            val rawDocument = documents[nodeId]
            val report = merger.merge(phone = null, watch = rawDocument).watch?.takeIf { accepts(nodeId, it) }
            val rawWatch = rawDocument?.watch
            val pingInfo = (ping as? WatchPingProbe.Supported)?.info
            val watchVersion = rawWatch?.appVersionName ?: pingInfo?.appVersionName
            val compatibility =
                evaluateWearCompatibility(
                    localAppVersion = phoneVersion,
                    peerAppVersion = watchVersion,
                    peerProtocolVersion = rawWatch?.protocolVersion ?: pingInfo?.protocolVersion,
                    peerMinPeerProtocolVersion = rawWatch?.minPeerProtocolVersion ?: pingInfo?.minPeerProtocolVersion,
                    peerSchemaUnsupported = unsupportedSchema != null,
                    legacyPeerPing = ping is WatchPingProbe.LegacyAck,
                )
            WatchPeerSnapshot(
                v2Active = active,
                watch = report,
                features = report?.features.orEmpty(),
                relinkFailing = failing,
                phoneVersion = phoneVersion,
                watchVersion = watchVersion,
                compatibility = compatibility,
            )
        }.distinctUntilChanged()

    override fun observe(): Flow<WatchPeerSnapshot> =
        combine(
            gate.isV2Active(),
            peerStates.latest(PeerRole.WATCH),
            peerStates.features(PeerRole.WATCH),
        ) { active, document, features ->
            val rawWatch = document?.watch
            val watchVersion = rawWatch?.appVersionName
            val compatibility =
                evaluateWearCompatibility(
                    localAppVersion = phoneVersion,
                    peerAppVersion = watchVersion,
                    peerProtocolVersion = rawWatch?.protocolVersion,
                    peerMinPeerProtocolVersion = rawWatch?.minPeerProtocolVersion,
                )
            WatchPeerSnapshot(
                v2Active = active,
                watch = merger.merge(phone = null, watch = document).watch,
                features = features,
                phoneVersion = phoneVersion,
                watchVersion = watchVersion,
                compatibility = compatibility,
            )
        }.distinctUntilChanged()
}
