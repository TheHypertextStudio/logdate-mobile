package app.logdate.client.repository.stickers

import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * A sticker in the user's library: a subject cut out of a photo and stored as a PNG with
 * transparency.
 *
 * @param imageUri where the transparent PNG is stored
 * @param label the name the user gave the sticker, when there is one
 */
data class Sticker(
    val id: Uuid,
    val imageUri: String,
    val label: String? = null,
)

/**
 * Persistent store for the user's sticker library.
 */
interface StickerRepository {
    /** Observes the sticker library, most recently created first. */
    fun observeAllStickers(): Flow<List<Sticker>>

    /**
     * Adds a sticker cut out of the photo at [sourcePhotoUri] and returns its id.
     *
     * @param sourceMomentRef the moment the source photo belongs to, when known
     * @param imageUri where the cut-out PNG is stored
     */
    suspend fun saveSticker(
        sourcePhotoUri: String,
        sourceMomentRef: Uuid?,
        imageUri: String,
        label: String? = null,
    ): Uuid
}
