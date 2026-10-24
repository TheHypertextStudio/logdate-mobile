package app.logdate.client.sync

import app.logdate.client.domain.reminders.ReminderClaimResponder
import app.logdate.client.media.device.MediaDeviceCategory
import app.logdate.client.remote.RemoteCameraActivity
import app.logdate.client.remote.RemoteCameraSessionController
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.HealthSnapshotDataMapper
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.datalayer.v2.WearProtocolGate
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.StateDocumentCodec
import app.logdate.client.sync.v2.continuity.PhonePlaybackHandoffHandler
import app.logdate.client.sync.v2.continuity.PhoneRecordingContinuity
import app.logdate.client.sync.v2.link.PhoneLinkInitiator
import app.logdate.client.sync.v2.media.PhoneMediaEndpoint
import app.logdate.client.sync.v2.receipts.WatchReceiptResponder
import app.logdate.client.sync.v2.receipts.answerPhoneRequest
import app.logdate.client.sync.v2.window.PhoneWindowBodyEndpoint
import app.logdate.client.sync.v2.window.WatchWindowPublisher
import app.logdate.client.sync.v2.window.WindowResyncResponder
import app.logdate.feature.editor.ui.camera.CameraRemoteCommand
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * Receives watch captures, shared state, media, and library requests with verified link routing.
 * Ingestors preserve cloud-backup queues; Koin resolution retries when the service wakes early.
 */
open class PhoneDataLayerListenerService : WearableListenerService() {
    protected open val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val noteDataMapper = NoteDataMapper()
    private val journalDataMapper = JournalDataMapper()
    private val associationDataMapper = AssociationDataMapper()
    private val healthSnapshotDataMapper = HealthSnapshotDataMapper()
    private val readChannels =
        PhoneReadChannels({ resolveKoin()?.get<PhoneMediaEndpoint>() }, { resolveKoin()?.get<PhoneWindowBodyEndpoint>() })

    @Volatile
    private var cachedLocalNodeId: String? = null

    companion object {
        private const val PATH_CAMERA_OPEN = "/logdate/camera/open"
        private const val PATH_CAMERA_CAPTURE = "/logdate/camera/capture"
        private const val PATH_CAMERA_SWITCH = "/logdate/camera/switch"
        private const val PATH_CAMERA_SELECT = "/logdate/camera/select"
        private const val PATH_CAMERA_CLOSE = "/logdate/camera/close"
        private const val CAMERA_SELECT_FRONT = "front"
        private const val CAMERA_SELECT_BACK = "back"
        private const val CAMERA_SELECT_DEVICE_PREFIX = "device:"
        private const val MAX_KOIN_RETRIES = 3
    }

