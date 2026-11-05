@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.logdate

import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateBlobWriter
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.ObjectUploadKind
import app.logdate.server.logdate.ObjectUploads
import app.logdate.server.logdate.deleteLegacyObject
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.space.SpaceBlob
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.uuid.toKotlinUuid

/** Durable journal access for streaming uploads; metadata finalizes in the space store's own transaction. */
class SpaceObjectUploads internal constructor(
    private val journal: ObjectUploads?,
) {
    internal fun begin(
        owner: UUID,
        epoch: Long,
        space: String,
        storage: LogDateBlobStorage,
        request: LogDateBlobStreamRequest,
    ): ObjectUpload? {
        val store = journal ?: return null
        check(request.ownerId == owner)
        val plan =
            LogDateBlobWriteRequest(
                owner,
                request.namespace,
                request.blobId,
                contentType = request.contentType,
                bytes = ByteArray(0),
            )
        return store.begin(
            IdentityWriteExpectation(owner.toKotlinUuid(), epoch),
            null,
            storage.plannedPath(plan),
            ObjectUploadKind.SPACE,
            space,
        )
    }

    internal fun beginMigration(
        owner: UUID,
        migration: SpaceMigration?,
        space: String,
        storage: LogDateBlobStorage,
        request: LogDateBlobStreamRequest,
    ): ObjectUpload? {
        val store = journal ?: return null
        if (migration == null || migration.accountId != owner || request.ownerId != owner) {
            throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
        }
        val plan =
            LogDateBlobWriteRequest(
                owner,
                request.namespace,
                request.blobId,
                contentType = request.contentType,
                bytes = ByteArray(0),
            )
        return store.beginMigration(
            IdentityWriteExpectation(owner.toKotlinUuid(), migration.identityEpoch),
            storage.plannedPath(plan),
            space,
            migration.migrationId,
            migration.version,
        )
    }

    internal suspend fun register(
        upload: ObjectUpload?,
        storage: LogDateBlobStorage,
        publish: suspend () -> SpaceBlob,
    ): SpaceBlob {
        if (upload == null) return publish()
        val store = requireNotNull(journal)
        return try {
            withContext(Dispatchers.IO) { store.stored(upload) }
            val result = publish()
            if (result.storagePath != upload.path) cleanup(upload, storage)
            result
        } catch (failure: Exception) {
            cleanup(upload, storage)
            throw failure
        }
    }

    internal suspend fun discard(
        upload: ObjectUpload?,
        writer: LogDateBlobWriter,
        storage: LogDateBlobStorage,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            if (upload == null) {
                writer.abort()
            } else {
                writer.closeForCleanup()
                requireNotNull(journal).stored(upload)
                cleanup(upload, storage)
            }
        }
    }

    private suspend fun cleanup(
        upload: ObjectUpload,
        storage: LogDateBlobStorage,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                requireNotNull(journal).cleanup(upload) { storage.deleteLegacyObject(it) }
            } catch (_: Exception) {
                Napier.w("Upload cleanup was not confirmed")
            }
        }
    }

    companion object {
        val Direct: SpaceObjectUploads = SpaceObjectUploads(null)
    }
}
