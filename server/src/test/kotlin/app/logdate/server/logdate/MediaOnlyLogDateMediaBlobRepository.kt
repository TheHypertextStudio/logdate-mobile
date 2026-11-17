package app.logdate.server.logdate

import java.util.UUID

/**
 * Serves only the media half of [LogDateMediaBlobRepository] from a [LogDateMediaRepository], for tests
 * that seed media metadata without AT Protocol blobs.
 */
class MediaOnlyLogDateMediaBlobRepository(
    private val mediaRepository: LogDateMediaRepository,
) : LogDateMediaBlobRepository {
    override fun upsertMedia(
        userId: UUID,
        media: LogDateMedia,
    ): LogDateMedia = mediaRepository.upsertMedia(userId, media)

    override fun getMedia(
        userId: UUID,
        mediaId: String,
    ): LogDateMedia? = mediaRepository.getMedia(userId, mediaId)

    override fun deleteMedia(
        userId: UUID,
        mediaId: String,
        deletedAt: Long,
    ) {
        mediaRepository.deleteMedia(userId, mediaId, deletedAt)
    }

    override fun listMedia(userId: UUID): List<LogDateMedia> = mediaRepository.listAllForUser(userId)

    override fun upsertAtprotoBlob(
        userId: UUID,
        blob: LogDateAtprotoBlob,
    ): LogDateAtprotoBlob = blob

    override fun getAtprotoBlob(
        userId: UUID,
        cid: String,
    ): LogDateAtprotoBlob? = null
}

fun LogDateMediaRepository.asLogDateMediaBlobRepository(): LogDateMediaBlobRepository = MediaOnlyLogDateMediaBlobRepository(this)
