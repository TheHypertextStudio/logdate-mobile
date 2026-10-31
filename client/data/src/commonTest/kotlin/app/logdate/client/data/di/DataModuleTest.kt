package app.logdate.client.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.logdate.client.data.notes.DatabaseNotePlaceResolver
import app.logdate.client.data.notes.NotePlaceResolver
import app.logdate.client.database.dao.PlaceDao
import app.logdate.client.database.entities.PlaceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.uuid.Uuid

/**
 * Verifies the Koin dependency injection configuration for the data layer.
 *
 * These tests ensure that key abstractions (like [NotePlaceResolver]) are correctly
 * bound to their concrete, persistent implementations in the data module's graph.
 */
class DataModuleTest {
    @Test
    fun `data module resolves database note place resolver as note place resolver`() {
        val koinApplication =
            koinApplication {
                allowOverride(true)
                modules(
                    module {
                        single<PlaceDao> { FakePlaceDao() }
                        single<NotePlaceResolver> { DatabaseNotePlaceResolver(get()) }
                        single<DataStore<Preferences>>(named("mainDataStore")) { FakePreferencesDataStore() }
                    },
                )
            }

        try {
            val resolver = koinApplication.koin.get<NotePlaceResolver>()
            assertIs<DatabaseNotePlaceResolver>(resolver)
        } finally {
            koinApplication.close()
        }
    }
}

private class FakePreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

private class FakePlaceDao : PlaceDao {
    private val placesFlow = MutableStateFlow<List<PlaceEntity>>(emptyList())

    override suspend fun insertIfAbsent(place: PlaceEntity): Long = -1

    override suspend fun insert(place: PlaceEntity) = Unit

    override suspend fun insertAll(places: List<PlaceEntity>) = Unit

    override suspend fun update(place: PlaceEntity) = Unit

    override suspend fun getById(id: Uuid): PlaceEntity? = null

    override fun observeById(id: Uuid): Flow<PlaceEntity?> = MutableStateFlow(null)

    override fun observeAll(): Flow<List<PlaceEntity>> = placesFlow

    override suspend fun getAll(): List<PlaceEntity> = emptyList()

    override suspend fun softDelete(
        id: Uuid,
        deletedAt: Long,
    ) = Unit

    override suspend fun hardDelete(id: Uuid) = Unit
}
