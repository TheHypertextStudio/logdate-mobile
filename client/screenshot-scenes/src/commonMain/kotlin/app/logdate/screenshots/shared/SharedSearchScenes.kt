@file:Suppress("ktlint:standard:function-naming")

package app.logdate.screenshots.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.client.repository.search.key
import app.logdate.feature.search.ui.AdaptiveSearchActions
import app.logdate.feature.search.ui.AdaptiveSearchBody
import app.logdate.feature.search.ui.AdaptiveSearchResults
import app.logdate.feature.search.ui.NarrowingOption
import app.logdate.feature.search.ui.NarrowingYear
import app.logdate.feature.search.ui.SearchNarrowing
import app.logdate.feature.search.ui.SearchNarrowingLayout
import app.logdate.feature.search.ui.SearchNarrowingOptions
import app.logdate.feature.search.ui.SearchScreenContent
import app.logdate.feature.search.ui.SearchScreenState
import app.logdate.feature.search.ui.groupSearchResults
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

internal val catalogSearchScenes: List<SharedScreenshotSceneSpec> =
    listOf(
        sharedScene(SharedScreenshotSceneId.SearchIdle, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            SearchScreenContent(
                searchState =
                    SearchScreenState.Idle(
                        recentSearches =
                            listOf(
                                "sunrise trail",
                                "voice memo",
                                "budget review",
                            ),
                    ),
                queryText = "",
                initialQuery = "",
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.SearchSearching, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            SearchScreenContent(
                searchState = SearchScreenState.Searching(query = SEARCH_QUERY),
                queryText = SEARCH_QUERY,
                initialQuery = SEARCH_QUERY,
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.SearchEmpty, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            SearchScreenContent(
                searchState = SearchScreenState.Empty(query = SEARCH_QUERY),
                queryText = SEARCH_QUERY,
                initialQuery = SEARCH_QUERY,
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.SearchResults, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            SearchScreenContent(
                searchState = SearchScreenState.Results(query = SEARCH_QUERY, results = searchResults),
                queryText = SEARCH_QUERY,
                initialQuery = SEARCH_QUERY,
                onQueryChange = {},
                onCommitSearch = {},
                onResultClick = {},
                onResultOpenDay = {},
                onGoBack = {},
            )
        },
        sharedScene(SharedScreenshotSceneId.SearchResultsAdaptive, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            Surface(modifier = Modifier.fillMaxSize()) {
                AdaptiveSearchResults(
                    groups = groupSearchResults(adaptiveSearchResults, TimeZone.UTC),
                    details = adaptiveSearchDetails,
                    onResultClick = {},
                    onResultLongClick = {},
                    onOpenDay = {},
                )
            }
        },
        sharedScene(SharedScreenshotSceneId.SearchNarrowingPanel, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            Surface(modifier = Modifier.fillMaxSize()) {
                SearchNarrowing(
                    options = narrowingOptions,
                    layout = SearchNarrowingLayout.Panel,
                    onToggleType = {},
                    onTogglePerson = {},
                    onTogglePlace = {},
                    onToggleYear = {},
                    onClear = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        sharedScene(SharedScreenshotSceneId.SearchNarrowingRow, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            Surface(modifier = Modifier.fillMaxSize()) {
                SearchNarrowing(
                    options = narrowingOptions,
                    layout = SearchNarrowingLayout.Row,
                    onToggleType = {},
                    onTogglePerson = {},
                    onTogglePlace = {},
                    onToggleYear = {},
                    onClear = {},
                )
            }
        },
        sharedScene(SharedScreenshotSceneId.SearchAdaptiveLayout, ScreenshotSceneGroup.SEARCH, standardMatrixVariants) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
                AdaptiveSearchBody(
                    results = adaptiveSearchResults,
                    details = adaptiveSearchDetails,
                    narrowing = narrowingOptions,
                    actions =
                        AdaptiveSearchActions(
                            onResultClick = {},
                            onResultLongClick = {},
                            onOpenDay = {},
                            onToggleType = {},
                            onTogglePerson = {},
                            onTogglePlace = {},
                            onToggleYear = {},
                            onClearNarrowing = {},
                        ),
                )
            }
        },
    )

private val adaptiveSearchResults: List<SearchResult> =
    listOf(
        searchResult(
            "00000000-0000-0000-0000-000000000071",
            "[Lake] Days Trips and afternoons on the water",
            baseInstant - 600.days,
            SearchContentType.JOURNAL,
        ),
        searchResult("00000000-0000-0000-0000-000000000072", "Summer 2025", baseInstant - 30.days, SearchContentType.REWIND),
        searchResult("00000000-0000-0000-0000-000000000073", "Postcard to Dad", baseInstant - 90.days, SearchContentType.POSTCARD),
        searchResult(
            "00000000-0000-0000-0000-000000000074",
            "Packed the kayak and drove out to the [lake] before it got hot.",
            baseInstant - 3.hours,
            SearchContentType.TEXT_NOTE,
        ),
        searchResult(
            "00000000-0000-0000-0000-000000000075",
            "The water was so clear we could see the anchor from the boat, the [lake] is low again.",
            baseInstant - 1.hours,
            SearchContentType.TRANSCRIPTION,
        ),
        searchResult(
            "00000000-0000-0000-0000-000000000076",
            "Kayak pulled up on the gravel at the [lake]",
            baseInstant - 2.hours,
            SearchContentType.MEDIA_CAPTION,
        ),
        searchResult(
            "00000000-0000-0000-0000-000000000077",
            "First [lake] day of the year. Cold, empty, perfect.",
            baseInstant - 40.days,
            SearchContentType.TEXT_NOTE,
        ),
    )

private val adaptiveSearchDetails: Map<app.logdate.client.repository.search.SearchResultKey, SearchResultDetail> =
    mapOf(
        adaptiveSearchResults[0].key to SearchResultDetail.Journal(coverUri = null, entryCount = 14),
        adaptiveSearchResults[1].key to
            SearchResultDetail.Rewind(start = baseInstant - 120.days, end = baseInstant - 30.days, coverUri = null),
        adaptiveSearchResults[2].key to SearchResultDetail.Postcard(coverUri = null, modifiedAt = baseInstant - 90.days),
        adaptiveSearchResults[4].key to SearchResultDetail.Audio(durationMs = 48_000),
        adaptiveSearchResults[5].key to SearchResultDetail.Image(uri = "file:///sample-photo.jpg"),
    )

private fun searchResult(
    id: String,
    content: String,
    created: kotlin.time.Instant,
    type: SearchContentType,
) = SearchResult(uid = Uuid.parse(id), content = content, created = created, contentType = type)

private val narrowingOptions =
    SearchNarrowingOptions(
        types =
            listOf(
                NarrowingOption(SearchContentType.TEXT_NOTE, null, 31, false),
                NarrowingOption(SearchContentType.TRANSCRIPTION, null, 6, false),
                NarrowingOption(SearchContentType.MEDIA_CAPTION, null, 14, false),
                NarrowingOption(SearchContentType.JOURNAL, null, 2, true),
                NarrowingOption(SearchContentType.REWIND, null, 3, false),
            ),
        people =
            listOf(
                NarrowingOption(Uuid.parse("00000000-0000-0000-0000-000000000081"), "Sam", 23, false),
                NarrowingOption(Uuid.parse("00000000-0000-0000-0000-000000000082"), "Maya", 9, false),
                NarrowingOption(Uuid.parse("00000000-0000-0000-0000-000000000083"), "Priya", 4, false),
            ),
        places =
            listOf(
                NarrowingOption(Uuid.parse("00000000-0000-0000-0000-000000000091"), "Lake Mead", 29, true),
                NarrowingOption(Uuid.parse("00000000-0000-0000-0000-000000000092"), "Las Vegas", 12, false),
                NarrowingOption(Uuid.parse("00000000-0000-0000-0000-000000000093"), "Chicago", 5, false),
            ),
        years =
            listOf(
                NarrowingYear(2022, List(12) { if (it == 4) 2 else 0 }, selected = false),
                NarrowingYear(2023, List(12) { if (it == 7) 5 else 0 }, selected = false),
                NarrowingYear(
                    2024,
                    List(12) {
                        if (it == 5) {
                            3
                        } else if (it == 7) {
                            1
                        } else {
                            0
                        }
                    },
                    selected = false,
                ),
                NarrowingYear(
                    2025,
                    List(12) {
                        if (it in 6..7) {
                            9 - it
                        } else if (it == 2) {
                            2
                        } else {
                            0
                        }
                    },
                    selected = true,
                ),
                NarrowingYear(2026, List(12) { if (it == 3) 4 else 0 }, selected = false),
            ),
    )
