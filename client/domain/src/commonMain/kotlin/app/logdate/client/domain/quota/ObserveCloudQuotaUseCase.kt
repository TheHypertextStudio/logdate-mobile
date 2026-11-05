package app.logdate.client.domain.quota

import app.logdate.shared.model.CloudQuotaManager
import app.logdate.shared.model.CloudQuotaSnapshot
import app.logdate.shared.model.CloudStorageQuota
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart

/**
 * Use case for observing cloud storage quota information.
 */
class ObserveCloudQuotaUseCase(
    private val quotaManager: CloudQuotaManager,
) {
    /** Account-scoped observations own their refresh and never promote a local fallback. */
    fun observeSnapshots(): Flow<CloudQuotaSnapshot> = quotaManager.observeQuotaSnapshots()

    /**
     * Observes quota changes in real time.
     *
     * Starting collection requests the current quota first so screens do not wait for some other
     * component to manually warm the quota cache before receiving server-backed usage.
     */
    operator fun invoke(): Flow<CloudStorageQuota> =
        quotaManager
            .observeQuota()
            .onStart { quotaManager.getCurrentQuota() }
}
