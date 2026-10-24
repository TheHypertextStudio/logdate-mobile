package app.logdate.client.sync

import app.logdate.client.sync.cloud.ConditionalCreateResult
import app.logdate.client.sync.cloud.SyncUploadResult
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingUpload
import io.github.aakira.napier.Napier
import kotlin.time.Clock

/**
 * Settles a create-if-absent upload. Created: the server's version is recorded. Already there: the
 * create is done and the next download brings the server's copy, so the local version is left alone.
 * Deleted on another device: the record is removed here too instead of brought back. A failure is
 * counted and retried like any other upload.
 */
internal class ConditionalCreateSettler(
    private val retryCoordinator: SyncRetryCoordinator,
    private val recordProgress: (Int) -> Unit,
) {
    /** Returns 1 when the record is on the server afterwards, 0 otherwise. */
    suspend fun settle(
        entityType: EntityType,
        pending: PendingUpload,
        result: Result<ConditionalCreateResult>,
        errors: MutableList<SyncError>,
        onCreated: suspend (SyncUploadResult) -> Unit,
        onDeleted: suspend () -> Unit,
    ): Int {
        val outcome =
            result.getOrElse { error ->
                val movedToDeadLetter = retryCoordinator.handleRetryFailure(entityType, pending, error)
                errors.add(
                    SyncError(
                        SyncErrorType.SERVER_ERROR,
                        "Failed to create ${entityType.name.lowercase()} ${pending.entityId}: ${error.message}",
                        error,
                        retryable = !movedToDeadLetter,
                    ),
                )
                Napier.w(
                    if (movedToDeadLetter) {
                        "Failed to create a record if absent; it was set aside after repeated failures"
                    } else {
                        "Failed to create a record if absent; it stays queued and is retried"
                    },
                )
                return 0
            }
        recordProgress(1)
        return when (outcome) {
            is ConditionalCreateResult.Created -> {
                onCreated(SyncUploadResult(outcome.serverVersion, outcome.syncedAt))
                retryCoordinator.markUploadSettled(entityType, pending, outcome.syncedAt, outcome.serverVersion)
                Napier.d("Created a record")
                1
            }

            is ConditionalCreateResult.AlreadyExists -> {
                retryCoordinator.markUploadSettled(entityType, pending, Clock.System.now(), outcome.serverVersion)
                Napier.i("The record was already on the server; the next download brings its copy")
                1
            }

            is ConditionalCreateResult.Deleted -> {
                onDeleted()
                retryCoordinator.markUploadSettled(entityType, pending, outcome.deletedAt, 0L)
                Napier.w("The record was deleted on another device; it is removed here instead of uploaded")
                0
            }
        }
    }
}
