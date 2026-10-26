package app.logdate.server.logdate

import app.logdate.server.sync.StaleWriteException
import app.logdate.shared.model.sync.DeviceId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.absoluteValue
import kotlin.random.Random

/**
 * LogDate-owned media metadata record.
 *
 * This remains compatible with the existing sync APIs while keeping the server's internal
 * language focused on LogDate media concepts rather than sync persistence details.
 */
data class LogDateMedia(
    val mediaId: String,
    val contentId: String,
    val userId: UUID,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val data: ByteArray,
    val storagePath: String? = null,
    val createdAt: Long,
    val version: Long,
    val deviceId: DeviceId,
    val encryptionVersion: Int? = null,
    val encryptionKeyId: String? = null,
    val encryptionMode: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LogDateMedia

        if (sizeBytes != other.sizeBytes) return false
        if (createdAt != other.createdAt) return false
        if (version != other.version) return false
        if (encryptionVersion != other.encryptionVersion) return false
        if (mediaId != other.mediaId) return false
        if (contentId != other.contentId) return false
        if (userId != other.userId) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (!data.contentEquals(other.data)) return false
        if (storagePath != other.storagePath) return false
        if (deviceId != other.deviceId) return false
        if (encryptionKeyId != other.encryptionKeyId) return false
        if (encryptionMode != other.encryptionMode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sizeBytes.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + (encryptionVersion ?: 0)
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + contentId.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + (storagePath?.hashCode() ?: 0)
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + (encryptionKeyId?.hashCode() ?: 0)
        result = 31 * result + (encryptionMode?.hashCode() ?: 0)
        return result
    }
}

/**
 * Internal boundary for media metadata operations.
 */
interface LogDateMediaRepository {
    fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
    ): LogDateMedia

    fun getMedia(
        userId: UUID,
        mediaId: String,
    ): LogDateMedia?

    fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
    )

    /**
     * Lists every media record owned by [userId], including soft-deleted ones, so account-deletion
     * can clean up blobs the user may have deleted but whose storage paths still point to bytes
     * we need to remove.
     */
    fun listAllForUser(userId: UUID): List<LogDateMedia>
}

/**
 * In-memory media metadata implementation for tests and non-database server runs.
 */
class InMemoryLogDateMediaRepository : LogDateMediaRepository {
    private val rows = ConcurrentHashMap<UUID, ConcurrentHashMap<String, StoredLogDateMedia>>()
    private val versions = ConcurrentHashMap<UUID, AtomicLong>()

    @Synchronized
    override fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
    ): LogDateMedia {
        val mediaId = media.mediaId.ifBlank { "media-${Random.nextLong().absoluteValue}" }
        rowsForUser(userId)[mediaId]?.deletedAt?.let { throw StaleWriteException(mediaId, it) }
        val version = nextVersion(userId)
        val stored =
            media.copy(
                mediaId = mediaId,
                userId = userId,
                version = version,
            )
        rowsForUser(userId)[mediaId] = StoredLogDateMedia(media = stored, deletedAt = null)
        return stored
    }

    override fun getMedia(
        userId: UUID,
        mediaId: String,
    ): LogDateMedia? =
        rowsForUser(userId)[mediaId]
            ?.takeIf { it.deletedAt == null }
            ?.media

    @Synchronized
    override fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
    ) {
        val existing = rowsForUser(userId)[mediaId]
        val previous =
            existing?.media ?: LogDateMedia(
                mediaId,
                "",
                userId,
                "",
                "application/octet-stream",
                0,
                byteArrayOf(),
                null,
                deletedAt,
                0,
                DeviceId.UNKNOWN,
            )
        rowsForUser(userId)[mediaId] =
            StoredLogDateMedia(
                media = previous.copy(version = nextVersion(userId)),
                deletedAt = maxOf(deletedAt, existing?.deletedAt ?: deletedAt),
            )
    }

    override fun listAllForUser(userId: UUID): List<LogDateMedia> = rowsForUser(userId).values.map(StoredLogDateMedia::media)

    private fun rowsForUser(userId: UUID): ConcurrentHashMap<String, StoredLogDateMedia> = rows.getOrPut(userId) { ConcurrentHashMap() }

    private fun nextVersion(userId: UUID): Long {
        val counter = versions.getOrPut(userId) { AtomicLong(System.currentTimeMillis()) }
        val candidate = System.currentTimeMillis()
        return counter.updateAndGet { previous ->
            when {
                candidate > previous -> candidate
                else -> previous + 1L
            }
        }
    }
}

private data class StoredLogDateMedia(
    val media: LogDateMedia,
    val deletedAt: Long?,
)
