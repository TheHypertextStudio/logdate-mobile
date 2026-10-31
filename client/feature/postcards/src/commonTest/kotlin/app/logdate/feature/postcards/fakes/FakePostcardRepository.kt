package app.logdate.feature.postcards.fakes

import app.logdate.client.repository.postcards.Postcard
import app.logdate.client.repository.postcards.PostcardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * In-memory [PostcardRepository] for tests.
 */
class FakePostcardRepository(
    initial: List<Postcard> = emptyList(),
) : PostcardRepository {
    private val postcards = MutableStateFlow(initial)

    override fun observeAllPostcards(): Flow<List<Postcard>> = postcards.map { all -> all.sortedByDescending { it.modifiedAt } }

    override fun observePostcard(id: Uuid): Flow<Postcard?> = postcards.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun getPostcard(id: Uuid): Postcard? = postcards.value.firstOrNull { it.id == id }

    override suspend fun createPostcard(postcard: Postcard) {
        if (postcards.value.any { it.id == postcard.id }) return
        postcards.value = postcards.value + postcard
    }

    override suspend fun updatePostcard(postcard: Postcard) {
        postcards.value = postcards.value.map { if (it.id == postcard.id) postcard else it }
    }
}
