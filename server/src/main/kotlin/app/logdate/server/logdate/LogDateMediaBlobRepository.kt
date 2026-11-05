package app.logdate.server.logdate

import java.util.UUID

/**
 * Unified LogDate-owned boundary for first-party media metadata and AT Protocol blob metadata.
 *
 * This keeps binary object lifecycle concerns behind one internal interface even while the
 * current persistence layer still stores sync media rows and ATProto blob rows separately.
 */
interface LogDateMediaBlobRepository {
    fun cleanupUnreferencedObject(
        userId: UUID,
        path: String,
        identityEpoch: Long = 0,
        deleteObject: (String) -> Boolean,
    ): Boolean {
        requireLegacyIdentityEpoch(identityEpoch)
        if (listMedia(userId).any { it.storagePath == path }) return false
        return deleteObject(path)
    }

    fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
        identityEpoch: Long = 0,
    ): LogDateMedia

    fun deleteMediaWithObject(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
        identityEpoch: Long = 0,
        deleteObject: (String) -> Boolean,
    ): Boolean {
        requireLegacyIdentityEpoch(identityEpoch)
        val path = getMedia(userId, mediaId)?.storagePath
        if (path != null && !deleteObject(path)) return false
        deleteMedia(userId, mediaId, deletedAt, identityEpoch)
        return true
    }

    fun getMedia(
        userId: UUID,
        mediaId: String,
    ): LogDateMedia?

    fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
        identityEpoch: Long = 0,
    )

    /** All media for [userId], including soft-deleted rows. Used by account deletion cascade. */
    fun listMedia(userId: UUID): List<LogDateMedia>

    fun upsertAtprotoBlob(
        userId: UUID,
        blob: LogDateAtprotoBlob,
        identityEpoch: Long = 0,
    ): LogDateAtprotoBlob

    fun getAtprotoBlob(
        userId: UUID,
        cid: String,
    ): LogDateAtprotoBlob?
}

/**
 * Composite runtime adapter for the current media and ATProto blob repositories.
 */
class CompositeLogDateMediaBlobRepository(
    private val mediaRepository: LogDateMediaRepository,
    private val atprotoBlobRepository: LogDateAtprotoBlobRepository,
) : LogDateMediaBlobRepository {
    override fun cleanupUnreferencedObject(
        userId: UUID,
        path: String,
        identityEpoch: Long,
        deleteObject: (String) -> Boolean,
    ): Boolean = mediaRepository.cleanupUnreferencedObject(userId, path, identityEpoch, deleteObject)

    override fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
        identityEpoch: Long,
    ): LogDateMedia = mediaRepository.upsertMedia(userId, media, identityEpoch)

    override fun deleteMediaWithObject(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
        identityEpoch: Long,
        deleteObject: (String) -> Boolean,
    ): Boolean = mediaRepository.deleteMediaWithObject(userId, mediaId, deletedAt, identityEpoch, deleteObject)

    override fun getMedia(
        userId: UUID,
        mediaId: String,
    ): LogDateMedia? = mediaRepository.getMedia(userId, mediaId)

    override fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
        identityEpoch: Long,
    ) {
        mediaRepository.deleteMedia(userId, mediaId, deletedAt, identityEpoch)
    }

    override fun listMedia(userId: UUID): List<LogDateMedia> = mediaRepository.listAllForUser(userId)

    override fun upsertAtprotoBlob(
        userId: UUID,
        blob: LogDateAtprotoBlob,
        identityEpoch: Long,
    ): LogDateAtprotoBlob = atprotoBlobRepository.upsertBlob(userId, blob, identityEpoch)

    override fun getAtprotoBlob(
        userId: UUID,
        cid: String,
    ): LogDateAtprotoBlob? = atprotoBlobRepository.getBlob(userId, cid)
}

/**
 * Transitional adapter for call sites that only need the media half of the unified boundary.
 */
class MediaOnlyLogDateMediaBlobRepository(
    private val mediaRepository: LogDateMediaRepository,
) : LogDateMediaBlobRepository {
    override fun cleanupUnreferencedObject(
        userId: UUID,
        path: String,
        identityEpoch: Long,
        deleteObject: (String) -> Boolean,
    ): Boolean = mediaRepository.cleanupUnreferencedObject(userId, path, identityEpoch, deleteObject)

    override fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
        identityEpoch: Long,
    ): LogDateMedia = mediaRepository.upsertMedia(userId, media, identityEpoch)

    override fun deleteMediaWithObject(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
        identityEpoch: Long,
        deleteObject: (String) -> Boolean,
    ): Boolean = mediaRepository.deleteMediaWithObject(userId, mediaId, deletedAt, identityEpoch, deleteObject)

    override fun getMedia(
        userId: UUID,
        mediaId: String,
    ): LogDateMedia? = mediaRepository.getMedia(userId, mediaId)

    override fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
        identityEpoch: Long,
    ) {
        mediaRepository.deleteMedia(userId, mediaId, deletedAt, identityEpoch)
    }

    override fun listMedia(userId: UUID): List<LogDateMedia> = mediaRepository.listAllForUser(userId)

    override fun upsertAtprotoBlob(
        userId: UUID,
        blob: LogDateAtprotoBlob,
        identityEpoch: Long,
    ): LogDateAtprotoBlob {
        requireLegacyIdentityEpoch(identityEpoch)
        return blob
    }

    override fun getAtprotoBlob(
        userId: UUID,
        cid: String,
    ): LogDateAtprotoBlob? = null
}

fun LogDateMediaRepository.asLogDateMediaBlobRepository(): LogDateMediaBlobRepository = MediaOnlyLogDateMediaBlobRepository(this)
