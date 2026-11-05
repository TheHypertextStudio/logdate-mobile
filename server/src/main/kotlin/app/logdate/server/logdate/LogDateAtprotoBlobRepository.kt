package app.logdate.server.logdate

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LogDate-owned metadata record for AT Protocol blobs.
 *
 * This keeps blob lookup and lifecycle state in a LogDate boundary while the ATProto route layer
 * exposes it as `uploadBlob` and `getBlob`.
 */
data class LogDateAtprotoBlob(
    val cid: String,
    val userId: UUID,
    val mimeType: String,
    val sizeBytes: Long,
    val storagePath: String,
    val createdAt: Long,
)

/**
 * Internal metadata boundary for AT Protocol blob lookups.
 */
interface LogDateAtprotoBlobRepository {
    fun upsertBlob(
        userId: UUID,
        blob: LogDateAtprotoBlob,
        identityEpoch: Long = 0,
    ): LogDateAtprotoBlob

    fun getBlob(
        userId: UUID,
        cid: String,
    ): LogDateAtprotoBlob?
}

/**
 * In-memory blob metadata implementation for tests and non-database server runs.
 */
class InMemoryLogDateAtprotoBlobRepository : LogDateAtprotoBlobRepository {
    private val rows = ConcurrentHashMap<UUID, ConcurrentHashMap<String, LogDateAtprotoBlob>>()

    override fun upsertBlob(
        userId: UUID,
        blob: LogDateAtprotoBlob,
        identityEpoch: Long,
    ): LogDateAtprotoBlob {
        requireLegacyIdentityEpoch(identityEpoch)
        val stored =
            blob.copy(
                cid = blob.cid,
                userId = userId,
            )
        rowsForUser(userId)[stored.cid] = stored
        return stored
    }

    override fun getBlob(
        userId: UUID,
        cid: String,
    ): LogDateAtprotoBlob? = rowsForUser(userId)[cid]

    private fun rowsForUser(userId: UUID): ConcurrentHashMap<String, LogDateAtprotoBlob> = rows.getOrPut(userId) { ConcurrentHashMap() }

    /** Where the bytes of every blob kept for [userId] are stored. */
    fun storagePaths(userId: UUID): List<String> = rows[userId]?.values?.map { it.storagePath }.orEmpty()

    /** Drops every blob record kept for [userId]. */
    fun forgetAccount(userId: UUID) {
        rows.remove(userId)
    }
}
