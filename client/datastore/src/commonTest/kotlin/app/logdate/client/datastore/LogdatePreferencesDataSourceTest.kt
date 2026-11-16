package app.logdate.client.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [LogdatePreferencesDataSource], which provides a high-level API for
 * accessing and modifying application-wide user preferences.
 *
 * This suite verifies that default preference values are correctly set and that
 * many changes to settings (such as search visibility or feature toggles) are correctly
 * persisted to the underlying [DataStore].
 */
class LogdatePreferencesDataSourceTest {
    private val dataSource = LogdatePreferencesDataSource(TestPreferencesDataStore())

    @Test
    fun `quiet save opt out survives reopening preferences`() =
        runTest {
            val store = TestPreferencesDataStore()
            val first = LogdatePreferencesDataSource(store)
            assertEquals(true, first.observeQuietSaveEnabled().first())
            first.setQuietSaveEnabled(false)
            assertEquals(false, LogdatePreferencesDataSource(store).observeQuietSaveEnabled().first())
        }

    @Test
    fun `system search visibility defaults to enabled`() =
        runTest {
            assertTrue(dataSource.getSystemSearchVisibilityEnabled())
        }

    @Test
    fun `system search visibility can be persisted`() =
        runTest {
            dataSource.setSystemSearchVisibilityEnabled(true)

            assertEquals(true, dataSource.getSystemSearchVisibilityEnabled())
        }

    @Test
    fun `android platform search index state can be persisted`() =
        runTest {
            dataSource.setAndroidPlatformSearchIndexState(
                generation = 42L,
                schemaVersion = 3,
            )

            assertEquals(42L, dataSource.getAndroidPlatformSearchIndexedGeneration())
            assertEquals(3, dataSource.getAndroidPlatformSearchSchemaVersion())
        }

    @Test
    fun `favorite notes are persisted and deduplicated`() =
        runTest {
            dataSource.addFavoriteNotes(setOf("note-1", "note-2"))
            dataSource.addFavoriteNotes(setOf("note-2", "note-3"))

            assertEquals(
                setOf("note-1", "note-2", "note-3"),
                dataSource
                    .userData
                    .first()
                    .favoriteNotes
                    .toSet(),
            )
        }

    @Test
    fun `has seen rewind onboarding defaults to false`() =
        runTest {
            assertEquals(false, dataSource.hasSeenRewindOnboarding())
        }

    @Test
    fun `has seen rewind onboarding can be persisted`() =
        runTest {
            dataSource.setHasSeenRewindOnboarding(true)

            assertEquals(true, dataSource.hasSeenRewindOnboarding())
            assertEquals(true, dataSource.observeHasSeenRewindOnboarding().first())
        }

    @Test
    fun `rewind curation strictness defaults to standard`() =
        runTest {
            assertEquals("STANDARD", dataSource.getRewindCurationStrictness())
        }

    @Test
    fun `rewind curation strictness can be persisted`() =
        runTest {
            dataSource.setRewindCurationStrictness("STRICT")

            assertEquals("STRICT", dataSource.getRewindCurationStrictness())
            assertEquals("STRICT", dataSource.observeRewindCurationStrictness().first())
        }

    @Test
    fun `rewind include screenshots defaults to false`() =
        runTest {
            assertEquals(false, dataSource.isRewindIncludeScreenshots())
        }

    @Test
    fun `rewind include screenshots can be persisted`() =
        runTest {
            dataSource.setRewindIncludeScreenshots(true)

            assertEquals(true, dataSource.isRewindIncludeScreenshots())
            assertEquals(true, dataSource.observeRewindIncludeScreenshots().first())
        }
}

/**
 * A test implementation of [DataStore<Preferences>] for testing.
 */
private class TestPreferencesDataStore : DataStore<Preferences> {
    private val preferencesFlow = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = preferencesFlow

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updatedPreferences = transform(preferencesFlow.value)
        preferencesFlow.value = updatedPreferences
        return updatedPreferences
    }
}
