package app.logdate.client.datastore.featureflags

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.di.commonDatastoreModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureFlagStoreTest {
    private val preferences = TestPreferencesDataStore()
    private val store = DataStoreFeatureFlagStore(preferences)

    @Test
    fun `Places browsing uses only its saved opt-in and preserves other preferences`() =
        runTest {
            val placesKey = booleanPreferencesKey("places_beta_enabled")
            val legacyHistoryKey = booleanPreferencesKey("human_location_history_enabled")
            val storedChoices = listOf<Boolean?>(null, false, true)
            storedChoices.forEach { placesChoice ->
                storedChoices.forEach { legacyHistoryChoice ->
                    val preferences = TestPreferencesDataStore()
                    preferences.updateData {
                        mutablePreferencesOf(
                            booleanPreferencesKey("developer_mode_enabled") to false,
                            booleanPreferencesKey("location_background_tracking_enabled") to true,
                            longPreferencesKey("location_tracking_interval_minutes") to 7L,
                            stringPreferencesKey("location_capture_mode") to "ACTIVE",
                        ).apply {
                            placesChoice?.let { this[placesKey] = it }
                            legacyHistoryChoice?.let { this[legacyHistoryKey] = it }
                        }
                    }
                    val snapshot = preferences.data.first().asMap()
                    val store = DataStoreFeatureFlagStore(preferences)
                    val case = "Places=$placesChoice, legacy history=$legacyHistoryChoice"

                    assertEquals(placesChoice == true, store.isEnabled(FeatureFlag.PLACES_BETA), case)
                    assertEquals(legacyHistoryChoice ?: true, store.isEnabled(FeatureFlag.HUMAN_LOCATION_HISTORY), case)
                    assertEquals(snapshot, preferences.data.first().asMap(), "Reading must preserve $case")

                    listOf(true, false).forEach { enabled ->
                        store.setEnabled(FeatureFlag.PLACES_BETA, enabled)

                        assertEquals(enabled, store.isEnabled(FeatureFlag.PLACES_BETA), case)
                        assertEquals(
                            snapshot.filterKeys { it != placesKey },
                            preferences.data
                                .first()
                                .asMap()
                                .filterKeys { it != placesKey },
                            "Changing browsing must preserve all other preferences: $case",
                        )
                    }
                }
            }
        }

    @Test
    fun `internal build enables Quiet Save without changing the user preference`() =
        runTest {
            val app =
                koinApplication {
                    modules(
                        commonDatastoreModule,
                        module {
                            single<DataStore<Preferences>>(named("mainDataStore")) { preferences }
                            single(named("quietSaveDefault")) { true }
                        },
                    )
                }
            try {
                val internalStore = app.koin.get<FeatureFlagStore>()
                assertTrue(internalStore.isEnabled(FeatureFlag.QUIET_SAVE))
                assertFalse(store.isEnabled(FeatureFlag.QUIET_SAVE))
                val settings = LogdatePreferencesDataSource(preferences)
                settings.setQuietSaveEnabled(false)
                assertTrue(internalStore.isEnabled(FeatureFlag.QUIET_SAVE))
                assertFalse(settings.observeQuietSaveEnabled().first())
                internalStore.setEnabled(FeatureFlag.QUIET_SAVE, false)
                assertFalse(internalStore.isEnabled(FeatureFlag.QUIET_SAVE))
            } finally {
                app.close()
            }
        }

    @Test
    fun `connected assistants stays unavailable with unset stored and default enabled preferences`() =
        runTest {
            val flag = assertNotNull(FeatureFlag.forKey("connected_assistants_enabled"))
            assertFalse(flag.defaultEnabled)
            assertFalse(flag.availableForLaunch)
            assertFalse(store.isEnabled(flag))

            preferences.updateData { it.toMutablePreferences().apply { this[booleanPreferencesKey(flag.key)] = true } }
            val internalStore = DataStoreFeatureFlagStore(preferences, defaultOverrides = mapOf(flag to true))

            assertFalse(store.isEnabled(flag))
            assertFalse(internalStore.isEnabled(flag))
        }

    @Test
    fun `an unset flag uses its declared default`() =
        runTest {
            assertTrue(store.isEnabled(FeatureFlag.LIBRARY))
            assertFalse(store.isEnabled(FeatureFlag.EVENTS))
            assertFalse(store.isEnabled(FeatureFlag.PEOPLE))
            assertTrue(store.isEnabled(FeatureFlag.CAMPFIRE_STREAKS))
        }

    @Test
    fun `internal watch default applies only to an unset watch flag`() =
        runTest {
            val internalStore =
                DataStoreFeatureFlagStore(
                    preferences,
                    defaultOverrides = mapOf(FeatureFlag.WATCH_EXPERIENCE_V2 to true),
                )

            assertTrue(internalStore.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2))
            assertFalse(internalStore.isEnabled(FeatureFlag.EVENTS))

            internalStore.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, false)

            assertFalse(internalStore.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2))
            assertEquals(false, preferences.data.first()[booleanPreferencesKey(FeatureFlag.WATCH_EXPERIENCE_V2.key)])
        }

    @Test
    fun `switching an internal watch flag back to its effective default clears the override`() =
        runTest {
            val internalStore =
                DataStoreFeatureFlagStore(
                    preferences,
                    defaultOverrides = mapOf(FeatureFlag.WATCH_EXPERIENCE_V2 to true),
                )
            internalStore.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, false)
            internalStore.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, true)

            assertNull(preferences.data.first()[booleanPreferencesKey(FeatureFlag.WATCH_EXPERIENCE_V2.key)])
            assertTrue(internalStore.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2))
        }

    @Test
    fun `Home workspace is enabled unless explicitly disabled`() =
        runTest {
            assertTrue(store.isEnabled(FeatureFlag.HOME_WORKSPACE_V2))

            store.setEnabled(FeatureFlag.HOME_WORKSPACE_V2, enabled = false)

            assertFalse(store.isEnabled(FeatureFlag.HOME_WORKSPACE_V2))
        }

    @Test
    fun `journal merge is enabled unless explicitly disabled`() =
        runTest {
            assertTrue(store.isEnabled(FeatureFlag.JOURNAL_MERGE))

            store.setEnabled(FeatureFlag.JOURNAL_MERGE, enabled = false)

            assertFalse(store.isEnabled(FeatureFlag.JOURNAL_MERGE))
        }

    @Test
    fun `setting a flag persists it and overrides the default`() =
        runTest {
            store.setEnabled(FeatureFlag.LIBRARY, enabled = false)
            store.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true)

            assertFalse(store.isEnabled(FeatureFlag.LIBRARY))
            assertTrue(store.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2))
        }

    @Test
    fun `setting a flag to a value other than its default stores that value`() =
        runTest {
            store.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true)
            store.setEnabled(FeatureFlag.CAMPFIRE_STREAKS, enabled = false)

            val stored = preferences.data.first()

            assertEquals(true, stored[booleanPreferencesKey("watch_experience_v2_enabled")])
            assertEquals(false, stored[booleanPreferencesKey("campfire_streaks_enabled")])
        }

    /**
     * A flag switched back to its default has no value of its own. A stored value is read before the
     * default, so one left behind would keep deciding the flag after the default changes, and a flag
     * that is on by default is no longer listed in Labs to switch it back.
     */
    @Test
    fun `setting a flag to its default clears the stored value`() =
        runTest {
            store.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = true)
            store.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = false)

            val stored = preferences.data.first()[booleanPreferencesKey("watch_experience_v2_enabled")]

            assertNull(stored, "a stored false would keep the flag off if its default turned on")
            assertFalse(store.isEnabled(FeatureFlag.WATCH_EXPERIENCE_V2))
        }

    @Test
    fun `setting a flag that is on by default back to on clears the stored value`() =
        runTest {
            store.setEnabled(FeatureFlag.CAMPFIRE_STREAKS, enabled = false)
            store.setEnabled(FeatureFlag.CAMPFIRE_STREAKS, enabled = true)

            val stored = preferences.data.first()[booleanPreferencesKey("campfire_streaks_enabled")]

            assertNull(stored)
            assertTrue(store.isEnabled(FeatureFlag.CAMPFIRE_STREAKS))
        }

    @Test
    fun `setting a flag to its default when nothing is stored stores nothing`() =
        runTest {
            store.setEnabled(FeatureFlag.WATCH_EXPERIENCE_V2, enabled = false)
            store.setEnabled(FeatureFlag.LIBRARY, enabled = true)

            val stored = preferences.data.first()

            assertTrue(stored.asMap().isEmpty())
        }

    @Test
    fun `observing a flag emits its current value`() =
        runTest {
            assertTrue(store.observe(FeatureFlag.LIBRARY).first())

            store.setEnabled(FeatureFlag.LIBRARY, enabled = false)

            assertFalse(store.observe(FeatureFlag.LIBRARY).first())
        }

    @Test
    fun `deferred features stay unavailable even with an old enabled preference`() =
        runTest {
            val dataSource = LogdatePreferencesDataSource(preferences)
            store.setEnabled(FeatureFlag.EVENTS, enabled = true)
            store.setEnabled(FeatureFlag.PEOPLE, enabled = true)

            assertFalse(store.isEnabled(FeatureFlag.EVENTS))
            assertFalse(store.isEnabled(FeatureFlag.PEOPLE))
            assertFalse(dataSource.observeEventsEnabled().first())
            assertFalse(dataSource.observePeopleEnabled().first())
        }

    @Test
    fun `OS integration surfaces stay off until they are turned on`() =
        runTest {
            val surfaces =
                listOf(
                    FeatureFlag.QUICK_SETTINGS_TILES,
                    FeatureFlag.LIVE_UPDATES,
                    FeatureFlag.RECORDING_BUBBLE,
                    FeatureFlag.APP_FUNCTIONS,
                    FeatureFlag.LOCK_SCREEN_CAPTURE_WIDGET,
                    FeatureFlag.NOTIFICATION_QUICK_REPLY,
                    FeatureFlag.EDITOR_HANDOFF,
                )
            surfaces.forEach { flag -> assertFalse(store.isEnabled(flag), "$flag should default off") }

            surfaces.forEach { flag -> store.setEnabled(flag, enabled = true) }

            surfaces.forEach { flag -> assertTrue(store.isEnabled(flag), "$flag should turn on") }
        }

    @Test
    fun `every flag has its own storage key`() {
        val keys = FeatureFlag.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a flag is stored under its declared key`() =
        runTest {
            store.setEnabled(FeatureFlag.LIBRARY, enabled = false)

            val stored = preferences.data.first()[booleanPreferencesKey("library_enabled")]

            assertEquals(false, stored)
        }

    /**
     * The three flags that predate this type are also read through their own accessors on
     * [LogdatePreferencesDataSource]. Both paths have to agree, or a feature is on according to
     * one caller and off according to another.
     */
    @Test
    fun `shares storage with the existing feature accessors`() =
        runTest {
            val dataSource = LogdatePreferencesDataSource(preferences)

            store.setEnabled(FeatureFlag.LIBRARY, enabled = false)
            assertFalse(dataSource.observeLibraryEnabled().first())

            dataSource.setLibraryEnabled(true)
            assertTrue(store.isEnabled(FeatureFlag.LIBRARY))
        }

    /**
     * The defaults declared on the flags have to match what the existing accessors already return,
     * otherwise introducing the registry silently changes what a feature does on a fresh install.
     */
    @Test
    fun `declared defaults match the existing accessors`() =
        runTest {
            val dataSource = LogdatePreferencesDataSource(preferences)

            assertEquals(dataSource.observeLibraryEnabled().first(), FeatureFlag.LIBRARY.defaultEnabled)
            assertEquals(dataSource.observeEventsEnabled().first(), FeatureFlag.EVENTS.defaultEnabled)
            assertEquals(dataSource.observePeopleEnabled().first(), FeatureFlag.PEOPLE.defaultEnabled)
        }

    @Test
    fun `a flag can be found by key and an unknown key finds nothing`() {
        assertEquals(FeatureFlag.LIBRARY, FeatureFlag.forKey("library_enabled"))
        assertNull(FeatureFlag.forKey("not_a_flag"))
    }

    @Test
    fun `every flag owns a distinct key`() {
        val keys = FeatureFlag.entries.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the watch experience flag starts off and can be turned on`() =
        runTest {
            val flag = assertNotNull(FeatureFlag.forKey("watch_experience_v2_enabled"))

            assertFalse(store.isEnabled(flag))

            store.setEnabled(flag, enabled = true)

            assertTrue(store.isEnabled(flag), "testers turn this flag on in release builds")
        }
}

private class TestPreferencesDataStore : DataStore<Preferences> {
    private val preferencesFlow = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> = preferencesFlow

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updatedPreferences = transform(preferencesFlow.value)
        preferencesFlow.value = updatedPreferences
        return updatedPreferences
    }
}
