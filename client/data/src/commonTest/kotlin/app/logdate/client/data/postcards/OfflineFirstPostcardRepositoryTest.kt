package app.logdate.client.data.postcards

import app.logdate.client.database.dao.PostcardDao
import app.logdate.client.database.entities.PostcardEntity
import app.logdate.client.repository.postcards.Postcard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OfflineFirstPostcardRepositoryTest {
    @Test
    fun `created postcard is read back with every field intact`() =
        runTest {
            val dao = FakePostcardDao()
            val repository = OfflineFirstPostcardRepository(dao)
            val postcard = postcard(title = "Lisbon")

            repository.createPostcard(postcard)

            assertEquals(postcard, repository.getPostcard(postcard.id))
            assertEquals(postcard, repository.observePostcard(postcard.id).first())
            assertEquals(listOf(postcard), repository.observeAllPostcards().first())
            assertEquals(
                postcard.documentJson,
                dao.rows.value
                    .single()
                    .documentJson,
            )
        }

    @Test
    fun `update replaces the stored postcard`() =
        runTest {
            val repository = OfflineFirstPostcardRepository(FakePostcardDao())
            val original = postcard(title = "Draft", sourceMomentRef = null)
            repository.createPostcard(original)
            val edited =
                original.copy(
                    title = "Finished",
                    modifiedAt = Instant.fromEpochMilliseconds(1_700_000_600_000),
                    documentJson = """{"elements":[]}""",
                )

            repository.updatePostcard(edited)

            assertEquals(edited, repository.getPostcard(original.id))
        }

    @Test
    fun `missing postcard reads as null`() =
        runTest {
            val repository = OfflineFirstPostcardRepository(FakePostcardDao())
            val missingId = Uuid.random()

            assertNull(repository.getPostcard(missingId))
            assertNull(repository.observePostcard(missingId).first())
        }
}

private fun postcard(
    title: String,
    sourceMomentRef: Uuid? = Uuid.random(),
): Postcard =
    Postcard(
        id = Uuid.random(),
        title = title,
        createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        modifiedAt = Instant.fromEpochMilliseconds(1_700_000_300_000),
        sourceMomentRef = sourceMomentRef,
        documentJson = """{"title":"$title"}""",
    )

private class FakePostcardDao : PostcardDao {
    val rows = MutableStateFlow<List<PostcardEntity>>(emptyList())

    override fun getPostcard(id: Uuid): Flow<PostcardEntity?> = rows.map { postcards -> postcards.firstOrNull { it.id == id } }

    override suspend fun getPostcardOneShot(id: Uuid): PostcardEntity? = rows.value.firstOrNull { it.id == id }

    override fun getAllPostcards(): Flow<List<PostcardEntity>> = rows.map { postcards -> postcards.sortedByDescending { it.modifiedAt } }

    override fun getPostcardsForMoment(momentRef: Uuid): Flow<List<PostcardEntity>> =
        rows.map { postcards -> postcards.filter { it.sourceMomentRef == momentRef } }

    override suspend fun insert(postcard: PostcardEntity) {
        if (rows.value.any { it.id == postcard.id }) return
        rows.value = rows.value + postcard
    }

    override suspend fun update(postcard: PostcardEntity) {
        rows.value = rows.value.map { if (it.id == postcard.id) postcard else it }
    }

    override suspend fun delete(id: Uuid) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
