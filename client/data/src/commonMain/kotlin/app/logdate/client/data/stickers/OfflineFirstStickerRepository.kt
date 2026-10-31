package app.logdate.client.data.stickers

import app.logdate.client.database.dao.StickerDao
import app.logdate.client.database.entities.StickerEntity
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.repository.stickers.Sticker
import app.logdate.client.repository.stickers.StickerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Implementation of [StickerRepository] backed by the local Room database.
 *
 * Writes run inside the [MediaClaimFence] so media reclamation cannot delete the source photo
 * or cut-out image that a new sticker references.
 */
class OfflineFirstStickerRepository(
    private val stickerDao: StickerDao,
    private val mediaClaimFence: MediaClaimFence = MediaClaimFence.Disabled,
) : StickerRepository {
    override fun observeAllStickers(): Flow<List<Sticker>> =
        stickerDao
            .getAllStickers()
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveSticker(
        sourcePhotoUri: String,
        sourceMomentRef: Uuid?,
        imageUri: String,
        label: String?,
    ): Uuid {
        val id = Uuid.random()
        mediaClaimFence.withClaim {
            stickerDao.insert(
                StickerEntity(
                    id = id,
                    sourcePhotoUri = sourcePhotoUri,
                    sourceMomentRef = sourceMomentRef,
                    imageUri = imageUri,
                    createdAt = Clock.System.now(),
                    label = label,
                ),
            )
        }
        return id
    }
}

private fun StickerEntity.toDomain(): Sticker =
    Sticker(
        id = id,
        imageUri = imageUri,
        label = label,
    )
