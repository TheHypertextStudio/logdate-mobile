package app.logdate.client.media.audio.download

/** Records an explicit download request until installation completes. All access is off the UI thread. */
interface ModelDownloadRequest {
    fun isRequested(): Boolean

    fun setRequested(requested: Boolean)
}

internal class InMemoryModelDownloadRequest : ModelDownloadRequest {
    private var requested = false

    override fun isRequested() = requested

    override fun setRequested(requested: Boolean) {
        this.requested = requested
    }
}
