package app.logdate.screenshots.flows.flow02_home_timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.awareness.daylight.DaylightPeriod
import app.logdate.client.domain.location.LocationMemoryTimeFilter
import app.logdate.client.text.toUiText
import app.logdate.feature.location.timeline.ui.LocationTimelineContent
import app.logdate.feature.location.timeline.ui.LocationTimelineQuickPeekSheet
import app.logdate.feature.location.timeline.ui.model.CurrentLocationUiModel
import app.logdate.feature.location.timeline.ui.model.LocationLabelSource
import app.logdate.feature.location.timeline.ui.model.LocationMemoryKind
import app.logdate.feature.location.timeline.ui.model.LocationMemoryPreviewUiModel
import app.logdate.feature.location.timeline.ui.model.LocationPlaceUiModel
import app.logdate.feature.location.timeline.ui.model.LocationStopUiModel
import app.logdate.feature.location.timeline.ui.model.LocationTimelineErrorUiState
import app.logdate.feature.location.timeline.ui.model.LocationTimelineUiState
import app.logdate.screenshots.common.HomeTabRouteFrame
import app.logdate.screenshots.common.RoutePreviewTab
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.ui.location.PlaceUiState
import app.logdate.ui.maps.LocalGoogleMapsAvailabilityOverride
import app.logdate.ui.timeline.MomentUiState
import app.logdate.ui.timeline.TextNoteUiState
import app.logdate.ui.timeline.TimelinePane
import app.logdate.ui.timeline.TimelineUiState
import app.logdate.ui.timeline.createSemanticTimelineDayUiState
import com.android.tools.screenshot.PreviewTest
import kotlinx.datetime.LocalDate
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.places_source_google_places
import logdate.client.feature.location.timeline.generated.resources.places_source_saved_place
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val sampleCurrentLocation =
    CurrentLocationUiModel(
        title = "Blue Bottle Coffee".toUiText(),
        subtitle = "123 Main St, San Francisco, CA",
        latitude = 37.7749,
        longitude = -122.4194,
    )

private val sampleStops =
    listOf(
        LocationStopUiModel(
            id = "1",
            title = "Golden Gate Park".toUiText(),
            subtitle = "501 Stanyan St, San Francisco, CA",
            latitude = 37.7749,
            longitude = -122.4194,
            startedAt = "2:15 PM",
            endedAt = "3:00 PM",
            timeRange = "2:15 PM - 3:00 PM",
            duration = "Stayed for 45m".toUiText(),
            sourceLabel = Res.string.places_source_google_places,
            source = LocationLabelSource.GOOGLE_PLACES,
            sampleCount = 8,
            startTime = Instant.fromEpochMilliseconds(1_740_000_000_000L),
            endTime = Instant.fromEpochMilliseconds(1_740_002_700_000L),
            hasReliableDuration = true,
        ),
        LocationStopUiModel(
            id = "2",
            title = "Home".toUiText(),
            subtitle = "456 Oak St, San Francisco, CA",
            latitude = 37.7694,
            longitude = -122.4862,
            startedAt = "10:30 AM",
            endedAt = "12:00 PM",
            timeRange = "10:30 AM - 12:00 PM",
            duration = "Stayed for 1h 30m".toUiText(),
            sourceLabel = Res.string.places_source_saved_place,
            source = LocationLabelSource.USER_DEFINED,
            sampleCount = 12,
            startTime = Instant.fromEpochMilliseconds(1_739_990_000_000L),
            endTime = Instant.fromEpochMilliseconds(1_739_995_400_000L),
            hasReliableDuration = true,
        ),
    )

