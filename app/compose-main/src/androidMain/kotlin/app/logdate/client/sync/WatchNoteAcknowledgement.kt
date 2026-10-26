package app.logdate.client.sync

import android.content.Context
import app.logdate.client.sync.datalayer.WearAudioRequestPaths
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import io.github.aakira.napier.Napier
import kotlinx.coroutines.tasks.await
import kotlin.uuid.Uuid

/** Delivers a payload-free message to the paired watch. Returns false when nothing was sent. */
fun interface WatchMessageSender {
    suspend fun send(path: String): Boolean
}

class MessageWatchNoteAcknowledger(
    private val sender: WatchMessageSender,
) : WatchNoteAcknowledger {
    override suspend fun acknowledge(noteId: Uuid) = acknowledge(noteId, null)

    override suspend fun acknowledge(
        noteId: Uuid,
        transferId: String?,
    ) {
        val path =
            if (transferId == null) {
                WearAudioRequestPaths.noteAckPath(noteId)
            } else {
                WearAudioRequestPaths.noteAckPath(noteId, transferId)
            }
        if (!sender.send(path)) {
            Napier.w("Watch did not receive the acknowledgement for note $noteId; it will retry")
        }
    }
}

/**
 * Messages every reachable node that has the LogDate watch app, or every connected node when the
 * capability lookup finds none.
 */
class GoogleWatchMessageSender(
    private val capabilityClient: CapabilityClient,
    private val nodeClient: NodeClient,
    private val messageClient: MessageClient,
) : WatchMessageSender {
    constructor(context: Context) : this(
        capabilityClient = Wearable.getCapabilityClient(context),
        nodeClient = Wearable.getNodeClient(context),
        messageClient = Wearable.getMessageClient(context),
    )

    override suspend fun send(path: String): Boolean =
        runCatching {
            val nodeIds = watchNodeIds()
            nodeIds.forEach { nodeId -> messageClient.sendMessage(nodeId, path, byteArrayOf()).await() }
            nodeIds.isNotEmpty()
        }.getOrElse { error ->
            Napier.w("Failed to message watch at path: $path", error)
            false
        }

    private suspend fun watchNodeIds(): List<String> {
        val capabilityNodes =
            capabilityClient
                .getCapability(WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        if (capabilityNodes.isNotEmpty()) return capabilityNodes.map { it.id }
        return nodeClient.connectedNodes.await().map { it.id }
    }

    private companion object {
        const val WATCH_CAPABILITY = "logdate_watch_app"
    }
}
