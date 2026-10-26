package app.logdate.feature.core.settings.ui

import app.logdate.client.datastore.UserSession
import app.logdate.client.sync.cloud.BackupMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class CloudArchivePhase {
    CHECKING,
    SIGNED_OUT,
    NEEDS_RECOVERY,
    NEVER_BACKED_UP,
    QUEUED,
    RUNNING,
    RETRYING,
    FAILED,
    COMPLETE,
    UNAVAILABLE,
}

data class CloudArchiveStatus(
    val phase: CloudArchivePhase,
    /** Server-confirmed completion time in epoch milliseconds. */
    val lastCompletedAt: Long? = null,
    val progress: CloudArchiveProgress? = null,
) {
    /** Whether the status surface should offer recovery for a failed or unconfirmed archive. */
    val needsManualRetry: Boolean get() = phase == CloudArchivePhase.FAILED || phase == CloudArchivePhase.UNAVAILABLE

    val canRetry: Boolean
        get() =
            phase == CloudArchivePhase.NEVER_BACKED_UP ||
                phase == CloudArchivePhase.FAILED ||
                phase == CloudArchivePhase.RETRYING ||
                phase == CloudArchivePhase.COMPLETE ||
                phase == CloudArchivePhase.UNAVAILABLE
}

enum class CloudArchiveStage { CHECKING, EXPORTING, ENCRYPTING, UPLOADING }

data class CloudArchiveProgress(
    val stage: CloudArchiveStage,
    val fraction: Float? = null,
)

enum class ArchiveWorkState { IDLE, QUEUED, RUNNING, RETRYING, FAILED, SUCCEEDED }

fun latestEncryptedArchiveAt(
    backups: List<BackupMetadata>,
    encryptedManifest: String,
): Long? =
    backups
        .asSequence()
        .filter { it.manifest == encryptedManifest }
        .maxOfOrNull { it.createdAt }

fun scopedCloudArchiveStatus(
    currentAccountId: String?,
    observedAccountId: String?,
    observedStatus: CloudArchiveStatus,
): CloudArchiveStatus =
    when {
        currentAccountId == null -> CloudArchiveStatus(CloudArchivePhase.SIGNED_OUT)
        currentAccountId != observedAccountId -> CloudArchiveStatus(CloudArchivePhase.CHECKING)
        else -> observedStatus
    }

/** A completed worker is not proof that an archive exists on the server. */
fun resolveCloudArchiveStatus(
    identityKeyAvailable: Boolean,
    workState: ArchiveWorkState,
    serverCompletedAt: Long?,
    serverLookupFailed: Boolean,
    progress: CloudArchiveProgress? = null,
): CloudArchiveStatus {
    if (!identityKeyAvailable) return CloudArchiveStatus(CloudArchivePhase.NEEDS_RECOVERY)
    val confirmedAt = if (serverLookupFailed) null else serverCompletedAt
    val phase =
        when (workState) {
            ArchiveWorkState.RUNNING -> {
                CloudArchivePhase.RUNNING
            }

            ArchiveWorkState.RETRYING -> {
                CloudArchivePhase.RETRYING
            }

            ArchiveWorkState.QUEUED -> {
                CloudArchivePhase.QUEUED
            }

            ArchiveWorkState.FAILED -> {
                CloudArchivePhase.FAILED
            }

            ArchiveWorkState.IDLE, ArchiveWorkState.SUCCEEDED -> {
                when {
                    serverLookupFailed -> CloudArchivePhase.UNAVAILABLE
                    confirmedAt != null -> CloudArchivePhase.COMPLETE
                    else -> CloudArchivePhase.NEVER_BACKED_UP
                }
            }
        }
    return CloudArchiveStatus(phase, confirmedAt, progress.takeIf { phase == CloudArchivePhase.RUNNING })
}

interface CloudArchiveStatusSource {
    fun observe(session: UserSession): Flow<CloudArchiveStatus>

    suspend fun requestBackup()
}

/** Non-Android targets have no Android archive worker. */
object UnavailableCloudArchiveStatusSource : CloudArchiveStatusSource {
    override fun observe(session: UserSession): Flow<CloudArchiveStatus> = flowOf(CloudArchiveStatus(CloudArchivePhase.UNAVAILABLE))

    override suspend fun requestBackup() = Unit
}
