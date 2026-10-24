package app.logdate.feature.core.sync

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.sync.BackupRequestState
import app.logdate.client.sync.SyncErrorType
import app.logdate.client.sync.SyncManager
import app.logdate.client.sync.SyncPausedReason
import app.logdate.client.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Maps the sync engine's runtime [SyncStatus] plus auth state into a UI-only
 * [SyncPresentation]. Lives in `client.feature.core` because that's the smallest module that
 * depends on both `client.sync` (for [SyncManager]) and `client.ui` (for [SyncPresentation]).
 *
 * Behavior summary:
 * - No session ⇒ always [SyncPresentation.Hidden]. The chip and banner never render without
 *   an account, regardless of any orphan queue state. This is the load-bearing fix for the
 *   "264 items waiting to sync" surface that shouldn't have existed.
 * - In-flight sync wins over pending/error display so the user sees forward motion.
 * - Errors map by type. Network errors are demoted to a chip (quiet, retried automatically);
 *   auth/storage/conflict errors promote to a banner (the user must act).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun observeSyncPresentation(
    syncManager: SyncManager,
    sessionStorage: SessionStorage,
): Flow<SyncPresentation> =
    combine(
        syncManager.syncStatusFlow,
        sessionStorage.getSessionFlow(),
    ) { status, session ->
        if (session == null) {
            SyncPresentation.Hidden
        } else {
            status.toPresentation()
        }
    }

internal fun SyncStatus.toPresentation(): SyncPresentation {
    if (isSyncing || requestState == BackupRequestState.RUNNING) {
        if (pendingDownloads > 0) {
            return SyncPresentation.Recovering(pendingCount = pendingDownloads)
        }
        return SyncPresentation.Syncing(
            progressPercent = SyncPresentation.Syncing.progressPercent(completedInRun, totalForRun),
        )
    }

    // A pause means no attempt is even being made, so it takes priority over a stale error from
    // a previous, different failure -- entering the recovery phrase is the one thing that moves
    // this forward, and a leftover network-error chip underneath it would say otherwise.
    if (pausedReason == SyncPausedReason.NEEDS_RECOVERY_PHRASE) {
        return SyncPresentation.NeedsRecovery
    }

    if (pausedReason == SyncPausedReason.LOCAL_STORAGE_UNAVAILABLE) {
        return SyncPresentation.StatusUnavailable
    }

    val error = lastError
    if (error != null) {
        return when (error.type) {
            SyncErrorType.AUTHENTICATION_ERROR -> SyncPresentation.AuthError

            SyncErrorType.KEY_RECOVERY_ERROR -> SyncPresentation.NeedsRecovery

            SyncErrorType.MEDIA_TOO_LARGE_ERROR -> SyncPresentation.MediaTooLarge

            SyncErrorType.ACCOUNT_STORAGE_ERROR -> SyncPresentation.AccountStorageUnsupported

            SyncErrorType.STORAGE_ERROR -> SyncPresentation.StorageError(pendingCount = pendingUploads)

            SyncErrorType.CONFLICT_ERROR -> SyncPresentation.ConflictError(conflictCount = conflictCount.coerceAtLeast(1))

            SyncErrorType.NETWORK_ERROR -> SyncPresentation.NetworkError(pendingCount = pendingUploads)

            SyncErrorType.SERVER_ERROR -> SyncPresentation.NetworkError(pendingCount = pendingUploads)

            // An unclassified failure used to collapse into Pending/Hidden -- indistinguishable
            // from a perfectly healthy state, so something going genuinely wrong produced no
            // signal at all. Treat it like a transient network error: a quiet, auto-retried chip,
            // not a new alarming surface, but no longer invisible.
            SyncErrorType.UNKNOWN_ERROR -> SyncPresentation.NetworkError(pendingCount = pendingUploads)
        }
    }

    if (!queueReadable) return SyncPresentation.StatusUnavailable

    if (requestState == BackupRequestState.FAILED) {
        return SyncPresentation.NetworkError(pendingCount = pendingUploads)
    }

    if (pendingDownloads > 0) {
        return SyncPresentation.Recovering(pendingCount = pendingDownloads)
    }

    return if (pendingUploads > 0 || requestState == BackupRequestState.QUEUED || requestState == BackupRequestState.RETRYING) {
        SyncPresentation.Pending(pendingCount = pendingUploads, requestState = requestState)
    } else {
        SyncPresentation.Hidden
    }
}
