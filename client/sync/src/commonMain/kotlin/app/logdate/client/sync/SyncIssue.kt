package app.logdate.client.sync

/** A known constraint is different from an attempt that failed. */
sealed interface SyncIssue {
    sealed interface Waiting : SyncIssue {
        data object Network : Waiting

        data object WiFi : Waiting

        data object BackgroundData : Waiting

        data object ScheduledWork : Waiting

        data object RetryBackoff : Waiting
    }

    sealed interface Failure : SyncIssue {
        data object SignIn : Failure

        data object JournalAccess : Failure

        data object CloudStorage : Failure

        data object Conflict : Failure

        data object LocalData : Failure

        data object Connection : Failure

        data object CloudService : Failure

        data object Unknown : Failure
    }
}

fun SyncStatus.issue(): SyncIssue? {
    if (isSyncing || requestState == BackupRequestState.RUNNING) return null
    when (pausedReason) {
        SyncPausedReason.NOT_SIGNED_IN -> {
            return SyncIssue.Failure.SignIn
        }

        SyncPausedReason.OFFLINE -> {
            return SyncIssue.Waiting.Network
        }

        SyncPausedReason.BACKGROUND_DATA_OFF -> {
            return SyncIssue.Waiting.BackgroundData
        }

        SyncPausedReason.MEDIA_WAITING_FOR_WIFI -> {
            return if (backgroundWorkLimited) SyncIssue.Waiting.BackgroundData else SyncIssue.Waiting.WiFi
        }

        SyncPausedReason.NEEDS_RECOVERY_PHRASE -> {
            return SyncIssue.Failure.JournalAccess
        }

        SyncPausedReason.LOCAL_STORAGE_UNAVAILABLE -> {
            return SyncIssue.Failure.LocalData
        }

        null -> {
            Unit
        }
    }
    if (!queueReadable) return SyncIssue.Failure.LocalData
    if (requestState == BackupRequestState.RETRYING) return SyncIssue.Waiting.RetryBackoff
    when (lastError?.type) {
        SyncErrorType.AUTHENTICATION_ERROR -> return SyncIssue.Failure.SignIn
        SyncErrorType.STORAGE_ERROR -> return SyncIssue.Failure.CloudStorage
        SyncErrorType.CONFLICT_ERROR -> return SyncIssue.Failure.Conflict
        SyncErrorType.NETWORK_ERROR -> return SyncIssue.Failure.Connection
        SyncErrorType.SERVER_ERROR -> return SyncIssue.Failure.CloudService
        SyncErrorType.UNKNOWN_ERROR, null -> Unit
    }
    if (unreadableCloudCount > 0) return SyncIssue.Failure.JournalAccess
    if (conflictCount > 0) return SyncIssue.Failure.Conflict
    if (hasErrors || lastError != null || requestState == BackupRequestState.FAILED) return SyncIssue.Failure.Unknown
    if (backgroundWorkLimited && (pendingUploads > 0 || pendingDownloads > 0)) return SyncIssue.Waiting.BackgroundData
    return SyncIssue.Waiting.ScheduledWork.takeIf { requestState == BackupRequestState.QUEUED }
}
