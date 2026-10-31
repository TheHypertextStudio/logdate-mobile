package app.logdate.client.data.postcards

import app.logdate.client.database.dao.PostcardDao
import app.logdate.client.database.entities.PostcardEntity
import app.logdate.client.repository.postcards.Postcard
import app.logdate.client.repository.postcards.PostcardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Implementation of [PostcardRepository] backed by the local Room database.
 */
class OfflineFirstPostcardRepository(
    private val postcardDao: PostcardDao,
) : PostcardRepository {
    override fun observeAllPostcards(): Flow<List<Postcard>> =
        postcardDao
            .getAllPostcards()
            .map { rows -> rows.map { it.toDomain() } }

    override fun observePostcard(id: Uuid): Flow<Postcard?> =
        postcardDao
            .getPostcard(id)
            .map { it?.toDomain() }

    override suspend fun getPostcard(id: Uuid): Postcard? = postcardDao.getPostcardOneShot(id)?.toDomain()

    override suspend fun createPostcard(postcard: Postcard) {
        postcardDao.insert(postcard.toEntity())
    }

    override suspend fun updatePostcard(postcard: Postcard) {
        postcardDao.update(postcard.toEntity())
    }
}

private fun PostcardEntity.toDomain(): Postcard =
    Postcard(
        id = id,
        title = title,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        sourceMomentRef = sourceMomentRef,
        documentJson = documentJson,
    )

private fun Postcard.toEntity(): PostcardEntity =
    PostcardEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        sourceMomentRef = sourceMomentRef,
        documentJson = documentJson,
    )
