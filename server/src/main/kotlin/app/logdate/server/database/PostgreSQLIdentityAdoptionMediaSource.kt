@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.crypto.EncryptionService
import app.logdate.server.identity.adoption.IDENTITY_ADOPTION_MAX_BLOB_BYTES
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionActionChallenge
import app.logdate.server.identity.adoption.IdentityAdoptionMediaSourceStream
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionSourceEvidence
import app.logdate.server.identity.adoption.IdentityAdoptionSourceItem
import app.logdate.server.identity.adoption.IdentityAdoptionSourceKind
import app.logdate.server.identity.adoption.IdentityAdoptionSourceReference
import app.logdate.server.identity.adoption.IdentityAdoptionStagingFailure
import app.logdate.server.identity.adoption.IdentityAdoptionVerifiedBlob
import app.logdate.server.logdate.LogDateBlobStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import studio.hypertext.atproto.repo.Cid
import java.io.InputStream
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.time.Clock

internal class PostgreSQLIdentityAdoptionMediaSource(
    private val source: DataSource,
    private val storage: LogDateBlobStorage,
    private val clock: Clock,
    private val encryption: EncryptionService?,
) {
    suspend fun read(
        operation: IdentityAdoptionOperation,
        challenge: IdentityAdoptionActionChallenge,
        signature: String,
        reference: IdentityAdoptionSourceReference,
        digest: String,
    ): IdentityAdoptionMediaSourceStream? {
        val frozen = reference.copy(key = reference.key.toList())
        val bodyDigest = mediaSourceDigest(frozen, digest)
        if (challenge.action != IdentityAdoptionAction.READ_STAGED || challenge.bodyDigest != bodyDigest) return null
        val item =
            PostgreSQLIdentityAdoptionActions(source, clock).consume(operation, challenge, signature, clock.now()) {
                original(operation, frozen, digest)
            } ?: return null
        val raw = openOriginal(operation, frozen, item) ?: return null
        if (!currentOrClose(operation, frozen, digest, raw.input::close)) return null
        val result =
            mediaSourceRepresentation(
                guarded(operation, frozen, digest, raw),
                raw.size,
                raw.hash,
                digest,
                encryption,
                decryptServerMedia = frozen.kind != IdentityAdoptionSourceKind.ATPROTO_BLOB,
            ) {
                reopen(operation, frozen, digest, raw, it)
            } ?: return null
        if (!currentOrClose(operation, frozen, digest, result.stream::close)) return null
        return result
    }

    private data class RawSource(
        val input: InputStream,
        val size: Long,
        val hash: String?,
        val path: String?,
    )

    private suspend fun openOriginal(
        operation: IdentityAdoptionOperation,
        reference: IdentityAdoptionSourceReference,
        item: IdentityAdoptionSourceItem,
    ): RawSource? {
        val path = (item.fields["storage_path"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (path == null) {
            val binary = item.binary
            stagingCheck(
                binary != null && binary.size in 0..IDENTITY_ADOPTION_MAX_BLOB_BYTES,
                IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
            )
            return RawSource(
                InlineMediaInputStream(source, operation, reference),
                requireNotNull(binary).size,
                binary.digest,
                null,
            )
        }
        val hash =
            if (reference.kind == IdentityAdoptionSourceKind.ATPROTO_BLOB) {
                stagingCheck(
                    IdentityAdoptionVerifiedBlob.acceptsCid(reference.key.single()),
                    IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE,
                )
                HexFormat.of().formatHex(Cid.require(reference.key.single()).toBytes().copyOfRange(4, 36))
            } else {
                null
            }
        val stream = withContext(Dispatchers.IO) { storage.openBlob(path) } ?: return null
        if (stream.size !in 0..IDENTITY_ADOPTION_MAX_BLOB_BYTES) {
            stream.close()
            return null
        }
        if (reference.kind == IdentityAdoptionSourceKind.ATPROTO_BLOB &&
            (item.fields["size_bytes"] as? JsonPrimitive)?.longOrNull != stream.size
        ) {
            stream.close()
            throw app.logdate.server.identity.adoption
                .IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
        }
        return RawSource(stream.input, stream.size, hash, path)
    }

    private fun guarded(
        operation: IdentityAdoptionOperation,
        reference: IdentityAdoptionSourceReference,
        digest: String,
        raw: RawSource,
    ) = VerifiedMediaInputStream(raw.input, raw.size, raw.hash) {
        check(runBlocking { current(operation, reference, digest) }) { "The source changed during media readback" }
    }

    private suspend fun reopen(
        operation: IdentityAdoptionOperation,
        reference: IdentityAdoptionSourceReference,
        digest: String,
        raw: RawSource,
        observedHash: String,
    ): InputStream? {
        val path = raw.path ?: return null
        if (!current(operation, reference, digest)) return null
        val stream = withContext(Dispatchers.IO) { storage.openBlob(path) } ?: return null
        if (stream.size != raw.size) {
            stream.close()
            return null
        }
        if (!currentOrClose(operation, reference, digest, stream::close)) return null
        return guarded(operation, reference, digest, raw.copy(input = stream.input, hash = observedHash))
    }

    private suspend fun currentOrClose(
        operation: IdentityAdoptionOperation,
        reference: IdentityAdoptionSourceReference,
        digest: String,
        close: () -> Unit,
    ): Boolean {
        try {
            if (current(operation, reference, digest)) return true
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { close() }
            throw error
        }
        withContext(NonCancellable + Dispatchers.IO) { close() }
        return false
    }

    private fun java.sql.Connection.original(
        operation: IdentityAdoptionOperation,
        reference: IdentityAdoptionSourceReference,
        digest: String,
    ): IdentityAdoptionSourceItem {
        val item =
            PostgreSQLIdentityAdoptionSourceInventory(source).itemLocked(
                this,
                operation,
                reference.kind,
                reference.key,
                clock.now(),
            )
        stagingCheck(
            item != null && HexFormat.of().formatHex(IdentityAdoptionSourceEvidence.item(reference.kind, item)) == digest,
            IdentityAdoptionStagingFailure.SOURCE_CHANGED,
        )
        return requireNotNull(item)
    }

    private suspend fun current(
        operation: IdentityAdoptionOperation,
        reference: IdentityAdoptionSourceReference,
        digest: String,
    ): Boolean =
        withContext(RawJdbcTransactionContext()) {
            source.blueskyTransaction {
                val actions = PostgreSQLIdentityAdoptionActions(source, clock)
                if (with(actions) { currentKey(operation, clock.now()) } == null) return@blueskyTransaction false
                original(operation, reference, digest)
                true
            }
        }
}

/** PostgreSQL bytea is fetched as bounded slices rather than materialized as a hundred-megabyte row. */
private class InlineMediaInputStream(
    private val source: DataSource,
    private val operation: IdentityAdoptionOperation,
    private val reference: IdentityAdoptionSourceReference,
) : InputStream() {
    private var offset = 1
    private var closed = false

    override fun read(): Int {
        val bytes = ByteArray(1)
        return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255
    }

    override fun read(
        bytes: ByteArray,
        start: Int,
        length: Int,
    ): Int {
        check(!closed)
        if (length == 0) return 0
        val table =
            when (reference.kind) {
                IdentityAdoptionSourceKind.SYNC_MEDIA -> "sync_media"
                IdentityAdoptionSourceKind.MEDIA -> "logdate_media_records"
                else -> error("This source has no inline media")
            }
        val payload =
            source.connection.use { connection ->
                connection.autoCommit = true
                connection.prepareStatement("SELECT substring(data from ? for ?) FROM $table WHERE user_id=? AND media_id=?").use {
                    it.setInt(1, offset)
                    it.setInt(2, minOf(length, 65536))
                    it.setObject(3, operation.binding.accountId.toJavaUUID())
                    it.setString(4, reference.key.single())
                    it.executeQuery().use { rows ->
                        check(rows.next())
                        rows.getBytes(1)
                    }
                }
            }
        if (payload.isEmpty()) return -1
        payload.copyInto(bytes, start)
        offset = Math.addExact(offset, payload.size)
        return payload.size
    }

    override fun close() {
        closed = true
    }
}
