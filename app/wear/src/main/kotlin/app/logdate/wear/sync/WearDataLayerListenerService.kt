package app.logdate.wear.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.logdate.client.database.dao.journals.JournalContentDao
import app.logdate.client.database.entities.journals.JournalContentEntityLink
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.reminders.ReminderClaimResponder
import app.logdate.client.domain.reminders.ReminderDeliveryResponder
import app.logdate.client.media.storage.androidMediaFileResolver
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.journals.JournalRepository
import app.logdate.client.repository.journals.SyncableJournalContentRepository
import app.logdate.client.repository.journals.SyncableJournalNotesRepository
import app.logdate.client.repository.journals.SyncableJournalRepository
import app.logdate.client.sync.continuity.HandoffFailure
import app.logdate.client.sync.continuity.PlaybackHandoffResult
import app.logdate.client.sync.datalayer.AssociationDataMapper
import app.logdate.client.sync.datalayer.JournalDataMapper
import app.logdate.client.sync.datalayer.RemoteCameraCaptureResultDataMapper
import app.logdate.client.sync.datalayer.RemoteCameraDeviceDataMapper
import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsProtocol
import app.logdate.client.sync.datalayer.v2.WatchDiagnosticsResponder
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.peer.removal.WatchRemovalProtocol
import app.logdate.client.sync.peer.state.StateDocumentCodec
import app.logdate.wear.launch.resolveWearServiceKoin
import app.logdate.wear.presentation.camera.WearRemoteCameraCaptureResultStore
import app.logdate.wear.presentation.camera.WearRemoteCameraDeviceStore
import app.logdate.wear.reminders.WearPresenceResponder
import app.logdate.wear.sync.v2.WatchStateMirror
import app.logdate.wear.sync.v2.delivery.WatchDeliveryPeer
import app.logdate.wear.sync.v2.link.WatchLinkCoordinator
import app.logdate.wear.sync.v2.link.answerWearRequest
import app.logdate.wear.sync.v2.removal.WatchRemovalEndpoint
import app.logdate.wear.sync.v2.removal.WatchRemovalEvents
import app.logdate.wear.sync.v2.removal.WatchRemovalLock
import app.logdate.wear.sync.v2.window.WindowDataEvents
import app.logdate.wear.sync.v2.window.WindowItem
import app.logdate.wear.v2.continuity.WatchPlaybackReceiver
import app.logdate.wear.v2.continuity.WatchRecordingContinuity
import app.logdate.wear.v2.surfaces.SurfaceUpdater
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMapItem
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Applies peer changes through sync repositories and resumes a retained watch removal. */
class WearDataLayerListenerService : WearableListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val noteDataMapper = NoteDataMapper()
    private val journalDataMapper = JournalDataMapper()
    private val associationDataMapper = AssociationDataMapper()

    @Volatile
    private var cachedLocalNodeId: String? = null

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val data = messageEvent.data
        val path = messageEvent.path
        val source = messageEvent.sourceNodeId
        serviceScope.launch {
            withContext(NonCancellable) { resolvePhoneMessageHandler()?.onMessage(path, data, source) }
        }
    }

    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.nodes.isEmpty()) return
        serviceScope.launch {
            withContext(NonCancellable) { resolvePhoneMessageHandler()?.onPhoneReachable() }
        }
    }

    override fun onRequest(
        nodeId: String,
        path: String,
        request: ByteArray,
    ): Task<ByteArray> = answerWearRequest(serviceScope) { answerRequest(nodeId, path, request) }

    private suspend fun answerRequest(
        nodeId: String,
        path: String,
        request: ByteArray,
    ): ByteArray? {
        if (!WatchLinkCoordinator.handles(path) &&
            path != WearPaths.LIVE_RECORDING_COMMAND &&
            path != WearPaths.LIVE_PLAYBACK_COMMAND &&
            path != WearPaths.PRESENCE &&
            path != WearPaths.REMINDERS_CLAIM &&
            path != WearPaths.REMINDERS_DELIVER &&
            path != WatchRemovalProtocol.PATH &&
            path != WatchDiagnosticsProtocol.PATH
        ) {
            Napier.w("The phone sent a request this watch does not answer: $path")
            return null
        }
        val playbackRequest = path == WearPaths.LIVE_PLAYBACK_COMMAND
        val koin = resolveWearServiceKoin(if (playbackRequest) 8_000L else 1_000L)
        if (koin == null) {
            if (playbackRequest) return playbackFailure(HandoffFailure.WATCH_STARTING)
            Napier.e("Koin unavailable while encrypted storage is not ready; the phone's link request fails and is tried again")
            return null
        }
        return if (path == WatchRemovalProtocol.PATH) {
            koin.get<WatchRemovalEndpoint>().answer(nodeId, request)
        } else if (path == WatchDiagnosticsProtocol.PATH) {
            val peers = koin.get<WatchDeliveryPeer>()
            val flags = koin.get<FeatureFlagStore>()
            WatchDiagnosticsResponder(
                current = peers::current,
                enabled = { flags.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2) },
                notificationsAllowed = { NotificationManagerCompat.from(this).areNotificationsEnabled() },
                microphoneAllowed = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                },
            ).answer(nodeId, request)
        } else if (path == WearPaths.LIVE_RECORDING_COMMAND) {
            withContext(Dispatchers.Main.immediate) { koin.get<WatchRecordingContinuity>().answer(nodeId, request) }
        } else if (path == WearPaths.LIVE_PLAYBACK_COMMAND) {
            withTimeoutOrNull(20_000L) { koin.get<WatchPlaybackReceiver>().answer(nodeId, request) }
                ?: playbackFailure(HandoffFailure.WATCH_DID_NOT_RESPOND)
        } else if (path == WearPaths.REMINDERS_CLAIM) {
            koin.get<ReminderClaimResponder>().answer(nodeId, request)
        } else if (path == WearPaths.REMINDERS_DELIVER) {
            koin.get<ReminderDeliveryResponder>().answer(nodeId, request)
        } else if (path == WearPaths.PRESENCE) {
            koin.get<WearPresenceResponder>().answer(nodeId, request)
        } else {
            koin.get<WatchLinkCoordinator>().answer(nodeId, path, request)
        }
    }

    private fun playbackFailure(failure: HandoffFailure): ByteArray =
        WearJson.encodeToBytes(PlaybackHandoffResult.serializer(), PlaybackHandoffResult(accepted = false, failure = failure))

    /** The watch now belongs to another phone, so the link with [nodeId] is stale. */
    override fun onNodeMigrated(
        nodeId: String,
        dataItems: DataItemBuffer,
    ) {
        dataItems.release()
        serviceScope.launch {
            withContext(NonCancellable) {
                val koin = resolveKoin()
                if (koin == null) {
                    Napier.e("Koin unavailable while encrypted storage is not ready; the move to another phone is noticed when it links")
                    return@withContext
                }
                koin.get<WatchLinkCoordinator>().onNodeMigrated(nodeId)
            }
        }
    }

    private suspend fun resolvePhoneMessageHandler(): WearPhoneMessageHandler? {
        val koin = resolveKoin()
        if (koin == null) {
            Napier.e("Koin unavailable while encrypted storage is not ready, dropping phone message")
            return null
        }
        return koin.get()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Snapshot events before they are recycled by the system
        val events = mutableListOf<SnapshotDataEvent>()
        for (event in dataEvents) {
            val uri = event.dataItem.uri
            val path = uri.path ?: continue
            val data = if (event.type == DataEvent.TYPE_CHANGED) extractStringMap(event) else emptyMap()
            events.add(SnapshotDataEvent(path, data, event.type, uri.host))
        }

        if (events.isEmpty()) return

        serviceScope.launch {
            val localNodeId = localNodeId()
            val syncEvents = mutableListOf<SnapshotDataEvent>()
            for (event in events) {
                if (!isChangeFromAnotherDevice(event.type, event.sourceNodeId, localNodeId)) continue
                when (event.path) {
                    RemoteCameraDeviceDataMapper.PATH_CAMERA_DEVICES -> {
                        handleRemoteCameraDevices(event.data)
                    }

                    RemoteCameraCaptureResultDataMapper.PATH_CAMERA_CAPTURE_RESULT -> {
                        handleRemoteCameraCaptureResult(event.data)
                    }

                    WearPaths.STATE -> {
                        mirrorPhoneState(event, localNodeId)
                    }

                    else -> {
                        syncEvents.add(event)
                    }
                }
            }
            if (syncEvents.isEmpty()) return@launch

            val koin = resolveKoin()
            if (koin == null) {
                Napier.e("Koin unavailable while encrypted storage is not ready, dropping ${syncEvents.size} data events")
                return@launch
            }

            val notesRepository = koin.get<JournalNotesRepository>()
            val journalRepository = koin.get<JournalRepository>()

            for (event in syncEvents) {
                processEvent(event, koin, notesRepository, journalRepository)
            }
            koin.get<WindowDataEvents>().finishBatch()
            koin.getOrNull<SurfaceUpdater>()?.refreshForSync()
        }
    }

    /**
     * Processes a single data event. Runs inside [NonCancellable] to ensure
     * database writes complete even if the service is destroyed mid-flight.
     */
    private suspend fun processEvent(
        event: SnapshotDataEvent,
        koin: org.koin.core.Koin,
        notesRepository: JournalNotesRepository,
        journalRepository: JournalRepository,
    ) = withContext(NonCancellable) {
        val path = event.path
        if (koin.get<WatchRemovalEvents>().consume(event.sourceNodeId.orEmpty(), path, event.data)) {
            return@withContext
        }

        koin.get<WatchRemovalLock>().ingest {
            try {
                when {
                    koin.get<WindowDataEvents>().consume(WindowItem(event.sourceNodeId.orEmpty(), path, event.data)) -> Unit

                    NoteDataMapper.isDeletePath(
                        path,
                    ) -> if (koin.get<WindowDataEvents>().legacyNotesAllowed()) handleNoteDelete(path, notesRepository, koin.get())

                    NoteDataMapper.isNotePath(
                        path,
                    ) -> if (koin.get<WindowDataEvents>().legacyNotesAllowed()) handleNoteSync(path, event.data, notesRepository)

                    JournalDataMapper.isDeletePath(path) -> handleJournalDelete(path, journalRepository)

                    JournalDataMapper.isJournalPath(path) -> handleJournalSync(path, event.data, journalRepository)

                    AssociationDataMapper.isDeletePath(path) -> handleAssociationDelete(path, koin)

                    AssociationDataMapper.isAssociationPath(path) -> handleAssociationSync(path, event.data, koin)
                }
            } catch (e: Exception) {
                Napier.e("Unhandled error processing sync event at path: $path", e)
            }
        }
    }

    private fun handleRemoteCameraDevices(stringMap: Map<String, String>) {
        WearRemoteCameraDeviceStore.update(RemoteCameraDeviceDataMapper.fromDataMap(stringMap))
    }

    private fun handleRemoteCameraCaptureResult(stringMap: Map<String, String>) {
        WearRemoteCameraCaptureResultStore.publish(RemoteCameraCaptureResultDataMapper.fromDataMap(stringMap))
    }

    private suspend fun mirrorPhoneState(
        event: SnapshotDataEvent,
        localNodeId: String?,
    ) {
        val sourceNodeId = event.sourceNodeId
        if (sourceNodeId == null) {
            Napier.w("A state document arrived without the node that wrote it; ignoring it")
            return
        }
        val koin = resolveKoin()
        if (koin == null) {
            Napier.e("Koin unavailable while encrypted storage is not ready; the phone's state is read again when LogDate next starts")
            return
        }
        withContext(NonCancellable) {
            if (koin.get<WatchRemovalLock>().removed()) return@withContext
            koin.get<WatchRemovalLock>().ingest {
                koin.get<WatchStateMirror>().onPhoneDocument(sourceNodeId, localNodeId, StateDocumentCodec.fromDataMap(event.data))
            }
        }
    }

    private suspend fun handleNoteDelete(
        path: String,
        notesRepository: JournalNotesRepository,
        syncedAudioCache: SyncedAudioCache,
    ) {
        val noteId =
            try {
                NoteDataMapper.noteIdFromPath(path)
            } catch (e: IllegalArgumentException) {
                Napier.w("Invalid note ID in delete path: $path", e)
                return
            }

        Napier.d("Received delete signal for note: $noteId")
        try {
            val mediaRef = (notesRepository.getNoteById(noteId) as? JournalNote.Audio)?.mediaRef
            if (notesRepository is SyncableJournalNotesRepository) {
                notesRepository.deleteFromSync(noteId)
            } else {
                notesRepository.removeById(noteId)
            }
            if (notesRepository.getNoteById(noteId) == null) {
                deleteLocalAudio(mediaRef, androidMediaFileResolver(this))
                syncedAudioCache.delete(noteId)
            }
        } catch (e: Exception) {
            Napier.w("Failed to delete synced note: $noteId", e)
        }
    }

    private suspend fun handleNoteSync(
        path: String,
        stringMap: Map<String, String>,
        notesRepository: JournalNotesRepository,
    ) {
        try {
            val note = noteDataMapper.fromDataMap(stringMap)
            Napier.d("Received note from phone: ${note.uid} (${note.type})")
            if (notesRepository is SyncableJournalNotesRepository) {
                notesRepository.createFromSync(note)
            } else {
                notesRepository.create(note)
            }
        } catch (e: Exception) {
            Napier.w("Failed to process synced note from path: $path", e)
        }
    }

    private suspend fun handleJournalDelete(
        path: String,
        journalRepository: JournalRepository,
    ) {
        val journalId =
            try {
                JournalDataMapper.journalIdFromPath(path)
            } catch (e: IllegalArgumentException) {
                Napier.w("Invalid journal ID in delete path: $path", e)
                return
            }

        Napier.d("Received delete signal for journal: $journalId")
        try {
            if (journalRepository is SyncableJournalRepository) {
                journalRepository.deleteFromSync(journalId)
            } else {
                journalRepository.delete(journalId)
            }
        } catch (e: Exception) {
            Napier.w("Failed to delete synced journal: $journalId", e)
        }
    }

    private suspend fun handleJournalSync(
        path: String,
        stringMap: Map<String, String>,
        journalRepository: JournalRepository,
    ) {
        try {
            val journal = journalDataMapper.fromDataMap(stringMap)
            Napier.d("Received journal from phone: ${journal.id}")
            if (journalRepository is SyncableJournalRepository) {
                // createFromSync uses upsert — safe for duplicates and updates
                journalRepository.createFromSync(journal)
            } else {
                journalRepository.create(journal)
            }
        } catch (e: Exception) {
            Napier.w("Failed to process synced journal from path: $path", e)
        }
    }

    private suspend fun handleAssociationDelete(
        path: String,
        koin: org.koin.core.Koin,
    ) {
        val (journalId, contentId) =
            try {
                AssociationDataMapper.idsFromPath(path)
            } catch (e: Exception) {
                Napier.w("Invalid association path: $path", e)
                return
            }

        Napier.d("Received association delete: journal=$journalId, content=$contentId")
        try {
            val contentRepo = koin.getOrNull<SyncableJournalContentRepository>()
            if (contentRepo != null) {
                contentRepo.removeContentFromJournalFromSync(contentId, journalId)
            } else {
                val dao = koin.get<JournalContentDao>()
                dao.removeContentFromJournal(journalId, contentId)
            }
        } catch (e: Exception) {
            Napier.w("Failed to delete synced association: $path", e)
        }
    }

    private suspend fun handleAssociationSync(
        path: String,
        stringMap: Map<String, String>,
        koin: org.koin.core.Koin,
    ) {
        try {
            val (journalId, contentId) = associationDataMapper.fromDataMap(stringMap)
            Napier.d("Received association from phone: journal=$journalId, content=$contentId")
            val contentRepo = koin.getOrNull<SyncableJournalContentRepository>()
            if (contentRepo != null) {
                contentRepo.addContentToJournalFromSync(contentId, journalId)
            } else {
                val dao = koin.get<JournalContentDao>()
                dao.addContentToJournal(JournalContentEntityLink(journalId, contentId))
            }
        } catch (e: Exception) {
            Napier.w("Failed to process synced association from path: $path", e)
        }
    }

    /** Cold messages wait briefly; callers retry instead of executing an expired handoff later. */
    private suspend fun resolveKoin(): org.koin.core.Koin? = resolveWearServiceKoin()

    /**
     * The watch's own Data Layer node, whose writes come back to it as data events, or null when Play
     * services cannot say, in which case every change is acted on.
     */
    private suspend fun localNodeId(): String? {
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
            Napier.w("Could not read this watch's Data Layer node; acting on every data change", e)
            null
        }
    }

    private fun extractStringMap(event: DataEvent): Map<String, String> {
        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
        val stringMap = mutableMapOf<String, String>()
        for (key in dataMap.keySet()) {
            if (key.startsWith("_")) continue
            dataMap.getString(key)?.let { stringMap[key] = it }
        }
        return stringMap
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    /**
     * Snapshot of a data event's path, payload, type and writer.
     * Created before [DataEventBuffer] is recycled by the system.
     */
    private data class SnapshotDataEvent(
        val path: String,
        val data: Map<String, String>,
        val type: Int,
        val sourceNodeId: String?,
    )
}
