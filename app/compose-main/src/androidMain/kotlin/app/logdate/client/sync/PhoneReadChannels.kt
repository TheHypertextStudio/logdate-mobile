package app.logdate.client.sync

import app.logdate.client.sync.datalayer.v2.WearMediaPaths
import app.logdate.client.sync.datalayer.v2.WindowBodyPaths
import app.logdate.client.sync.v2.media.PhoneMediaEndpoint
import app.logdate.client.sync.v2.window.PhoneWindowBodyEndpoint
import com.google.android.gms.wearable.ChannelClient

class PhoneReadChannels(
    private val media: suspend () -> PhoneMediaEndpoint?,
    private val bodies: suspend () -> PhoneWindowBodyEndpoint?,
) {
    fun recognizes(path: String): Boolean =
        path.startsWith(WearMediaPaths.CHANNEL_PREFIX) || path.startsWith(WindowBodyPaths.CHANNEL_PREFIX)

    suspend fun serve(channel: ChannelClient.Channel): Boolean =
        when {
            channel.path.startsWith(WindowBodyPaths.CHANNEL_PREFIX) -> {
                bodies()?.let {
                    it.serve(channel)
                    true
                } ?: false
            }

            channel.path.startsWith(WearMediaPaths.CHANNEL_PREFIX) -> {
                media()?.let {
                    it.serve(channel)
                    true
                } ?: false
            }

            else -> {
                false
            }
        }

    suspend fun answer(
        node: String,
        path: String,
        payload: ByteArray,
    ): ByteArray? =
        when (path) {
            WindowBodyPaths.RANGE -> bodies()?.answer(node, path, payload)
            WearMediaPaths.INFO, WearMediaPaths.RANGE -> media()?.answer(node, path, payload)
            else -> null
        }
}
