package app.logdate.client.data.stickers

import app.logdate.client.data.fakes.RecordingMediaClaimFence
import app.logdate.client.database.dao.StickerDao
import app.logdate.client.database.entities.StickerEntity
import app.logdate.client.repository.stickers.Sticker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OfflineFirstStickerRepositoryTest {
    @Test
    fun `sticker media owner writes execute inside the media claim fence`() =
        runTest {
            val fence = RecordingMediaClaimFence()
            val dao = FakeStickerDao(onInsert = { assertTrue(fence.isInsideClaim) })
            val repository = OfflineFirstStickerRepository(dao, fence)

            val id =
                repository.saveSticker(
                    sourcePhotoUri = "logdate-media://library/objects/source.jpg",
                    sourceMomentRef = null,
                    imageUri = "logdate-media://library/objects/sticker.png",
                    label = "Cat",
                )

            val inserted = dao.rows.value.single()
            assertEquals(1, fence.claims)
            assertEquals(id, inserted.id)
            assertEquals("logdate-media://library/objects/source.jpg", inserted.sourcePhotoUri)
            assertEquals("logdate-media://library/objects/sticker.png", inserted.imageUri)
            assertEquals("Cat", inserted.label)
        }

    @Test
    fun `observed stickers expose the id image and label of each row`() =
        runTest {
            val withLabel = stickerEntity(label = "Dog")
            val withoutLabel = stickerEntity(label = null)
            val dao = FakeStickerDao(initial = listOf(withLabel, withoutLabel))

            val stickers = OfflineFirstStickerRepository(dao).observeAllStickers().first()

            assertEquals(
                listOf(
                    Sticker(id = withLabel.id, imageUri = withLabel.imageUri, label = "Dog"),
                    Sticker(id = withoutLabel.id, imageUri = withoutLabel.imageUri, label = null),
                ),
                stickers,
            )
        }
}

private fun stickerEntity(label: String?): StickerEntity {
    val id = Uuid.random()
    return StickerEntity(
        id = id,
        sourcePhotoUri = "logdate-media://library/objects/$id-source.jpg",
        sourceMomentRef = Uuid.random(),
        imageUri = "logdate-media://library/objects/$id.png",
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        label = label,
    )
}

private class FakeStickerDao(
    initial: List<StickerEntity> = emptyList(),
    private val onInsert: () -> Unit = {},
) : StickerDao {
    val rows = MutableStateFlow(initial)

    override fun getAllStickers(): Flow<List<StickerEntity>> = rows

    override suspend fun getSticker(id: Uuid): StickerEntity? = rows.value.firstOrNull { it.id == id }

    override fun getStickersForMoment(momentRef: Uuid): Flow<List<StickerEntity>> =
        rows.map { stickers -> stickers.filter { it.sourceMomentRef == momentRef } }

    override suspend fun insert(sticker: StickerEntity) {
        onInsert()
        rows.value = rows.value + sticker
    }

    override suspend fun delete(id: Uuid) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
