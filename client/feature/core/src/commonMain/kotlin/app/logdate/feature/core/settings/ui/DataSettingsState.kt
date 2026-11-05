package app.logdate.feature.core.settings.ui

import app.logdate.client.data.maintenance.IntegrityRepairResult
import app.logdate.client.data.maintenance.IntegrityReport
import app.logdate.client.sync.SyncStatus
import app.logdate.shared.model.CloudStorageQuota

sealed interface AccountAvailability {
    val isAuthenticated: Boolean?

    data object Loading : AccountAvailability {
        override val isAuthenticated: Boolean? = null
    }

    data class Available(
        override val isAuthenticated: Boolean,
    ) : AccountAvailability

    data class Unavailable(
        override val isAuthenticated: Boolean? = null,
    ) : AccountAvailability
}

sealed interface QuotaAvailability {
    val quota: CloudStorageQuota?

    data class Loading(
        override val quota: CloudStorageQuota? = null,
    ) : QuotaAvailability

    data class Available(
        override val quota: CloudStorageQuota,
    ) : QuotaAvailability

    data class Unavailable(
        override val quota: CloudStorageQuota? = null,
    ) : QuotaAvailability
}

sealed interface SyncRequestState {
    data object Idle : SyncRequestState

    data object Requesting : SyncRequestState

    data object Accepted : SyncRequestState

    data object Failed : SyncRequestState
}

sealed interface ArchiveRequestState {
    data object Idle : ArchiveRequestState

    data object Requesting : ArchiveRequestState

    data object Accepted : ArchiveRequestState

    data object Failed : ArchiveRequestState
}

sealed interface IntegrityOperation {
    data object Idle : IntegrityOperation

    data object Checking : IntegrityOperation

    data object Repairing : IntegrityOperation

    data class Failed(
        val reason: IntegrityFailure,
    ) : IntegrityOperation
}

enum class IntegrityFailure { CHECK, REPAIR, REFRESH_AFTER_REPAIR }

data class IntegrityState(
    val operation: IntegrityOperation = IntegrityOperation.Idle,
    val lastReport: IntegrityReport? = null,
    val lastRepair: IntegrityRepairResult? = null,
) {
    val isChecking: Boolean get() = operation == IntegrityOperation.Checking
    val isRepairing: Boolean get() = operation == IntegrityOperation.Repairing
    val isRunning: Boolean get() = isChecking || isRepairing
}

sealed interface SyncFeedback {
    data object NeedsAccount : SyncFeedback

    data object Started : SyncFeedback

    data object Failed : SyncFeedback
}

data class SyncFeedbackDelivery(
    val id: Long,
    val feedback: SyncFeedback,
)

data class DataSettingsState(
    val quotaAvailability: QuotaAvailability = QuotaAvailability.Loading(),
    val isQuotaAvailable: Boolean = false,
    val integrityState: IntegrityState = IntegrityState(),
    val syncStatus: SyncStatus? = null,
    val cloudArchiveStatus: CloudArchiveStatus = CloudArchiveStatus(CloudArchivePhase.CHECKING),
    val accountAvailability: AccountAvailability = AccountAvailability.Loading,
    val syncRequest: SyncRequestState = SyncRequestState.Idle,
    val archiveRequest: ArchiveRequestState = ArchiveRequestState.Idle,
) {
    /** Null means the stored account has not been read; it must never imply signed out. */
    val isAuthenticated: Boolean? get() = accountAvailability.isAuthenticated
    val accountLoadFailed: Boolean get() = accountAvailability is AccountAvailability.Unavailable
    val quotaState: CloudStorageQuota? get() = quotaAvailability.quota
    val hasAuthoritativeQuota: Boolean get() = quotaState != null
}
