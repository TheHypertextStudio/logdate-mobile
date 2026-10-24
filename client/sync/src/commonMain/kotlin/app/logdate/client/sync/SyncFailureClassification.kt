package app.logdate.client.sync

import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.MediaTooLargeException
import app.logdate.client.sync.metadata.SyncDeadLetterReason

internal fun classifySyncFailure(error: Throwable): SyncDeadLetterReason =
    when (error) {
        is MissingMediaException -> {
            SyncDeadLetterReason.MISSING_FILE
        }

        is InterruptedUploadException -> {
            SyncDeadLetterReason.APP_CLOSED
        }

        is MediaTooLargeException -> {
            SyncDeadLetterReason.FILE_TOO_LARGE
        }

        is CloudApiException -> {
            when {
                error.statusCode == 401 -> SyncDeadLetterReason.SIGN_IN_REQUIRED
                error.statusCode in setOf(502, 503, 504) -> SyncDeadLetterReason.SERVER_UNAVAILABLE
                error.errorCode == "NETWORK_ERROR" -> SyncDeadLetterReason.NETWORK_UNAVAILABLE
                else -> SyncDeadLetterReason.UNKNOWN
            }
        }

        else -> {
            SyncDeadLetterReason.UNKNOWN
        }
    }

/** Earlier upload attempts at an entry never finished, because the app closed during each one. */
class InterruptedUploadException(
    unfinishedAttempts: Int,
) : Exception("LogDate closed while uploading this entry, $unfinishedAttempts times in a row")
