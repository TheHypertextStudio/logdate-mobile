package app.logdate.wear.sync.v2.link

import app.logdate.client.sync.cloud.DeviceSessionRevocationListener
import io.github.aakira.napier.Napier

/**
 * What the watch does when the server ends its own sign-in. A removal erases the watch. A sign-in that only
 * ran out keeps every entry on the watch and asks its phone, through the watch's state, to link it again.
 */
class WatchSessionEndHandler(
    private val links: WatchLinkStore,
    private val erase: suspend () -> Unit,
) : DeviceSessionRevocationListener {
    override suspend fun onDeviceSessionRevoked() = erase()

    override suspend fun onDeviceSessionEnded() {
        if (links.markSessionExpired()) {
            Napier.i("This watch's sign-in ran out; it keeps its entries and its phone links it again")
        }
    }
}
