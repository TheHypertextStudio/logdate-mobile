package app.logdate.client.data.notes

import app.logdate.client.database.dao.PlaceDao
import app.logdate.client.repository.journals.NotePlace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/**
 * Resolves and observes semantic [NotePlace] records referenced by persisted notes.
 */
interface NotePlaceResolver {
    suspend fun get(placeId: Uuid): NotePlace?

    fun observeAll(): Flow<Map<Uuid, NotePlace>>
}

/**
 * [NotePlaceResolver] backed by [PlaceDao].
 *
 * @property placeDao DAO used to query and observe saved places.
 */
class DatabaseNotePlaceResolver(
    private val placeDao: PlaceDao,
) : NotePlaceResolver {
    override suspend fun get(placeId: Uuid): NotePlace? = placeDao.getById(placeId)?.toNotePlace()

    override fun observeAll(): Flow<Map<Uuid, NotePlace>> =
        placeDao.observeAll().map { places ->
            places.associate { place ->
                place.id to place.toNotePlace()
            }
        }
}

/** No-op [NotePlaceResolver] that resolves no places, used when place lookup is unavailable. */
object EmptyNotePlaceResolver : NotePlaceResolver {
    override suspend fun get(placeId: Uuid): NotePlace? = null

    override fun observeAll(): Flow<Map<Uuid, NotePlace>> = kotlinx.coroutines.flow.flowOf(emptyMap())
}
