package app.logdate.client.e2e

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.logdate.client.data.search.AndroidPlatformSearchIndexManager
import app.logdate.client.data.search.AndroidPlatformSearchRepository
import app.logdate.client.data.search.OfflineFirstSearchRepository
import app.logdate.client.data.search.OfflineFirstSearchResultDetailsRepository
import app.logdate.client.data.search.RewindLookup
import app.logdate.client.database.LogDateDatabase
import app.logdate.client.database.entities.JournalEntity
import app.logdate.client.database.entities.PlaceEntity
import app.logdate.client.database.entities.TextNoteEntity
import app.logdate.client.database.getRoomDatabase
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.datastore.featureflags.DataStoreFeatureFlagStore
import app.logdate.client.domain.search.ObserveRecentSearchesUseCase
import app.logdate.client.domain.search.ObserveSearchFacetsUseCase
import app.logdate.client.domain.search.UniversalSearchUseCase
import app.logdate.client.repository.search.RecentSearchesRepository
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchEntryActivity
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.SearchResultKey
import app.logdate.client.repository.search.SearchResultPreview
import app.logdate.feature.search.ui.ADAPTIVE_SEARCH_RESULTS_TAG
import app.logdate.feature.search.ui.AdaptiveSearchActions
import app.logdate.feature.search.ui.AdaptiveSearchBody
import app.logdate.feature.search.ui.SEARCH_NARROWING_TAG
import app.logdate.feature.search.ui.SEARCH_PREVIEW_ACTIVITY_TAG
import app.logdate.feature.search.ui.SEARCH_PREVIEW_NARROW_TAG
import app.logdate.feature.search.ui.SEARCH_PREVIEW_TAG
import app.logdate.feature.search.ui.SEARCH_PREVIEW_TITLE_TAG
import app.logdate.feature.search.ui.SearchNarrowingOptions
import app.logdate.feature.search.ui.SearchScreen
import app.logdate.feature.search.ui.SearchScreenContent
import app.logdate.feature.search.ui.SearchScreenState
import app.logdate.feature.search.ui.SearchViewModel
import app.logdate.feature.timeline.ui.details.PlaceLocationMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Instrumented tests for [SearchScreenContent].
 *
 * Verifies the global search screen renders results, empty state, and the
 * real database-backed search pipeline.
 */
@RunWith(AndroidJUnit4::class)
class SearchScreenE2ETest {
    private val postureSupport = FoldablePostureTestSupport()
    private val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(postureSupport.publisherRule).around(composeRule)

    private lateinit var database: LogDateDatabase
    private lateinit var searchScope: CoroutineScope
    private lateinit var viewModel: SearchViewModel

    private val textNoteResult =
        SearchResult(
            uid = Uuid.random(),
            content = "Finished the final chapter of the novel today.",
            created = Clock.System.now(),
            contentType = SearchContentType.TEXT_NOTE,
        )
    private val transcriptionResult =
        SearchResult(
            uid = Uuid.random(),
            content = "Voice memo about the sunset hike and planning next week.",
            created = Clock.System.now(),
            contentType = SearchContentType.TRANSCRIPTION,
        )
    private val allResults = listOf(textNoteResult, transcriptionResult)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            getRoomDatabase(
                builder = Room.inMemoryDatabaseBuilder(context, LogDateDatabase::class.java),
            )

        runBlocking {
            database.textNoteDao().addNote(
                TextNoteEntity(
                    content = "Hiked the sunrise trail before breakfast.",
                    uid = Uuid.random(),
                    created = Instant.fromEpochMilliseconds(1_000),
                    lastUpdated = Instant.fromEpochMilliseconds(1_000),
                ),
            )
            database.textNoteDao().addNote(
                TextNoteEntity(
                    content = "Reviewed quarterly budget numbers at the office.",
                    uid = Uuid.random(),
                    created = Instant.fromEpochMilliseconds(2_000),
                    lastUpdated = Instant.fromEpochMilliseconds(2_000),
                ),
            )
        }

        searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val searchRepository =
            AndroidPlatformSearchRepository(
                appSearchIndexManager =
                    AndroidPlatformSearchIndexManager(
                        context = context,
                        searchDao = database.searchDao(),
                        preferencesDataSource = LogdatePreferencesDataSource(TestPreferencesDataStore()),
                        externalScope = searchScope,
                        databaseName = "search_screen_e2e_${System.nanoTime()}",
                    ),
                roomSearchRepository =
                    OfflineFirstSearchRepository(
                        searchDao = database.searchDao(),
                        personDao = database.personDao(),
                        featureFlags = DataStoreFeatureFlagStore(TestPreferencesDataStore()),
                    ),
            )
        val recentSearchesRepository = InMemoryRecentSearchesRepository()
        viewModel =
            SearchViewModel(
                universalSearchUseCase = UniversalSearchUseCase(searchRepository),
                observeRecentSearchesUseCase = ObserveRecentSearchesUseCase(recentSearchesRepository),
                searchResultDetailsRepository =
                    OfflineFirstSearchResultDetailsRepository(
                        detailsDao = database.searchDetailsDao(),
                        personDao = database.personDao(),
                        rewinds = RewindLookup { null },
                    ),
                observeSearchFacetsUseCase = ObserveSearchFacetsUseCase(searchRepository),
            )
    }

    @After
    fun tearDown() {
        searchScope.cancel()
        database.close()
    }

    @Test
    fun `idle state shows prompt text`() {
        composeRule.setContent {
            SearchScreenContent(
                searchState = SearchScreenState.Idle(recentSearches = emptyList()),
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search for entries").assertIsDisplayed()
    }

    @Test
    fun `with results shows text note content`() {
        composeRule.setContent {
            SearchScreenContent(
                searchState = SearchScreenState.Results(results = allResults),
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Finished the final chapter of the novel today.").assertIsDisplayed()
    }

    @Test
    fun `with results shows transcription content`() {
        composeRule.setContent {
            SearchScreenContent(
                searchState = SearchScreenState.Results(results = allResults),
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        }

        composeRule.waitForIdle()
        composeRule
            .onNodeWithText(
                "Voice memo about the sunset hike and planning next week.",
            ).assertIsDisplayed()
    }

    @Test
    fun `empty state shows no results message`() {
        composeRule.setContent {
            SearchScreenContent(
                searchState = SearchScreenState.Empty(query = "nonexistent"),
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("No results", substring = true).assertIsDisplayed()
    }

    @Test
    fun `real search typing query returns indexed database results`() {
        composeRule.setContent {
            MaterialTheme {
                SearchScreen(
                    onResultClick = {},
                    onResultOpenDay = {},
                    onGoBack = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_screen_input").performClick()
        composeRule.onNodeWithTag("search_screen_input").performTextInput("sunr")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText(
                    "Hiked the sunrise trail before breakfast.",
                    substring = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule
            .onNodeWithText("Hiked the sunrise trail before breakfast.", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Reviewed quarterly budget numbers at the office.", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `real search unmatched query shows empty state`() {
        composeRule.setContent {
            MaterialTheme {
                SearchScreen(
                    onResultClick = {},
                    onResultOpenDay = {},
                    onGoBack = {},
                    viewModel = viewModel,
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("search_screen_input").performClick()
        composeRule.onNodeWithTag("search_screen_input").performTextInput("zzz_no_match")

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithText(
                    "No results for \"zzz_no_match\"",
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText("No results for \"zzz_no_match\"").assertIsDisplayed()
    }

    // region Adaptive search, on a managed device

    @Test
    fun adaptiveSearchPlacesPanelsForTheWindow() {
        seedLakeContent()
        showAdaptiveSearch()
        waitForAdaptiveResults()

        val widthDp = composeRule.activity.resources.configuration.screenWidthDp
        when {
            widthDp >= 840 -> {
                composeRule.onNodeWithTag(SEARCH_PREVIEW_TAG).assertIsDisplayed()
                composeRule.onNodeWithTag(SEARCH_NARROWING_TAG).assertIsDisplayed()
            }

            widthDp < 600 -> {
                composeRule.onAllNodesWithTag(SEARCH_PREVIEW_TAG).assertCountEquals(0)
            }
        }
        capture("layout-${widthDp}dp")
    }

    @Test
    fun bookPostureKeepsResultsAndPreviewOnSeparateSidesOfTheHinge() {
        assumeTrue(composeRule.activity.resources.configuration.screenWidthDp >= 600)
        seedLakeContent()
        showAdaptiveSearch()
        waitForAdaptiveResults()

        composeRule.activityRule.scenario.onActivity(postureSupport::publishBookPosture)
        composeRule.waitForIdle()

        val results = composeRule.onNodeWithTag(ADAPTIVE_SEARCH_RESULTS_TAG).fetchSemanticsNode().boundsInRoot
        val preview = composeRule.onNodeWithTag(SEARCH_PREVIEW_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(results.right <= preview.left || preview.right <= results.left, "results $results overlap preview $preview")
        capture("book")
    }

    @Test
    fun tabletopPostureStacksThePreviewAboveTheResults() {
        assumeTrue(composeRule.activity.resources.configuration.screenWidthDp >= 600)
        seedLakeContent()
        showAdaptiveSearch()
        waitForAdaptiveResults()

        composeRule.activityRule.scenario.onActivity(postureSupport::publishTabletopPosture)
        composeRule.waitForIdle()

        val results = composeRule.onNodeWithTag(ADAPTIVE_SEARCH_RESULTS_TAG).fetchSemanticsNode().boundsInRoot
        val preview = composeRule.onNodeWithTag(SEARCH_PREVIEW_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(preview.bottom <= results.top, "preview $preview is not above results $results")
        capture("tabletop")
    }

    @Test
    fun theChosenResultSurvivesRestoration() {
        assumeTrue(composeRule.activity.resources.configuration.screenWidthDp >= 840)
        seedLakeContent()
        val tester = StateRestorationTester(composeRule)
        tester.setContent { AdaptiveSearchContent() }
        waitForAdaptiveResults()

        composeRule.onAllNodesWithText("Paddled out on the lake, trip 0", substring = true)[0].performClick()
        composeRule.waitForIdle()
        val chosen = titleOfPreview()

        tester.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        assertEquals(chosen, titleOfPreview())
    }

    @Test
    fun aPlacePreviewShowsItsMapAndNarrowsSearchToThePlace() {
        assumeTrue(composeRule.activity.resources.configuration.screenWidthDp >= 840)
        val place = Uuid.random()
        val result = SearchResult(place, "Lake Mead", Clock.System.now(), SearchContentType.PLACE)
        val key = SearchResultKey(SearchContentType.PLACE, place)
        var narrowedTo: Uuid? = null
        composeRule.setContent {
            MaterialTheme {
                AdaptiveSearchBody(
                    results = listOf(result),
                    details = mapOf(key to SearchResultDetail.Place(latitude = 36.0, longitude = -114.7)),
                    narrowing = SearchNarrowingOptions(),
                    actions =
                        AdaptiveSearchActions(
                            onResultClick = {},
                            onResultLongClick = {},
                            onOpenDay = {},
                            onToggleType = {},
                            onTogglePerson = {},
                            onTogglePlace = { narrowedTo = it },
                            onToggleYear = {},
                            onClearNarrowing = {},
                        ),
                    knownPreviews =
                        mapOf(
                            key to
                                SearchResultPreview.Place(
                                    activity = SearchEntryActivity(3, 3, Clock.System.now(), Clock.System.now(), mapOf(2026 to 3)),
                                    recentEntries = emptyList(),
                                ),
                        ),
                    placeMap = { name, latitude, longitude, modifier -> PlaceLocationMap(name, latitude, longitude, modifier) },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SEARCH_PREVIEW_ACTIVITY_TAG).assertIsDisplayed()
        capture("place-preview")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val nativeMap = device.wait(Until.hasObject(By.descContains("Google")), 5_000)
        val drawnMap = composeRule.onAllNodesWithText("Locations Visited").fetchSemanticsNodes().isNotEmpty()
        assertTrue(nativeMap || drawnMap, "The place preview showed neither the native map with attribution nor the drawn preview")

        composeRule.onNodeWithTag(SEARCH_PREVIEW_NARROW_TAG).performClick()
        assertEquals(place, narrowedTo)
    }

    private fun titleOfPreview(): String =
        composeRule
            .onNodeWithTag(SEARCH_PREVIEW_TITLE_TAG)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString { it.text }

    private fun seedLakeContent() {
        val now = Clock.System.now()
        val place = Uuid.random()
        runBlocking {
            database.placeDao().insert(
                PlaceEntity(id = place, name = "Lake Mead", latitude = 36.0, longitude = -114.7, created = now, lastUpdated = now),
            )
            database.journalDao().create(
                JournalEntity(title = "Lake Days", description = "Summers on the water", created = now, lastUpdated = now),
            )
            repeat(3) { index ->
                database.textNoteDao().addNote(
                    TextNoteEntity(
                        content = "Paddled out on the lake, trip $index",
                        uid = Uuid.random(),
                        created = now - (index + 1).days,
                        lastUpdated = now,
                        placeId = place,
                    ),
                )
            }
        }
    }

    private val adaptiveViewModel: SearchViewModel by lazy {
        val repository =
            OfflineFirstSearchRepository(
                searchDao = database.searchDao(),
                personDao = database.personDao(),
                featureFlags = DataStoreFeatureFlagStore(TestPreferencesDataStore()),
            )
        SearchViewModel(
            universalSearchUseCase = UniversalSearchUseCase(repository),
            observeRecentSearchesUseCase = ObserveRecentSearchesUseCase(InMemoryRecentSearchesRepository()),
            searchResultDetailsRepository =
                OfflineFirstSearchResultDetailsRepository(
                    detailsDao = database.searchDetailsDao(),
                    personDao = database.personDao(),
                    rewinds = RewindLookup { null },
                ),
            observeSearchFacetsUseCase = ObserveSearchFacetsUseCase(repository),
        )
    }

    @Composable
    private fun AdaptiveSearchContent() {
        MaterialTheme {
            SearchScreen(
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
                adaptive = true,
                placeMap = { name, latitude, longitude, modifier -> PlaceLocationMap(name, latitude, longitude, modifier) },
                viewModel = adaptiveViewModel,
            )
        }
    }

    private fun showAdaptiveSearch() {
        composeRule.setContent { AdaptiveSearchContent() }
    }

    private fun waitForAdaptiveResults() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("search_screen_input").performClick()
        composeRule.onNodeWithTag("search_screen_input").performTextInput("lake")
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithTag(ADAPTIVE_SEARCH_RESULTS_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        Espresso.closeSoftKeyboard()
        composeRule.waitForIdle()
    }

    private fun capture(name: String) {
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        val output = File(directory, "search-adaptive").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(output, "$name.png")))
    }

    // endregion
}

private class InMemoryRecentSearchesRepository : RecentSearchesRepository {
    private val recentSearches = MutableStateFlow<List<String>>(emptyList())

    override fun observeRecentSearches(): Flow<List<String>> = recentSearches

    override suspend fun addRecentSearch(query: String) {
        recentSearches.value = listOf(query) + recentSearches.value.filterNot { it == query }
    }

    override suspend fun removeRecentSearch(query: String) {
        recentSearches.value = recentSearches.value.filterNot { it == query }
    }

    override suspend fun clearRecentSearches() {
        recentSearches.value = emptyList()
    }
}

/**
 * A simple in-memory implementation of [DataStore] for testing.
 *
 * This allows tests to observe and update [Preferences] without requiring
 * disk I/O, facilitating fast and isolated tests.
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
