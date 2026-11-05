@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.logdate

import app.logdate.server.identity.adoption.IdentityWriteExpectation
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.uuid.toKotlinUuid

/** Coordinates external media and archive writes with their original account authority. */
class LegacyObjectUploads internal constructor(
    private val journal: ObjectUploads?,
) {
    val isDurable: Boolean get() = journal != null

    suspend fun <T> upload(
        owner: UUID,
        namespace: LogDateBlobNamespace,
        epoch: Long = 0,
        plannedPath: () -> String,
        write: () -> String,
        canonicalPath: (T) -> String?,
        publish: (String, Long) -> T,
        deleteObject: (String) -> Boolean,
    ): T {
        val kind =
            when (namespace) {
                LogDateBlobNamespace.MEDIA -> ObjectUploadKind.MEDIA
                LogDateBlobNamespace.BACKUP -> ObjectUploadKind.BACKUP
                else -> error("Unsupported upload namespace")
            }
        val store = journal ?: return publish(writePath(write), epoch)
        val origin = IdentityWriteExpectation(owner.toKotlinUuid(), epoch)
        val upload = store.begin(origin, null, writePath(plannedPath), kind)
        val path = writePath(write)
        check(path == upload.path) { "The provider wrote an unexpected object path" }
        val result =
            try {
                store.stored(upload)
                store.attach(upload, { publish(path, epoch) }, { canonicalPath(it) == path })
            } catch (failure: Exception) {
                settle(store, upload, deleteObject)
                throw failure
            }
        if (canonicalPath(result) != path) settle(store, upload, deleteObject)
        return result
    }

    private fun writePath(write: () -> String): String =
        try {
            write()
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: Exception) {
            throw LegacyObjectWriteFailure(failure)
        }

    private suspend fun settle(
        store: ObjectUploads,
        upload: ObjectUpload,
        deleteObject: (String) -> Boolean,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (!store.cleanup(upload, deleteObject)) Napier.w("Upload cleanup remains pending")
            } catch (_: Exception) {
                Napier.w("Uploaded object retained for cleanup")
            }
        }
    }

    companion object {
        val Direct: LegacyObjectUploads = LegacyObjectUploads(null)
    }
}

internal class LegacyObjectWriteFailure(
    cause: Exception,
) : RuntimeException("Object storage write failed", cause)
