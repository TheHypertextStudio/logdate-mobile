package app.logdate.server.identity.adoption

import app.logdate.server.database.RawJdbcTransactionContext
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.logdate.LogDateBlobStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import studio.hypertext.atproto.repo.Cid
import java.security.MessageDigest

internal const val IDENTITY_ADOPTION_MAX_BLOB_BYTES = 100L * 1024 * 1024

/** A private SQL admission must retain this source object before readback starts. */
internal data class IdentityAdoptionBlobAdmission(
    val id: String,
    val operationId: String,
    val generation: Long,
    val sourceSpace: String,
    val cid: String,
    val storagePath: String,
    val size: Long,
    val mimeType: String,
)

/** Only successful server readback can construct this evidence; it is never a request DTO. */
internal class IdentityAdoptionVerifiedBlob private constructor(
    val admission: IdentityAdoptionBlobAdmission,
) {
    companion object {
        suspend fun read(
            admission: IdentityAdoptionBlobAdmission,
            storage: LogDateBlobStorage,
        ): IdentityAdoptionVerifiedBlob? = readFrom(admission, storage, null)

        suspend fun readOwned(
            admission: IdentityAdoptionBlobAdmission,
            storage: LogDateBlobStorage,
            owner: String,
        ): IdentityAdoptionVerifiedBlob? = readFrom(admission, storage, owner)

        private suspend fun readFrom(
            admission: IdentityAdoptionBlobAdmission,
            storage: LogDateBlobStorage,
            owner: String?,
        ): IdentityAdoptionVerifiedBlob? {
            if (TransactionManager.currentOrNull() != null || RawJdbcTransactionContext.isActive()) {
                throw IdentityLifecycleConflictException("Identity adoption readback cannot run inside a database transaction")
            }
            currentCoroutineContext().ensureActive()
            if (admission.size !in 0..IDENTITY_ADOPTION_MAX_BLOB_BYTES || !acceptsCid(admission.cid)) return null
            return withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val stream =
                    (if (owner == null) storage.openBlob(admission.storagePath) else storage.openPrivateBlob(admission.storagePath, owner))
                        ?: return@withContext null
                stream.use {
                    if (it.size != admission.size) return@withContext null
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65_536)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val maximum = minOf(buffer.size.toLong(), admission.size - size + 1).toInt()
                        val count = it.input.read(buffer, 0, maximum)
                        currentCoroutineContext().ensureActive()
                        if (count < 0) break
                        if (count == 0) {
                            val byte = it.input.read()
                            currentCoroutineContext().ensureActive()
                            if (byte < 0) break
                            size++
                            if (size > admission.size) return@withContext null
                            digest.update(byte.toByte())
                        } else {
                            size += count
                            if (size > admission.size) return@withContext null
                            digest.update(buffer, 0, count)
                        }
                    }
                    if (size != admission.size) return@withContext null
                    val actual = Cid.fromBytes(HEADER + digest.digest()).value
                    if (actual != admission.cid) return@withContext null
                    IdentityAdoptionVerifiedBlob(admission)
                }
            }
        }

        fun acceptsCid(value: String): Boolean {
            if (value.length != 59) return false
            val bytes = runCatching { Cid.require(value).toBytes() }.getOrNull() ?: return false
            return bytes.size == 36 && bytes.take(4).toByteArray().contentEquals(HEADER) && Cid.fromBytes(bytes).value == value
        }

        private val HEADER = byteArrayOf(1, 0x55, 0x12, 0x20)
    }
}
