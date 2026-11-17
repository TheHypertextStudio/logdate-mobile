package app.logdate.feature.core.sync

import app.logdate.client.sync.BackupRequestState
import app.logdate.shared.model.EntitlementResponse
import app.logdate.shared.model.EntitlementStatusWire

/**
 * UI-only projection of the sync pipeline state. Composables in this package render against
 * [SyncPresentation] directly and never see business types — so the same surface works on
 * any platform, in previews, in screenshot tests, and from a viewmodel.
 *
 * The mapping from `SyncStatus` (in `client/sync`) plus auth state lives in a viewmodel
 * outside this module, since `client/ui` deliberately doesn't depend on the sync engine.
 */
sealed class SyncPresentation {
    /**
     * Render nothing. Covers: no account, healthy idle, syncing-with-zero-pending in some
     * configurations. The chip and banner both honor this by not composing.
     */
    data object Hidden : SyncPresentation()

    /**
     * A sync run is in flight. [progressPercent] is how far through the run it is, in
     * [PROGRESS_STEP_PERCENT] steps, or null before the run's size is known.
     *
     * Stepped rather than exact so a long backup changes this value a few dozen times instead of
     * once per uploaded item: every change recomposes the home screen around the status button.
     */
    data class Syncing(
        val progressPercent: Int? = null,
    ) : SyncPresentation() {
        companion object {
            const val PROGRESS_STEP_PERCENT = 5

            /** [progressPercent] for [completed] of [total], or null when [total] is unknown or zero. */
            fun progressPercent(
                completed: Int,
                total: Int?,
            ): Int? {
                if (total == null || total <= 0) return null
                val percent = (completed.coerceIn(0, total) * 100) / total
                return percent - percent % PROGRESS_STEP_PERCENT
            }
        }
    }

    /**
     * Items waiting to upload but no run currently in flight (e.g. offline, backoff, debounce).
     * Shown as a count badge on the backup status button.
     */
    data class Pending(
        val pendingCount: Int,
        val requestState: BackupRequestState = BackupRequestState.NONE,
    ) : SyncPresentation()

    /** Downloaded journal items still need local recovery, such as restoring their media files. */
    data class Recovering(
        val pendingCount: Int,
    ) : SyncPresentation()

    /** The local queue could not be read, so a healthy count cannot be claimed. */
    data object StatusUnavailable : SyncPresentation()

    /**
     * Auth lapsed. The most user-visible failure mode — the banner asks for re-sign-in.
     */
    data object AuthError : SyncPresentation()

    /**
     * The server refused to store more. Banner with an action that leads to the plan. [cause] says
     * which limit refused it, once that is known; until then it reads as a full plan.
     */
    data class StorageError(
        val pendingCount: Int,
        val cause: StorageRefusal = StorageRefusal.Full(),
    ) : SyncPresentation()

    /** A specific attachment cannot upload until the person replaces it with a smaller file. */
    data object MediaTooLarge : SyncPresentation()

    /** The server stores the account in a way this version can't sync, so nothing syncs until the app is updated. */
    data object AccountStorageUnsupported : SyncPresentation()

    /** Edit conflicts present. Banner with a "Review conflicts" action. */
    data class ConflictError(
        val conflictCount: Int,
    ) : SyncPresentation()

    /**
     * A network error. The runtime retries automatically, so the error itself is a chip. Once
     * entries are waiting to back up, a banner also offers the list of them, since that is the
     * one thing the user can act on.
     */
    data class NetworkError(
        val pendingCount: Int,
    ) : SyncPresentation()

    /**
     * This device has no identity key, but the account already has data in the cloud. Backing up
     * is paused rather than risking a new key that could never read what is already there -- the
     * user needs to enter their recovery phrase again before anything else can happen.
     */
    data object NeedsRecovery : SyncPresentation()
}

/** Why the server will not store more for this account. */
sealed interface StorageRefusal {
    /** The plan's storage is used up. [includedBytes] is how much the plan holds, when known. */
    data class Full(
        val includedBytes: Long? = null,
    ) : StorageRefusal

    /** The account has no plan, so nothing can be stored until it picks one. */
    data object NoPlan : StorageRefusal

    /** The plan sells more storage, but a payment is failing, so it cannot grow. */
    data object PaymentDue : StorageRefusal

    companion object {
        /** What an account's plan says about why it cannot store more. */
        fun of(entitlement: EntitlementResponse): StorageRefusal =
            when {
                entitlement.planId == EntitlementResponse.NO_PLAN_ID -> NoPlan
                entitlement.extraStorageStepBytes != null && entitlement.status == EntitlementStatusWire.PAST_DUE -> PaymentDue
                else -> Full(includedBytes = entitlement.storageBytesLimit)
            }
    }
}

/** What the user can do with a [SyncPresentation] surface. */
sealed class SyncAction {
    data object UseMobileData : SyncAction()

    data object SignIn : SyncAction()

    data object ManageStorage : SyncAction()

    data object ReviewConflicts : SyncAction()

    /** Open the list of entries that are stuck, separate from [ReviewConflicts]'s edit conflicts. */
    data object ReviewIssues : SyncAction()

    /** Show what is waiting to back up, and why. */
    data object OpenStatus : SyncAction()

    /** Resolve [SyncPresentation.NeedsRecovery] by entering the recovery phrase again. */
    data object EnterRecoveryPhrase : SyncAction()

    /** Resolve [SyncPresentation.AccountStorageUnsupported] by updating the app, where the app can update itself. */
    data object UpdateApp : SyncAction()
}
