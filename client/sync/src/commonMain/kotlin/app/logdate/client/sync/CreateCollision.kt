package app.logdate.client.sync

import app.logdate.client.sync.cloud.CloudApiException
import app.logdate.client.sync.cloud.ConditionalCreateResult
import app.logdate.client.sync.cloud.ContentState
import app.logdate.client.sync.cloud.SyncUploadResult
import app.logdate.client.sync.metadata.EntityType
import app.logdate.client.sync.metadata.PendingOperation
import app.logdate.client.sync.metadata.PendingUpload
import app.logdate.client.sync.metadata.SyncMetadataService
import io.github.aakira.napier.Napier
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** What this device knows about a record the server already had when this device created it. */
sealed interface CreateCollision {
    /** Nothing says another device made it, so the refusal stays an upload failure. */
    data object Unexpected : CreateCollision

    /** Another device of this account made it; [contentVersion] is the version this device received from it. */
    data class CreatedElsewhere(
        val contentVersion: Long,
    ) : CreateCollision
}

/** Tells the upload pass which records another device of this account, such as a linked watch, may have created first. */
fun interface CreateCollisionPolicy {
    suspend fun collisionFor(
        entityType: EntityType,
        entityId: String,
    ): CreateCollision

    companion object {
        val None = CreateCollisionPolicy { _, _ -> CreateCollision.Unexpected }
    }
}

/** What to do with a create the server refused because the record already exists. */
sealed interface CollisionOutcome {
    /** The server's version is the one this device has: the create is done, and the next download brings the server's copy. */
    data class Settle(
        val serverVersion: Long,
    ) : CollisionOutcome

    /** This device changed the record after the other device's copy: the change goes up as an edit of [serverVersion]. */
    data class RebaseAsUpdate(
        val serverVersion: Long,
    ) : CollisionOutcome

    data class Deleted(
        val deletedAt: Instant,
    ) : CollisionOutcome

    /** An ordinary failure: retried, and set aside after repeated failures. */
    data object Retry : CollisionOutcome
}

object CreateCollisionResolver {
    fun resolve(
        collision: CreateCollision,
        localContentVersion: Long,
        server: ContentState,
    ): CollisionOutcome {
        if (collision !is CreateCollision.CreatedElsewhere) return CollisionOutcome.Retry
        if (server is ContentState.Deleted) return CollisionOutcome.Deleted(server.deletedAt)
        if (server !is ContentState.Present || server.serverVersion <= 0) return CollisionOutcome.Retry
        if (localContentVersion > collision.contentVersion) return CollisionOutcome.RebaseAsUpdate(server.serverVersion)
        return CollisionOutcome.Settle(server.serverVersion)
    }
}

/**
 * Resolves a refused create for a record another device of this
 * account created first, preserving server deletions and avoiding duplicate versions or set-aside uploads.
 */
internal class CreateCollisionSettler(
    private val policy: CreateCollisionPolicy,
    private val retryCoordinator: SyncRetryCoordinator,
    private val syncMetadataService: SyncMetadataService,
    private val recordProgress: (Int) -> Unit,
    private val transactionManager: SyncTransactionManager,
) {
    /** Returns true when [error] was such a create and this device resolved it; false leaves it to the failure path. */
    suspend fun settle(
        entityType: EntityType,
        pending: PendingUpload,
        localContentVersion: Long,
        error: Throwable,
        serverCopy: suspend () -> Result<ContentState>,
        currentContentVersion: suspend () -> Long?,
        onDeleted: suspend () -> Unit,
    ): Boolean {
        val refusal = error as? CloudApiException ?: return false
        val exists = refusal.errorCode == CONTENT_EXISTS && refusal.statusCode == 412
        val deleted = refusal.errorCode == "CONTENT_DELETED" && refusal.statusCode == 410
        if (pending.operation != PendingOperation.CREATE || (!exists && !deleted)) return false
        val collision = policy.collisionFor(entityType, pending.entityId)
        if (collision is CreateCollision.Unexpected) return false
        val server =
            serverCopy().getOrElse {
                if (it is CancellationException) throw it
                Napier.w("Could not read the server's copy of a record another device created; the upload is retried")
                return false
            }
        return transactionManager.withTransaction {
            if (!syncMetadataService.isCurrentOperation(entityType, pending) || currentContentVersion() != localContentVersion) {
                return@withTransaction false
            }
            when (val outcome = CreateCollisionResolver.resolve(collision, localContentVersion, server)) {
                is CollisionOutcome.Settle -> {
                    val settled = retryCoordinator.markUploadSettled(entityType, pending, Clock.System.now(), outcome.serverVersion)
                    if (settled) recordProgress(1)
                    settled
                }

                is CollisionOutcome.Deleted -> {
                    val settled = retryCoordinator.markUploadSettled(entityType, pending, outcome.deletedAt, 0L)
                    if (settled) {
                        onDeleted()
                        recordProgress(1)
                    }
                    settled
                }

                is CollisionOutcome.RebaseAsUpdate -> {
                    rebase(entityType, pending, outcome.serverVersion)
                }

                CollisionOutcome.Retry -> {
                    false
                }
            }
        }
    }

    suspend fun isKnownCapture(
        entityType: EntityType,
        entityId: String,
    ): Boolean = policy.collisionFor(entityType, entityId) is CreateCollision.CreatedElsewhere

    private suspend fun rebase(
        entityType: EntityType,
        pending: PendingUpload,
        serverVersion: Long,
    ): Boolean {
        if (!syncMetadataService.bindCreateToServerVersion(entityType, pending, serverVersion)) {
            Napier.w("Could not turn a create into an edit of the server's copy; the upload is retried")
            return false
        }
        retryCoordinator.releaseAttempt(entityType, pending)
        Napier.i("This device changed a record after another device backed it up; the change goes up as an edit")
        return true
    }

    private companion object {
        const val CONTENT_EXISTS = "CONTENT_EXISTS"
    }
}

/** Preserve strict-create refusals so the ordinary upload path can inspect current server state before settling. */
internal fun Result<ConditionalCreateResult>.asUploadResult(): Result<SyncUploadResult> =
    mapCatching { result ->
        when (result) {
            is ConditionalCreateResult.Created -> SyncUploadResult(result.serverVersion, result.syncedAt)

            is ConditionalCreateResult.AlreadyExists -> throw CloudApiException(
                "CONTENT_EXISTS",
                "Watch capture already exists",
                statusCode = 412,
            )

            is ConditionalCreateResult.Deleted -> throw CloudApiException("CONTENT_DELETED", "Watch capture was deleted", statusCode = 410)
        }
    }