private val samplePlaces =
    listOf(
        LocationPlaceUiModel(
            id = "place-1",
            title = "Golden Gate Park".toUiText(),
            subtitle = "2 memories · San Francisco, CA",
            latitude = 37.7749,
            longitude = -122.4194,
            lastVisitedLabel = "Visited today",
            memoryCount = 2,
            sourceLabel = Res.string.places_source_google_places,
            source = LocationLabelSource.GOOGLE_PLACES,
            memories =
                listOf(
                    LocationMemoryPreviewUiModel(
                        noteId = Uuid.parse("00000000-0000-0000-0000-000000000081"),
                        title = "Afternoon walk",
                        subtitle = "Text memory",
                        timestamp = Instant.fromEpochMilliseconds(1_740_002_700_000L),
                        latitude = 37.7749,
                        longitude = -122.4194,
                        kind = LocationMemoryKind.TEXT,
                    ),
                    LocationMemoryPreviewUiModel(
                        noteId = Uuid.parse("00000000-0000-0000-0000-000000000082"),
                        title = "Park snapshot",
                        subtitle = "Photo memory",
                        timestamp = Instant.fromEpochMilliseconds(1_740_001_800_000L),
                        latitude = 37.7749,
                        longitude = -122.4194,
                        kind = LocationMemoryKind.PHOTO,
                    ),
                ),
            relatedStops = listOf(sampleStops.first()),
        ),
        LocationPlaceUiModel(
            id = "place-2",
            title = "Home".toUiText(),
            subtitle = "1 memory · San Francisco, CA",
            latitude = 37.7694,
            longitude = -122.4862,
            lastVisitedLabel = "Visited yesterday",
            memoryCount = 1,
            sourceLabel = Res.string.places_source_saved_place,
            source = LocationLabelSource.USER_DEFINED,
            memories =
                listOf(
                    LocationMemoryPreviewUiModel(
                        noteId = Uuid.parse("00000000-0000-0000-0000-000000000083"),
                        title = "Quiet evening",
                        subtitle = "Audio memory",
                        timestamp = Instant.fromEpochMilliseconds(1_739_995_400_000L),
                        latitude = 37.7694,
                        longitude = -122.4862,
                        kind = LocationMemoryKind.AUDIO,
                    ),
                ),
            relatedStops = listOf(sampleStops.last()),
        ),
    )

private val sampleLocationSelectedState =
    LocationTimelineUiState.Content(
        isLoadingCurrentLocation = false,
        isLoadingPlaces = false,
        isLoadingStops = false,
        currentLocation = sampleCurrentLocation,
        selectedFilter = LocationMemoryTimeFilter.Last30Days,
        places = samplePlaces,
        visiblePlaces = samplePlaces,
        recentStops = sampleStops,
        selectedPlaceId = samplePlaces.last().id,
    )

private val sampleLocationEmptyState =
    LocationTimelineUiState.Content(
        isLoadingCurrentLocation = false,
        isLoadingPlaces = false,
        isLoadingStops = false,
        currentLocation = null,
        selectedFilter = LocationMemoryTimeFilter.Last30Days,
        places = emptyList(),
        visiblePlaces = emptyList(),
        recentStops = emptyList(),
    )

private val sampleLocationErrorState =
    LocationTimelineUiState.Error(
        LocationTimelineErrorUiState.TemporarilyUnavailable,
    )

private val quickPeekBlueBottle = PlaceUiState(id = "place-1", title = "Blue Bottle Coffee")
private val quickPeekDoloresPark = PlaceUiState(id = "place-2", title = "Dolores Park")
private val quickPeekHome = PlaceUiState(id = "place-3", title = "Home")

