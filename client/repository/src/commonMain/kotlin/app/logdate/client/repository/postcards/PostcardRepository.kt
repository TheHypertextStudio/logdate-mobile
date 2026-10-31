package app.logdate.client.repository.postcards

import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A saved Postcard: the metadata shown in lists plus the serialized canvas document.
 *
 * @param sourceMomentRef the moment the Postcard was started from, when there is one
 * @param documentJson the canvas document, serialized by the postcards feature, which owns its format
 */
data class Postcard(
    val id: Uuid,
    val title: String,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val sourceMomentRef: Uuid? = null,
    val documentJson: String,
)

/**
 * Persistent store for the user's Postcards.
 */
interface PostcardRepository {
    /** Observes every Postcard, most recently modified first. */
    fun observeAllPostcards(): Flow<List<Postcard>>

    /** Observes the Postcard with [id]; emits null while no such Postcard exists. */
    fun observePostcard(id: Uuid): Flow<Postcard?>

    /** Returns the Postcard with [id], or null when no such Postcard exists. */
    suspend fun getPostcard(id: Uuid): Postcard?

    /** Stores a new Postcard. Leaves an existing Postcard with the same id unchanged. */
    suspend fun createPostcard(postcard: Postcard)

    /** Replaces the stored Postcard that has the same id. */
    suspend fun updatePostcard(postcard: Postcard)
}
