@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.server.atproto

import app.logdate.server.database.requireRepoWriteOrigin
import app.logdate.server.database.toJavaUUID
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.adoption.IdentityFenceOutcome
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.LogDateAtprotoBlob
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.ObjectUploads
import app.logdate.server.logdate.deleteLegacyObject
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.pds.BlobDownload
import studio.hypertext.atproto.pds.BlobRef
import studio.hypertext.atproto.pds.CidLink
import studio.hypertext.atproto.pds.GetBlobRequest
import studio.hypertext.atproto.pds.UploadBlobRequest
import studio.hypertext.atproto.pds.runtime.PdsBlobStore
import studio.hypertext.atproto.repo.Cid
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Server adapter that exposes LogDate-owned blob persistence through the shared PDS blob store
 * contract.
 */
internal class LogDatePdsBlobStore(
    private val identityService: AtprotoIdentityService,
    private val mediaBlobRepository: LogDateMediaBlobRepository,
    private val blobStorage: LogDateBlobStorage,
    private val uploads: ObjectUploads = ObjectUploads.Direct,
) : PdsBlobStore {
    override suspend fun putBlob(request: UploadBlobRequest): Result<BlobRef> =
        try {
            val origin = requireRepoWriteOrigin()
            val account = identityService.findByDid(request.repo.toString())
            if (account == null || account.id != origin.accountId || account.identityEpoch != origin.identityEpoch) {
                throw IdentityWriteRefusedException(IdentityFenceOutcome.CHANGED)
            }
            val userId = origin.accountId.toJavaUUID()
            val cid = Cid.rawSha256(request.bytes)
            val write =
                LogDateBlobWriteRequest(
                    ownerId = userId,
                    namespace = LogDateBlobNamespace.ATPROTO,
                    blobId = "$cid-${Uuid.random()}",
                    contentType = request.contentType,
                    bytes = request.bytes,
                )
            val upload = uploads.begin(origin, request.repo, blobStorage.plannedPath(write))
            val path = blobStorage.putBlob(write)
            check(path == upload.path) { "The provider wrote an unexpected object path" }
            register(upload) {
                mediaBlobRepository.upsertAtprotoBlob(
                    userId = userId,
                    blob =
                        LogDateAtprotoBlob(
                            cid = cid.toString(),
                            userId = userId,
                            mimeType = request.contentType,
                            sizeBytes = request.bytes.size.toLong(),
                            storagePath = path,
                            createdAt = Clock.System.now().toEpochMilliseconds(),
                        ),
                    identityEpoch = origin.identityEpoch,
                )
            }
            Result.success(BlobRef(ref = CidLink(cid), mimeType = request.contentType, size = request.bytes.size.toLong()))
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    private suspend fun <T> register(
        upload: ObjectUpload,
        publish: () -> T,
    ): T =
        try {
            uploads.stored(upload)
            uploads.attach(upload, publish)
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    uploads.cleanup(upload) { blobStorage.deleteLegacyObject(it) }
                } catch (_: Exception) {
                    Napier.w("Uploaded object retained for cleanup")
                }
            }
            throw failure
        }

    override suspend fun getBlob(request: GetBlobRequest): Result<BlobDownload?> =
        runCatching {
            val account = identityService.findByDid(request.did.toString()) ?: return@runCatching null
            val blob =
                mediaBlobRepository.getAtprotoBlob(
                    userId = account.id.toJavaUUID(),
                    cid = request.cid.toString(),
                ) ?: return@runCatching null
            val bytes = blobStorage.getBlob(blob.storagePath) ?: return@runCatching null
            BlobDownload(
                contentType = blob.mimeType,
                bytes = bytes,
            )
        }
}
