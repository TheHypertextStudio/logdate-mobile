package app.logdate.feature.core.settings.ui

import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.metadata.SyncDeadLetterReason
import app.logdate.feature.core.sync.AccountSyncStatus
import app.logdate.feature.core.sync.accountSyncStatus

/** One user-facing status for incremental sync and the encrypted backup worker. */
internal fun backupStatus(
    status: SyncStatus?,
    archive: CloudArchiveStatus?,
    reasons: Set<SyncDeadLetterReason> = emptySet(),
): AccountSyncStatus {
    if (archive?.phase == CloudArchivePhase.NEEDS_RECOVERY) return AccountSyncStatus.KEY_RECOVERY_REQUIRED
    val entries = status?.let { accountSyncStatus(it, reasons) } ?: AccountSyncStatus.CHECKING
    if (entries != AccountSyncStatus.UP_TO_DATE && entries != AccountSyncStatus.WAITING && entries != AccountSyncStatus.CHECKING) {
        return entries
    }
    if (archive == null || !archive.supportsBackup) return entries
    return when (archive.phase) {
        CloudArchivePhase.RUNNING -> {
            AccountSyncStatus.SYNCING
        }

        CloudArchivePhase.SIGNED_OUT -> {
            AccountSyncStatus.SIGN_IN_REQUIRED
        }

        CloudArchivePhase.NEEDS_RECOVERY -> {
            AccountSyncStatus.KEY_RECOVERY_REQUIRED
        }

        CloudArchivePhase.RETRYING -> {
            AccountSyncStatus.RETRYING
        }

        CloudArchivePhase.FAILED, CloudArchivePhase.UNAVAILABLE -> {
            AccountSyncStatus.UNKNOWN
        }

        CloudArchivePhase.CHECKING -> {
            if (entries == AccountSyncStatus.UP_TO_DATE) AccountSyncStatus.CHECKING else entries
        }

        CloudArchivePhase.NEVER_BACKED_UP -> {
            AccountSyncStatus.CHECKING
        }

        CloudArchivePhase.QUEUED -> {
            if (status?.backgroundWorkLimited ==
                true
            ) {
                AccountSyncStatus.BACKGROUND_RESTRICTED
            } else {
                AccountSyncStatus.WAITING
            }
        }

        CloudArchivePhase.COMPLETE -> {
            if (archive.lastCompletedAt == null) AccountSyncStatus.CHECKING else entries
        }
    }
}
