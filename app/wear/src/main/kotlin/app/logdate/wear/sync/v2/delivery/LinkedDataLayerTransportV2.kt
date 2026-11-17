package app.logdate.wear.sync.v2.delivery

import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.PhoneReach
import app.logdate.wear.sync.WearDataLayerClient
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A run's captured audience: requests and recordings never fall back to a different phone. */
class LinkedDataLayerTransportV2(
    private val peer: PeerDeliveryContext,
    private val peers: WatchDeliveryPeer,
    private val dataLayer: WearDataLayerClient,
    private val capabilities: CapabilityClient,
    private val channels: ChannelClient,
    private val messages: MessageClient,
    private val timeoutMs: Long = 10_000L,
) : DataLayerTransportV2 {
    override suspend fun reach(): PhoneReach {
        val phone = selectedPhone() ?: return PhoneReach.UNREACHABLE
        return if (phone.isNearby) PhoneReach.NEARBY else PhoneReach.RELAY_ONLY
    }

    override suspend fun put(
        path: String,
        data: Map<String, String>,
    ): Boolean =
        attempt("send a capture") {
            val envelope = (EnvelopeDataMap.fromDataMap(data) as? Decoded.Supported)?.value ?: return@attempt false
            if (envelope.context != peer || !peers.isCurrent(peer)) return@attempt false
            dataLayer.putDataItem(path, data, urgent = true) && peers.isCurrent(peer)
        }

    override suspend fun deleteOwn(path: String): Boolean =
        attempt("remove a delivered data item") {
            if (!peers.isCurrent(peer)) return@attempt false
            dataLayer.deleteOwnDataItem(path) && peers.isCurrent(peer)
        }

    override suspend fun sendFile(
        channelPath: String,
        localFilePath: String,
    ): Boolean =
        attempt("send a recording") {
            val record = WearPaths.parseRecordMedia(channelPath) ?: return@attempt false
            if (record.kind != RecordKind.NOTE || record.linkId != peer.linkId) return@attempt false
            val phone = selectedPhone() ?: return@attempt false
            val channel = channels.openChannel(phone.id, channelPath).await()
            var closed: Boolean
            val sent =
                try {
                    if (peers.isCurrent(peer)) transfer(channel, localFilePath) else false
                } finally {
                    closed = close(channel)
                }
            sent && closed && peers.isCurrent(peer)
        }

    override suspend fun request(
        path: String,
        payload: ByteArray,
    ): Result<ByteArray> {
        val phone = selectedPhone() ?: return Result.failure(IllegalStateException("The linked phone is not in reach"))
        return try {
            val answer = withTimeoutOrNull(timeoutMs) { messages.sendRequest(phone.id, path, payload).await() }
            if (answer == null || !peers.isCurrent(peer)) {
                Result.failure(IllegalStateException("The linked phone did not answer in the current exchange"))
            } else {
                Result.success(answer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun selectedPhone(): Node? =
        try {
            if (!peer.isValid() || !peers.isCurrent(peer)) return null
            val nodes = capabilities.getCapability(WearProtocol.PHONE_CAPABILITY_V2, CapabilityClient.FILTER_REACHABLE).await().nodes
            if (peers.isCurrent(peer)) nodes.firstOrNull { it.id == peer.phoneNode } else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not find the linked phone; captures wait", e)
            null
        }

    private suspend fun transfer(
        channel: ChannelClient.Channel,
        localFilePath: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            channels.getOutputStream(channel).await().use { output ->
                File(localFilePath).inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var count = input.read(buffer)
                    while (count != -1) {
                        currentCoroutineContext().ensureActive()
                        if (!peers.isCurrent(peer)) return@withContext false
                        output.write(buffer, 0, count)
                        count = input.read(buffer)
                    }
                    peers.isCurrent(peer)
                }
            }
        }

    private suspend fun close(channel: ChannelClient.Channel): Boolean =
        withContext(NonCancellable) {
            try {
                channels.close(channel).await()
                true
            } catch (e: Exception) {
                Napier.w("Could not close a watch capture channel", e)
                false
            }
        }

    private suspend fun attempt(
        action: String,
        block: suspend () -> Boolean,
    ): Boolean =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not $action for the linked phone", e)
            false
        }
}
