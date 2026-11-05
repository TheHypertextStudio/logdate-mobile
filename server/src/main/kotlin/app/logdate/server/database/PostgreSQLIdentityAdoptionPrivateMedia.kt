@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionMediaUpload
import app.logdate.server.identity.adoption.IdentityAdoptionMediaVerification
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionPrivateMedia
import app.logdate.server.identity.adoption.IdentityAdoptionPrivateMediaReceipt
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStream
import app.logdate.server.logdate.LogDateBlobStreamRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.sql.DataSource
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** One-shot provider writes have durable exclusive ownership before the first provider request. */
internal class PostgreSQLIdentityAdoptionPrivateMedia(
    private val source: DataSource,
    private val storage: LogDateBlobStorage,
    private val quota: suspend (Uuid) -> Long,
    private val clock: Clock = Clock.System,
    private val encryption: app.logdate.server.crypto.EncryptionService? = null,
    override val transfers: app.logdate.server.transfer.TransferSlots =
        app.logdate.server.transfer
            .TransferSlots(),
) : IdentityAdoptionPrivateMedia {
    override suspend fun readSource(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        reference: app.logdate.server.identity.adoption.IdentityAdoptionSourceReference,
        sourceDigest: String,
    ): app.logdate.server.identity.adoption.IdentityAdoptionMediaSourceStream? =
        PostgreSQLIdentityAdoptionMediaSource(
            source,
            storage,
            clock,
            encryption,
        ).read(operation, challenge, signature, reference, sourceDigest)

    override suspend fun begin(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        upload: IdentityAdoptionMediaUpload,
    ): IdentityAdoptionPrivateMediaReceipt? {
        val frozen = upload.copy(source = upload.source.copy(key = upload.source.key.toList()))
        val digest = mediaUploadDigest(frozen)
        if (challenge.action != IdentityAdoptionAction.BEGIN_BLOB || challenge.bodyDigest != digest) return null
        val blobId = Uuid.random().toString()
        val ownerToken =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
        val path = storage.plannedPrivateBlobPath(blobId)
        val limit = quota(operation.binding.accountId)
        return PostgreSQLIdentityAdoptionActions(source, clock).consume(operation, challenge, signature, clock.now()) {
            val generation = mediaUploadGeneration(operation)
            mediaUpload(operation, frozen.uploadId)?.let {
                stagingCheck(
                    it.upload == frozen,
                    IdentityAdoptionStagingFailure.CONFLICT,
                )
                touchMediaUpload(frozen.uploadId, clock.now())
                val receipt = it.receipt(generation)
                if (it.phase == "VERIFIED") {
                    requireMediaUploadSource(source, operation, it.upload, clock.now())
                    if (!retainedMediaPin(operation, it.admission(operation, generation))) {
                        return@consume receipt.copy(state = "STORED")
                    }
                }
                return@consume receipt
            }
            requireMediaUploadSource(source, operation, frozen, clock.now())
            requireIdentityAdoptionStagingRoom(operation, frozen.size, limit, clock.now())
            lockObjectPath(path)
            requireExclusiveObjectPath(operation.binding.accountId.toJavaUUID(), LegacyObjectKind.UNREFERENCED, path, path)
            val retired =
                prepareStatement("SELECT 1 FROM legacy_object_deletions WHERE storage_path=?").use {
                    it.setString(1, path)
                    it.executeQuery().use { rows -> rows.next() }
                }
            stagingCheck(!retired, IdentityAdoptionStagingFailure.CONFLICT)
            insertMediaUpload(operation, frozen, blobId, ownerToken, path, clock.now())
            requireNotNull(mediaUpload(operation, frozen.uploadId)).receipt(generation)
        }
    }

    override suspend fun write(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
        length: Long,
        input: InputStream,
    ): IdentityAdoptionPrivateMediaReceipt? {
        val row =
            authorize(operation, challenge, signature, uploadId, "write", IdentityAdoptionAction.BEGIN_BLOB) { row ->
                stagingCheck(length == row.upload.size, IdentityAdoptionStagingFailure.INVALID_REQUEST)
                if (row.phase == "ADMITTED") {
                    check(mediaUploadPhase(uploadId, "ADMITTED", "WRITING", clock.now()))
                    row.copy(phase = "WRITING")
                } else {
                    null
                }
            } ?: return null
        var settled = false
        var collision = false
        try {
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val writer =
                    storage.openBlobWriter(
                        LogDateBlobStreamRequest(
                            operation.binding.accountId.toJavaUUID(),
                            LogDateBlobNamespace.SPACE,
                            row.blobId,
                            "application/octet-stream",
                            createOnly = true,
                            privateOwner = row.ownerToken,
                        ),
                    )
                try {
                    check(writer.storagePath == row.path)
                    streamIdentityAdoptionMedia(writer, input, row.upload.cid, length)
                    writer.commit()
                } catch (error: app.logdate.server.logdate.LogDateBlobCollisionException) {
                    collision = true
                    throw error
                } finally {
                    writer.closeForCleanup()
                    settled = !collision && writer.storagePath == row.path
                }
            }
        } finally {
            if (settled) {
                withContext(NonCancellable + RawJdbcTransactionContext()) {
                    source.blueskyTransaction {
                        lockMediaUploadOwner(operation)
                        mediaUploadPhase(uploadId, "WRITING", "STORED", clock.now())
                    }
                }
            }
        }
        return current(operation, uploadId)?.receipt(currentGeneration(operation) ?: return null)
    }

    override suspend fun verify(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
    ): IdentityAdoptionMediaVerification? {
        if (operation.phase !in
            setOf(
                app.logdate.server.identity.adoption.IdentityAdoptionPhase.PREPARING,
                app.logdate.server.identity.adoption.IdentityAdoptionPhase.FENCED,
            )
        ) {
            return null
        }
        val row =
            authorize(operation, challenge, signature, uploadId, "verify", IdentityAdoptionAction.READ_STAGED) { it }
                ?: return null
        if (row.phase !in setOf("WRITING", "STORED", "VERIFIED")) return null
        val generation = currentGeneration(operation) ?: return null
        val admission = row.admission(operation, generation)
        val proof = IdentityAdoptionVerifiedBlob.readOwned(admission, storage, row.ownerToken) ?: return null
        return withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                val actions = PostgreSQLIdentityAdoptionActions(source, clock)
                if (with(actions) { currentKey(operation, clock.now()) } == null) return@blueskyTransaction null
                val latest = mediaUpload(operation, uploadId) ?: return@blueskyTransaction null
                if (latest.upload != row.upload ||
                    latest.path != row.path ||
                    latest.ownerToken != row.ownerToken ||
                    latest.blobId != row.blobId ||
                    latest.phase !in setOf("WRITING", "STORED", "VERIFIED")
                ) {
                    return@blueskyTransaction null
                }
                if (mediaUploadGeneration(operation) != proof.admission.generation) return@blueskyTransaction null
                requireMediaUploadSource(source, operation, latest.upload, clock.now())
                if (latest.phase != "VERIFIED") check(mediaUploadPhase(uploadId, latest.phase, "VERIFIED", clock.now()))
                val retained = retainedMediaPin(operation, proof.admission)
                pinMediaUpload(operation, proof.admission, clock.now(), keepExisting = retained)
                IdentityAdoptionMediaVerification(
                    operation.id,
                    uploadId,
                    generation,
                    row.upload.cid,
                    row.upload.size,
                    "application/octet-stream",
                )
            }
        }
    }

    override suspend fun discard(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
    ): IdentityAdoptionPrivateMediaReceipt? {
        val cleanup = PostgreSQLIdentityAdoptionMediaCleanup(source, storage, clock)
        val row =
            authorize(operation, challenge, signature, uploadId, "discard", IdentityAdoptionAction.BEGIN_BLOB) {
                if (it.phase !in setOf("ADMITTED", "WRITING", "STORED", "DELETE_PENDING", "RETIRED")) return@authorize null
                if (it.phase == "RETIRED") it else with(cleanup) { retire(it) }
            } ?: return null
        if (row.phase != "RETIRED" && !cleanup.cleanup(uploadId)) return null
        val generation = currentGeneration(operation) ?: return null
        return row.copy(phase = "RETIRED").receipt(generation)
    }

    override suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        uploadId: String,
    ): LogDateBlobStream? {
        val row =
            authorize(operation, challenge, signature, uploadId, "read", IdentityAdoptionAction.READ_STAGED) {
                it.takeIf { value -> value.phase == "VERIFIED" }
            } ?: return null
        val stream = withContext(Dispatchers.IO) { storage.openPrivateBlob(row.path, row.ownerToken) } ?: return null
        val retained =
            try {
                current(operation, uploadId)
            } catch (error: Throwable) {
                withContext(NonCancellable + Dispatchers.IO) { stream.close() }
                throw error
            }
        if (stream.size != row.upload.size ||
            retained?.phase != "VERIFIED" ||
            retained.ownerToken != row.ownerToken ||
            retained.path != row.path
        ) {
            withContext(NonCancellable + Dispatchers.IO) { stream.close() }
            return null
        }
        val digest =
            java.util.HexFormat.of().formatHex(
                studio.hypertext.atproto.repo.Cid
                    .require(row.upload.cid)
                    .toBytes()
                    .copyOfRange(4, 36),
            )
        return LogDateBlobStream(stream.size, VerifiedMediaInputStream(stream.input, stream.size, digest) {})
    }

    private fun java.sql.Connection.retainedMediaPin(
        operation: IdentityAdoptionOperation,
        admission: app.logdate.server.identity.adoption.IdentityAdoptionBlobAdmission,
    ): Boolean {
        val previous =
            try {
                PostgreSQLIdentityAdoptionBlobs(
                    source,
                    clock,
                ).inspectLocked(this, operation, admission.generation, admission.cid, clock.now())
            } catch (failure: app.logdate.server.identity.adoption.IdentityAdoptionStagingException) {
                if (failure.reason != IdentityAdoptionStagingFailure.SOURCE_CHANGED) throw failure
                null
            }
        return previous != null && previous.size == admission.size && previous.mimeType == admission.mimeType
    }

    private suspend fun <T> authorize(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        id: String,
        action: String,
        kind: IdentityAdoptionAction,
        block: java.sql.Connection.(IdentityAdoptionMediaUploadRow) -> T,
    ): T? {
        if (challenge.action != kind || challenge.bodyDigest != mediaUploadActionDigest(id, action)) return null
        return PostgreSQLIdentityAdoptionActions(source, clock).consume(operation, challenge, signature, clock.now()) {
            val row = mediaUpload(operation, id) ?: return@consume null
            if (action != "discard") requireMediaUploadSource(source, operation, row.upload, clock.now())
            touchMediaUpload(id, clock.now())
            block(row)
        }
    }

    private suspend fun current(
        operation: IdentityAdoptionOperation,
        id: String,
    ): IdentityAdoptionMediaUploadRow? =
        withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                val actions = PostgreSQLIdentityAdoptionActions(source, clock)
                if (with(actions) { currentKey(operation, clock.now()) } == null) return@blueskyTransaction null
                mediaUpload(operation, id)?.also { requireMediaUploadSource(source, operation, it.upload, clock.now()) }
            }
        }

    private suspend fun currentGeneration(operation: IdentityAdoptionOperation): Long? =
        withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                val actions = PostgreSQLIdentityAdoptionActions(source, clock)
                if (with(actions) { currentKey(operation, clock.now()) } == null) return@blueskyTransaction null
                mediaUploadGeneration(operation)
            }
        }
}