private val timelineQuickPeekDays =
    listOf(
        createSemanticTimelineDayUiState(
            summary = "Stopped by Blue Bottle before heading to Dolores Park and logging a few notes.",
            date = LocalDate(2025, 2, 20),
            moments =
                listOf(
                    MomentUiState(
                        id = "moment-2025-02-20-quick-peek",
                        label = "At Dolores Park",
                        timeOfDay = DaylightPeriod.AFTERNOON,
                        textSnippet = "Quick location-rich day: coffee, park, then home with just enough time to log the route.",
                        places = listOf(quickPeekBlueBottle, quickPeekDoloresPark),
                        isHero = true,
                    ),
                ),
            notes =
                listOf(
                    TextNoteUiState(
                        noteId = Uuid.parse("00000000-0000-0000-0000-000000000071"),
                        text = "Quick location-rich day: coffee, park, then home with just enough time to log the route.",
                        timestamp = Instant.parse("2025-02-20T15:00:00Z"),
                    ),
                ),
            placesVisited = listOf(quickPeekBlueBottle, quickPeekDoloresPark),
        ),
        createSemanticTimelineDayUiState(
            summary = "Wrapped up errands and ended the evening back at home.",
            date = LocalDate(2025, 2, 19),
            moments =
                listOf(
                    MomentUiState(
                        id = "moment-2025-02-19-quick-peek",
                        label = "At Home",
                        timeOfDay = DaylightPeriod.EVENING,
                        textSnippet = "Errands stayed compact, and the last stop of the day was home.",
                        places = listOf(quickPeekHome),
                        isHero = true,
                    ),
                ),
            notes =
                listOf(
                    TextNoteUiState(
                        noteId = Uuid.parse("00000000-0000-0000-0000-000000000072"),
                        text = "Errands stayed compact, and the last stop of the day was home.",
                        timestamp = Instant.parse("2025-02-19T20:00:00Z"),
                    ),
                ),
            placesVisited = listOf(quickPeekHome),
        ),
    )

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S01_LocationPopulated() {
    ScreenshotTheme {
        HomeTabRouteFrame(selectedTab = RoutePreviewTab.LOCATION) {
            LocationTimelineContent(
                uiState = sampleLocationSelectedState,
                onSelectPlace = {},
                onDismissPlaceDetail = {},
                onDeleteStop = {},
                onSelectFilter = {},
                onLoadMorePlaces = {},
            )
        }
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S02_LocationEmpty() {
    ScreenshotTheme {
        HomeTabRouteFrame(selectedTab = RoutePreviewTab.LOCATION) {
            LocationTimelineContent(
                uiState = sampleLocationEmptyState,
                onSelectPlace = {},
                onDismissPlaceDetail = {},
                onDeleteStop = {},
                onSelectFilter = {},
                onLoadMorePlaces = {},
            )
        }
    }
}

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun S03_LocationUnavailable() {
    ScreenshotTheme {
        HomeTabRouteFrame(selectedTab = RoutePreviewTab.LOCATION) {
            LocationTimelineContent(
                uiState = sampleLocationErrorState,
                onSelectPlace = {},
                onDismissPlaceDetail = {},
                onDeleteStop = {},
                onSelectFilter = {},
                onLoadMorePlaces = {},
            )
        }
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S04_TimelineLocationQuickPeek() {
    ScreenshotTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            HomeTabRouteFrame(selectedTab = RoutePreviewTab.TIMELINE) {
                TimelinePane(
                    uiState = TimelineUiState(items = timelineQuickPeekDays),
                    onNewEntry = {},
                    onShareMemory = { _ -> },
                    onOpenDay = {},
                )
            }

            LocationTimelineQuickPeekSheet(
                uiState = sampleLocationSelectedState,
                onDismissRequest = {},
                onOpenFullTimeline = {},
                onSelectPlace = {},
            )
        }
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S05_LocationQuickPeekEmpty() {
    ScreenshotTheme {
        LocationTimelineQuickPeekSheet(
            uiState = sampleLocationEmptyState,
            onDismissRequest = {},
            onOpenFullTimeline = {},
            onSelectPlace = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S06_LocationQuickPeekLoading() {
    ScreenshotTheme {
        LocationTimelineQuickPeekSheet(
            uiState = LocationTimelineUiState.Content(),
            onDismissRequest = {},
            onOpenFullTimeline = {},
            onSelectPlace = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S07_LocationQuickPeekPermissionRequired() {
    ScreenshotTheme {
        LocationTimelineQuickPeekSheet(
            uiState = LocationTimelineUiState.Error(LocationTimelineErrorUiState.PermissionRequired),
            onDismissRequest = {},
            onOpenFullTimeline = {},
            onSelectPlace = {},
        )
    }
}

@PreviewTest
@Preview(showBackground = true, device = PHONE)
@Composable
fun S08_LocationQuickPeekNoMapConfigured() {
    ScreenshotTheme {
        CompositionLocalProvider(LocalGoogleMapsAvailabilityOverride provides false) {
            LocationTimelineQuickPeekSheet(
                uiState = sampleLocationSelectedState,
                onDismissRequest = {},
                onOpenFullTimeline = {},
                onSelectPlace = {},
            )
        }
    }
}
