package app.logdate.client.media.audio.download

/**
 * Status of an on-device speech / audio-tagging model download.
 *
 * Both [app.logdate.client.media.audio.transcription.TranscriptionService.offlineModelDownloadStatus]
 * and [app.logdate.client.media.audio.tagging.AudioTaggingService.modelDownloadStatus] expose
 * this type so the UI can render consistent availability and download progress.
 */
sealed interface ModelDownloadStatus {
    /** Installed files have not yet been checked. */
    data object Checking : ModelDownloadStatus

    /** The platform supplies transcription without an app-managed download. */
    data object ManagedBySystem : ModelDownloadStatus

    /** Installed files were checked and the model is missing. */
    data object Idle : ModelDownloadStatus

    /**
     * HTTP body is streaming to disk. [bytesDownloaded] grows over time;
     * [totalBytes] is `null` when the server didn't send Content-Length, in
     * which case the UI should show an indeterminate progress indicator.
     */
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : ModelDownloadStatus {
        /** 0..1 progress fraction, or `null` when [totalBytes] is unknown. */
        val fraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { bytesDownloaded.toFloat() / it.toFloat() }
    }

    /**
     * Bytes have finished downloading; the archive is being unpacked into
     * its final location. Typically lasts a second or two.
     */
    data object Extracting : ModelDownloadStatus

    /** The model is on disk and ready to use. */
    data object Completed : ModelDownloadStatus

    /**
     * Unavailable or interrupted states; temporary failures are retried automatically. Marker sub-interface so call sites can check
     * `state is Failure` instead of comparing every case by hand. The UI maps
     * each concrete case to a localized string.
     */
    sealed interface Failure : ModelDownloadStatus

    /** Availability could not be determined; retry discovery rather than downloading. */
    data object CheckFailed : Failure

    /** No internet, DNS failure, or host unreachable. */
    data object NoNetwork : Failure

    /** The host returned a non-success status (gone, server error, etc.). */
    data object ServerUnavailable : Failure

    /** Not enough free storage to fit the extracted model. */
    data object OutOfStorage : Failure

    /** The downloaded archive failed to unpack — corrupt or unexpected layout. */
    data object ArchiveCorrupt : Failure

    /** The current package is invalid; wait for a corrected source revision. */
    data object AwaitingUpdate : Failure

    /** This implementation doesn't support a downloadable model at all. */
    data object NotSupported : Failure

    /** Anything else. The triggering exception is in the logs. */
    data object UnknownError : Failure
}