    /** A watch turning on its redesigned app is the moment to link it, even when LogDate was not running. */
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.name != WearProtocol.WATCH_CAPABILITY_V2 || capabilityInfo.nodes.isEmpty()) return
        serviceScope.launch {
            val initiator = resolveKoin()?.get<PhoneLinkInitiator>()
            if (initiator == null) {
                Napier.e("Koin unavailable after $MAX_KOIN_RETRIES retries; the watch is linked when LogDate next starts")
                return@launch
            }
            initiator.reconcileNow()
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        serviceScope.launch {
            when {
                messageEvent.path == WearPaths.LIVE_RECORDING -> {
                    resolveKoin()?.get<PhoneRecordingContinuity>()?.receive(messageEvent.sourceNodeId, messageEvent.data)
                }

                messageEvent.path == PATH_CAMERA_OPEN -> {
                    Napier.d("Watch requested camera open")
                    try {
                        launchCamera()
                    } catch (e: Exception) {
                        Napier.w("Failed to open camera from watch request", e)
                    }
                }

                messageEvent.path == PATH_CAMERA_CAPTURE -> {
                    Napier.d("Watch requested photo capture")
                    sendCameraCommand(CameraRemoteCommand.Capture)
                }

                messageEvent.path == PATH_CAMERA_SWITCH -> {
                    Napier.d("Watch requested camera switch")
                    sendCameraCommand(CameraRemoteCommand.SwitchBuiltInCamera)
                }

                messageEvent.path == PATH_CAMERA_SELECT -> {
                    val selection = messageEvent.data.decodeToString()
                    Napier.d("Watch requested camera selection: $selection")
                    when {
                        selection == CAMERA_SELECT_FRONT -> {
                            sendCameraCommand(
                                CameraRemoteCommand.SelectCameraCategory(MediaDeviceCategory.FRONT_CAMERA),
                            )
                        }

                        selection == CAMERA_SELECT_BACK -> {
                            sendCameraCommand(
                                CameraRemoteCommand.SelectCameraCategory(MediaDeviceCategory.BACK_CAMERA),
                            )
                        }

                        selection.startsWith(CAMERA_SELECT_DEVICE_PREFIX) -> {
                            sendCameraCommand(
                                CameraRemoteCommand.SelectCameraDevice(
                                    selection.removePrefix(CAMERA_SELECT_DEVICE_PREFIX),
                                ),
                            )
                        }

                        else -> {
                            Napier.w("Unknown remote camera selection: $selection")
                        }
                    }
                }

                messageEvent.path == PATH_CAMERA_CLOSE -> {
                    Napier.d("Watch requested camera close")
                    sendCameraCommand(CameraRemoteCommand.Close)
                }

                messageEvent.path == WearAudioRequestPaths.SYNC_REQUEST_PATH -> {
                    if (publishWindowInstead(messageEvent.sourceNodeId)) return@launch
                    val syncBridge = resolvePhoneWearSyncBridge() ?: return@launch
                    syncBridge.publishNotesToWatch(sourceNodeId = messageEvent.sourceNodeId)
                }

                WearAudioRequestPaths.isAudioRequestPath(messageEvent.path) -> {
                    val syncBridge = resolvePhoneWearSyncBridge() ?: return@launch
                    val noteId =
                        runCatching {
                            WearAudioRequestPaths.noteIdFromAudioRequestPath(messageEvent.path)
                        }.getOrElse { error ->
                            Napier.w("Invalid audio request path: ${messageEvent.path}", error)
                            return@launch
                        }
                    syncBridge.streamAudioToWatch(
                        noteId = noteId,
                        sourceNodeId = messageEvent.sourceNodeId,
                    )
                }

                else -> {
                    Napier.d("Unknown message path: ${messageEvent.path}")
                }
            }
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (readChannels.recognizes(channel.path)) {
            serviceScope.launch { if (!readChannels.serve(channel)) closeChannel(channel) }
            return
        }
        if (WearPaths.parseRecordMedia(channel.path) != null) {
            serviceScope.launch { receiveRecordMedia(channel) }
            return
        }
        if (!WearAudioRequestPaths.isAudioTransferPath(channel.path)) {
            Napier.d("Ignoring channel at unknown path: ${channel.path}")
            return
        }
        serviceScope.launch { receiveAudioChannel(channel) }
    }

    /**
     * Streams the watch's audio file into the ingestor. Runs inside [NonCancellable] so a service
     * teardown mid-transfer does not leave a half-received file for the watch to retry.
     */
    private suspend fun receiveAudioChannel(channel: ChannelClient.Channel) =
        withContext(NonCancellable) {
            val noteId =
                runCatching { WearAudioRequestPaths.noteIdFromAudioTransferPath(channel.path) }
                    .getOrElse { error ->
                        Napier.w("Invalid audio transfer path: ${channel.path}", error)
                        closeChannel(channel)
                        return@withContext
                    }
            val ingestor = resolveSyncDependencies()?.noteIngestor
            if (ingestor == null) {
                Napier.e("Koin unavailable, dropping audio from the watch for note $noteId")
                closeChannel(channel)
                return@withContext
            }

            try {
                openChannelInput(channel).use { input -> ingestor.onAudioBytes(noteId, input) }
            } catch (e: Exception) {
                Napier.w("Failed to receive audio from the watch for note $noteId", e)
            } finally {
                closeChannel(channel)
            }
        }

    /** A version 2 recording from the watch; the watch sends it again when this fails. */
    private suspend fun receiveRecordMedia(channel: ChannelClient.Channel) =
        withContext(NonCancellable) {
            if (!receiptsActive()) {
                closeChannel(channel)
                return@withContext
            }
            val ingestor = resolveSyncDependencies()?.recordIngestorV2
            if (ingestor == null) {
                Napier.e("Koin unavailable; the watch sends this recording again")
                closeChannel(channel)
                return@withContext
            }
            try {
                openChannelInput(channel).use { input -> ingestor.onMedia(channel.nodeId, channel.path, input) }
            } catch (e: Exception) {
                Napier.w("Failed to receive a recording from the watch", e)
            } finally {
                closeChannel(channel)
            }
        }

    /** Answers a watch's version 2 requests; when nothing can, the request fails and the watch asks again. */
    override fun onRequest(
        nodeId: String,
        path: String,
        request: ByteArray,
    ): Task<ByteArray> = answerPhoneRequest(serviceScope) { answerRequest(nodeId, path, request) }

    protected open suspend fun answerRequest(
        nodeId: String,
        path: String,
        request: ByteArray,
    ): ByteArray? =
        when {
            path == WearPaths.LIVE_PLAYBACK_COMMAND -> {
                withTimeoutOrNull(5_000L) { resolveKoin()?.get<PhonePlaybackHandoffHandler>() }
                    ?.answer(nodeId, request)
            }

            path == WearPaths.RECEIPTS && receiptsActive() -> {
                resolveKoin()?.get<WatchReceiptResponder>()?.answer(nodeId, request)
            }

            path == WearPaths.WINDOW_RESYNC -> {
                resolveKoin()?.get<WindowResyncResponder>()?.answer(nodeId, request)
            }

            path == WearPaths.REMINDERS_CLAIM -> {
                resolveKoin()?.get<ReminderClaimResponder>()?.answer(nodeId, request)
            }

            else -> {
                readChannels.answer(nodeId, path, request)
            }
        }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Snapshot events before they are recycled by the system
        val events = mutableListOf<PhoneDataLayerSnapshotEvent>()
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            val path = uri.path ?: continue
            val data = if (event.type == DataEvent.TYPE_CHANGED) event.stringPayload() else emptyMap()
            events.add(PhoneDataLayerSnapshotEvent(path, data, type = event.type, sourceNodeId = uri.host))
        }

        if (events.isEmpty()) return

        serviceScope.launch {
            processSnapshotEvents(events)
        }
    }

    protected open suspend fun processSnapshotEvents(events: List<PhoneDataLayerSnapshotEvent>) {
        val dependencies = resolveSyncDependencies()
        if (dependencies == null) {
            Napier.e("Koin unavailable after $MAX_KOIN_RETRIES retries, dropping ${events.size} data events")
            return
        }

        val localNodeId = localNodeId()
        for (event in events) {
            if (!event.isChangeFromAnotherDevice(localNodeId)) continue
            processSnapshotEvent(event, dependencies)
        }
    }

    protected open suspend fun receiptsActive(): Boolean =
        resolveKoin()?.get<WearProtocolGate>()?.isFeatureActive(WearFeatures.RECEIPTS)?.first() == true

    protected open suspend fun resolveSyncDependencies(): PhoneDataLayerSyncDependencies? {
        val koin = resolveKoin() ?: return null
        return PhoneDataLayerSyncDependencies(
            noteIngestor = koin.get(),
            journalIngestor = koin.get(),
            healthSnapshotDao = koin.get(),
            peerStates = koin.get(),
            recordIngestorV2 = koin.get(),
        )
    }

    /**
     * Processes a single data event. Runs inside [NonCancellable] to ensure
     * database writes complete even if the service is destroyed mid-flight.
     */
    protected open suspend fun processSnapshotEvent(
        event: PhoneDataLayerSnapshotEvent,
        dependencies: PhoneDataLayerSyncDependencies,
    ) = withContext(NonCancellable) {
        val path = event.path
        val records = dependencies.recordIngestorV2

        try {
            when {
                WearPaths.parseWatchRecord(path) != null -> if (receiptsActive()) records?.onRecord(event.sourceNodeId, path, event.data)

                NoteDataMapper.isDeletePath(path) -> handleNoteDelete(path, dependencies.noteIngestor)

                NoteDataMapper.isNotePath(path) -> handleNoteSync(event, path, dependencies.noteIngestor)

                JournalDataMapper.isDeletePath(path) -> handleJournalDelete(path, dependencies.journalIngestor)

                JournalDataMapper.isJournalPath(path) -> handleJournalSync(event, path, dependencies.journalIngestor)

                AssociationDataMapper.isDeletePath(path) -> handleAssociationDelete(path, dependencies.journalIngestor)

                AssociationDataMapper.isAssociationPath(path) -> handleAssociationSync(event, path, dependencies.journalIngestor)

                HealthSnapshotDataMapper.isHealthPath(
                    path,
                ) -> handleHealthSync(event, path, dependencies.healthSnapshotDao, healthSnapshotDataMapper)

                path == WearPaths.STATE -> acceptWatchState(event, dependencies.peerStates)
            }
        } catch (e: Exception) {
            Napier.e("Unhandled error processing sync event at path: $path", e)
        }
    }

    private suspend fun handleNoteDelete(
        path: String,
        ingestor: WatchNoteIngestor,
    ) {
        val noteId =
            try {
                NoteDataMapper.noteIdFromPath(path)
            } catch (e: IllegalArgumentException) {
                Napier.w("Invalid note ID in delete path: $path", e)
                return
            }

        Napier.d("Received delete signal from watch for note: $noteId")
        try {
            ingestor.onNoteDeleted(noteId)
        } catch (e: Exception) {
            Napier.w("Failed to delete synced note from watch: $noteId", e)
        }
    }

    private suspend fun handleNoteSync(
        event: PhoneDataLayerSnapshotEvent,
        path: String,
        ingestor: WatchNoteIngestor,
    ) {
        try {
            ingestor.onNoteMetadata(event.data)
        } catch (e: Exception) {
            Napier.w("Failed to process synced note from watch at path: $path", e)
        }
    }

    private suspend fun handleJournalDelete(
        path: String,
        ingestor: WatchJournalIngestor,
    ) {
        val journalId =
            try {
                JournalDataMapper.journalIdFromPath(path)
            } catch (e: IllegalArgumentException) {
                Napier.w("Invalid journal ID in delete path: $path", e)
                return
            }

        Napier.d("Received delete signal from watch for journal: $journalId")
        try {
            ingestor.onJournalDeleted(journalId)
        } catch (e: Exception) {
            Napier.w("Failed to delete synced journal from watch: $journalId", e)
        }
    }

    private suspend fun handleJournalSync(
        event: PhoneDataLayerSnapshotEvent,
        path: String,
        ingestor: WatchJournalIngestor,
    ) {
        try {
            val journal = journalDataMapper.fromDataMap(event.data)
            Napier.d("Received journal from watch: ${journal.id}")
            ingestor.onJournal(journal)
        } catch (e: Exception) {
            Napier.w("Failed to process synced journal from watch at path: $path", e)
        }
    }

    private suspend fun handleAssociationDelete(
        path: String,
        ingestor: WatchJournalIngestor,
    ) {
        val (journalId, contentId) =
            try {
                AssociationDataMapper.idsFromPath(path)
            } catch (e: Exception) {
                Napier.w("Invalid association path: $path", e)
                return
            }

        Napier.d("Received association delete from watch: journal=$journalId, content=$contentId")
        try {
            ingestor.onAssociationDeleted(journalId, contentId)
        } catch (e: Exception) {
            Napier.w("Failed to delete synced association: $path", e)
        }
    }

    private suspend fun handleAssociationSync(
        event: PhoneDataLayerSnapshotEvent,
        path: String,
        ingestor: WatchJournalIngestor,
    ) {
        try {
            val (journalId, contentId) = associationDataMapper.fromDataMap(event.data)
            Napier.d("Received association from watch: journal=$journalId, content=$contentId")
            ingestor.onAssociation(journalId, contentId)
        } catch (e: Exception) {
            Napier.w("Failed to process synced association from watch at path: $path", e)
        }
    }

    private fun acceptWatchState(
        event: PhoneDataLayerSnapshotEvent,
        peerStates: PeerStateStore,
    ) {
        val watchNode = event.sourceNodeId
        if (watchNode == null) {
            Napier.w("A state document arrived without the node that wrote it; ignoring it")
            return
        }
        peerStates.accept(watchNode, StateDocumentCodec.fromDataMap(event.data), PeerRole.WATCH)
    }

    private suspend fun resolveKoin(): org.koin.core.Koin? = resolvePhoneServiceKoin()

    protected open suspend fun publishWindowInstead(nodeId: String): Boolean =
        resolveKoin()?.get<WatchWindowPublisher>()?.publishInstead(nodeId) == true

    protected open suspend fun resolvePhoneWearSyncBridge(): PhoneWearSyncBridge? = resolveKoin()?.get()

    /** Identifies this phone's own writes, which the Data Layer sends back as events. */
    protected open suspend fun localNodeId(): String? {
        cachedLocalNodeId?.let { return it }
        return try {
            Wearable
                .getNodeClient(this)
                .localNode
                .await()
                .id
                .also { cachedLocalNodeId = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not read this phone's Data Layer node; acting on every data change", e)
            null
        }
    }

    protected open suspend fun openChannelInput(channel: ChannelClient.Channel): InputStream =
        Wearable.getChannelClient(this).getInputStream(channel).await()

    protected open suspend fun closeChannel(channel: ChannelClient.Channel) {
        runCatching { Wearable.getChannelClient(this).close(channel).await() }
            .onFailure { error -> Napier.w("Failed to close audio channel ${channel.path}", error) }
    }

    protected open fun launchCamera() {
        startActivity(RemoteCameraActivity.createIntent(this))
    }

    protected open fun sendCameraCommand(command: CameraRemoteCommand): Boolean = RemoteCameraSessionController.send(command)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
