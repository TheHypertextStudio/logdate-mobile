package app.logdate.client.sync.v2.media

import android.net.Uri
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.PhoneMediaDescriptor
import app.logdate.client.sync.datalayer.v2.PhoneMediaInfoRequest
import app.logdate.client.sync.datalayer.v2.PhoneMediaRangeRequest
import app.logdate.client.sync.datalayer.v2.PhoneMediaRangeTicket
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearMediaPaths
import com.google.android.gms.wearable.ChannelClient
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Thin listener boundary; every request is authorized again by the server. */
class PhoneMediaEndpoint(
    private val server: PhoneMediaServer,
    private val channels: ChannelClient,
) {
    suspend fun answer(
        node: String,
        path: String,
        payload: ByteArray,
    ): ByteArray? {
        if (payload.size > 8192) return null
        return when (path) {
            WearMediaPaths.INFO -> {
                val request = (WearJson.decode(payload, 1, PhoneMediaInfoRequest.serializer()) as? Decoded.Supported)?.value ?: return null
                if (request.schema != 1) return null
                server.describe(node, request)?.let { WearJson.encodeToBytes(PhoneMediaDescriptor.serializer(), it) }
            }

            WearMediaPaths.RANGE -> {
                val request = (WearJson.decode(payload, 1, PhoneMediaRangeRequest.serializer()) as? Decoded.Supported)?.value ?: return null
                if (request.schema != 1) return null
                server.prepare(node, request)?.let { WearJson.encodeToBytes(PhoneMediaRangeTicket.serializer(), it) }
            }

            else -> {
                null
            }
        }
    }

    suspend fun serve(channel: ChannelClient.Channel) {
        try {
            server.serve(channel.nodeId, channel.path) { file, offset, length, current ->
                GooglePhoneMediaSender(channels).send(channel, Uri.fromFile(file), offset, length, current)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Could not send phone media to the linked watch", e)
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(5_000) { channels.close(channel).await() }
                } catch (e: Exception) {
                    Napier.w("Could not close a phone media output channel", e)
                }
            }
        }
    }
}
