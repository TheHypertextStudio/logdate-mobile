package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncStatus

internal data class BackupOverviewState(
    val lastBackupAt: Long?,
    val pendingChanges: Int,
    val canBackUpNow: Boolean,
    val needsReview: Boolean,
) {
    val hasDetails: Boolean get() = pendingChanges > 0 || needsReview
}

internal fun backupOverviewState(
    sync: SyncStatus?,
    archive: CloudArchiveStatus,
    reviewableIssueCount: Int = 0,
): BackupOverviewState {
    val needsReview = reviewableIssueCount > 0 || (sync?.conflictCount ?: 0) > 0 || (sync?.earlierConflictCount ?: 0) > 0
    val blocked =
        sync?.lastError?.type in
            setOf(
                SyncErrorType.AUTHENTICATION_ERROR,
                SyncErrorType.KEY_RECOVERY_ERROR,
                SyncErrorType.STORAGE_ERROR,
                SyncErrorType.CONFLICT_ERROR,
                SyncErrorType.ACCOUNT_STORAGE_ERROR,
            )
    return BackupOverviewState(
        lastBackupAt = archive.lastCompletedAt,
        pendingChanges = (sync?.pendingUploads ?: 0).coerceAtLeast(0),
        canBackUpNow =
            archive.canRetry &&
                sync != null &&
                sync.queueReadable &&
                sync.unreadableCloudCount == 0 &&
                sync.pausedReason == null &&
                !sync.isSyncing &&
                sync.conflictCount == 0 &&
                !blocked &&
                reviewableIssueCount == 0,
        needsReview = needsReview,
    )
}
