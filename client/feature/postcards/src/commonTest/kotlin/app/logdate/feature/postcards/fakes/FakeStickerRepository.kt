package app.logdate.feature.postcards.fakes

import app.logdate.client.repository.stickers.Sticker
import app.logdate.client.repository.stickers.StickerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlin.uuid.Uuid

/**
 * In-memory [StickerRepository] for tests.
 *
 * Pass `null` as the initial library to model a library that has not loaded yet; observers
 * receive nothing until [setStickers] is called.
 */
class FakeStickerRepository(
    initial: List<Sticker>? = emptyList(),
) : StickerRepository {
    private val stickers = MutableStateFlow(initial)

    fun setStickers(library: List<Sticker>) {
        stickers.value = library
    }

    override fun observeAllStickers(): Flow<List<Sticker>> = stickers.filterNotNull()

    override suspend fun saveSticker(
        sourcePhotoUri: String,
        sourceMomentRef: Uuid?,
        imageUri: String,
        label: String?,
    ): Uuid {
        val sticker = Sticker(id = Uuid.random(), imageUri = imageUri, label = label)
        stickers.value = listOf(sticker) + stickers.value.orEmpty()
        return sticker.id
    }
}
