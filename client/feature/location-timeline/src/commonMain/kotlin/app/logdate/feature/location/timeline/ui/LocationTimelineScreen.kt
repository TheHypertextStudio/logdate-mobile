@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalSharedTransitionApi::class)

package app.logdate.feature.location.timeline.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.domain.location.LocationMemoryTimeFilter
import app.logdate.client.permissions.rememberLocationPermissionState
import app.logdate.client.text.asString
import app.logdate.feature.location.timeline.ui.model.CurrentLocationUiModel
import app.logdate.feature.location.timeline.ui.model.LocationMemoryPreviewUiModel
import app.logdate.feature.location.timeline.ui.model.LocationPlaceUiModel
import app.logdate.feature.location.timeline.ui.model.LocationStopUiModel
import app.logdate.feature.location.timeline.ui.model.LocationTimelineErrorUiState
import app.logdate.feature.location.timeline.ui.model.LocationTimelineUiState
import app.logdate.ui.LocalNavAnimatedVisibilityScope
import app.logdate.ui.LocalSharedTransitionScope
import app.logdate.ui.adaptive.AdaptivePaneLayout
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.transitions.TransitionKeys
import app.logdate.ui.platform.PlatformSheet
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.all_time
import logdate.client.feature.location.timeline.generated.resources.current_location
import logdate.client.feature.location.timeline.generated.resources.custom_range
import logdate.client.feature.location.timeline.generated.resources.delete_location
import logdate.client.feature.location.timeline.generated.resources.delete_location_confirmation
import logdate.client.feature.location.timeline.generated.resources.enable
import logdate.client.feature.location.timeline.generated.resources.last_30_days
import logdate.client.feature.location.timeline.generated.resources.last_90_days
import logdate.client.feature.location.timeline.generated.resources.last_visited_label
import logdate.client.feature.location.timeline.generated.resources.load_more_places
import logdate.client.feature.location.timeline.generated.resources.location_permission_required
import logdate.client.feature.location.timeline.generated.resources.location_permission_required_description
import logdate.client.feature.location.timeline.generated.resources.location_services_disabled
import logdate.client.feature.location.timeline.generated.resources.location_services_disabled_description
import logdate.client.feature.location.timeline.generated.resources.location_timeline
import logdate.client.feature.location.timeline.generated.resources.location_timeline_empty_description
import logdate.client.feature.location.timeline.generated.resources.locations_temporarily_unavailable_description
import logdate.client.feature.location.timeline.generated.resources.memories_count
import logdate.client.feature.location.timeline.generated.resources.no_location_history_yet
import logdate.client.feature.location.timeline.generated.resources.no_memories_for_this_range
import logdate.client.feature.location.timeline.generated.resources.pinned_memory
import logdate.client.feature.location.timeline.generated.resources.recent_memories
import logdate.client.feature.location.timeline.generated.resources.recent_stays
import logdate.client.feature.location.timeline.generated.resources.samples_count
import logdate.client.feature.location.timeline.generated.resources.unable_to_load_location_timeline
import logdate.client.feature.location.timeline.generated.resources.view_note
import logdate.client.feature.location.timeline.generated.resources.year_to_date
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_try_again
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegacyLocationTimelineScreen(
    modifier: Modifier = Modifier,
    onOpenNote: (Uuid) -> Unit = {},
    viewModel: LocationTimelineViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(Res.string.location_timeline))
                },
            )
        },
        containerColor = Color.Transparent,
        modifier = modifier,
    ) { paddingValues ->
        LocationTimelineContent(
            uiState = uiState,
            onSelectPlace = viewModel::selectPlace,
            onDismissPlaceDetail = viewModel::dismissPlaceDetail,
            onDeleteStop = viewModel::deleteStop,
            onSelectFilter = { filter -> viewModel.selectFilter(filter) },
            onLoadMorePlaces = viewModel::loadMorePlaces,
            onRetry = viewModel::retry,
            onOpenNote = onOpenNote,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        )
    }
}

@Composable
fun LocationTimelineContent(
    uiState: LocationTimelineUiState,
    onSelectPlace: (String) -> Unit,
    onDismissPlaceDetail: () -> Unit,
    onDeleteStop: (String) -> Unit,
    onSelectFilter: (LocationMemoryTimeFilter) -> Unit,
    onLoadMorePlaces: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenNote: (Uuid) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        is LocationTimelineUiState.Error -> {
            ErrorState(error = uiState.error, onRetry = onRetry, modifier = modifier)
        }

        is LocationTimelineUiState.Content -> {
            ContentState(
                uiState = uiState,
                onSelectPlace = onSelectPlace,
                onDismissPlaceDetail = onDismissPlaceDetail,
                onDeleteStop = onDeleteStop,
                onSelectFilter = onSelectFilter,
                onLoadMorePlaces = onLoadMorePlaces,
                onOpenNote = onOpenNote,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ErrorState(
    error: LocationTimelineErrorUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        LocationTimelineErrorCard(
            error = error,
            onRetry = onRetry,
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp),
        )
    }
}

@Composable
internal fun LocationTimelineErrorCard(
    error: LocationTimelineErrorUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionState = rememberLocationPermissionState()

    LaunchedEffect(error, permissionState.hasPermission) {
        if (error == LocationTimelineErrorUiState.PermissionRequired && permissionState.hasPermission) {
            onRetry()
        }
    }

    val title: String
    val description: String
    val actionLabel: String
    val action: () -> Unit

    when (error) {
        LocationTimelineErrorUiState.PermissionRequired -> {
            title = stringResource(Res.string.location_permission_required)
            description = stringResource(Res.string.location_permission_required_description)
            actionLabel = stringResource(Res.string.enable)
            action = permissionState.requestPermission
        }

        LocationTimelineErrorUiState.LocationServicesDisabled -> {
            title = stringResource(Res.string.location_services_disabled)
            description = stringResource(Res.string.location_services_disabled_description)
            actionLabel = stringResource(UiRes.string.common_try_again)
            action = onRetry
        }

        LocationTimelineErrorUiState.TemporarilyUnavailable -> {
            title = stringResource(Res.string.unable_to_load_location_timeline)
            description = stringResource(Res.string.locations_temporarily_unavailable_description)
            actionLabel = stringResource(UiRes.string.common_try_again)
            action = onRetry
        }
    }

    Card(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = action) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ContentState(
    uiState: LocationTimelineUiState.Content,
    onSelectPlace: (String) -> Unit,
    onDismissPlaceDetail: () -> Unit,
    onDeleteStop: (String) -> Unit,
    onSelectFilter: (LocationMemoryTimeFilter) -> Unit,
    onLoadMorePlaces: () -> Unit,
    onOpenNote: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.isFullyLoaded && uiState.visiblePlaces.isEmpty() && uiState.recentStops.isEmpty() && uiState.currentLocation == null) {
        EmptyLocationTimeline(modifier = modifier)
        return
    }

    val listState = rememberLazyListState()
    val hasMapData = uiState.currentLocation != null || uiState.visiblePlaces.isNotEmpty()

    @Composable
    fun MapOrPlaceholder(modifier: Modifier) {
        if (hasMapData) {
            LocationTimelineMap(
                places = uiState.visiblePlaces,
                currentLocation = uiState.currentLocation,
                selectedPlaceId = uiState.selectedPlaceId,
                onSelectPlace = onSelectPlace,
                modifier = modifier,
            )
        } else {
            MapPlaceholder(modifier = modifier)
        }
    }

    AdaptivePaneLayout(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        supportingPaneBreakpoint = 0.dp,
        supportingPaneWidth = 380.dp,
        supportingPaneMaxWidth = 440.dp,
        paneSpacing = 12.dp,
        contentPadding = PaddingValues(0.dp),
        mainPaneMinWidth = 320.dp,
        supportingPane = { _ ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxSize().padding(top = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                PlacesAndHistoryList(
                    uiState = uiState,
                    onSelectPlace = onSelectPlace,
                    onDeleteStop = onDeleteStop,
                    onSelectFilter = onSelectFilter,
                    onLoadMorePlaces = onLoadMorePlaces,
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        },
        mainPane = { layoutInfo ->
            if (layoutInfo.showSupportingPane) {
                MapOrPlaceholder(
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                )
            } else {
                val expandedMapHeight = 320.dp
                val collapsedMapHeight = 148.dp
                val collapseRange = expandedMapHeight - collapsedMapHeight
                val collapseRangePx = with(LocalDensity.current) { collapseRange.toPx() }
                val mapHeightTarget by remember(listState, collapseRange, collapseRangePx) {
                    derivedStateOf {
                        val collapseFraction =
                            when {
                                listState.firstVisibleItemIndex > 0 -> {
                                    1f
                                }

                                collapseRangePx <= 0f -> {
                                    0f
                                }

                                else -> {
                                    (listState.firstVisibleItemScrollOffset / collapseRangePx).coerceIn(0f, 1f)
                                }
                            }
                        expandedMapHeight - (collapseRange * collapseFraction)
                    }
                }
                val animatedMapHeight by animateDpAsState(
                    targetValue = mapHeightTarget,
                    animationSpec =
                        spring(
                            dampingRatio = 0.92f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    label = "LocationTimelineMapHeight",
                )
                Column(modifier = Modifier.fillMaxSize()) {
                    MapOrPlaceholder(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(animatedMapHeight)
                                .padding(16.dp),
                    )
                    HorizontalDivider()
                    PlacesAndHistoryList(
                        uiState = uiState,
                        onSelectPlace = onSelectPlace,
                        onDeleteStop = onDeleteStop,
                        onSelectFilter = onSelectFilter,
                        onLoadMorePlaces = onLoadMorePlaces,
                        listState = listState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        },
    )

    uiState.selectedPlace?.let { place ->
        LocationPlaceDetailSheet(
            place = place,
            onDismissRequest = onDismissPlaceDetail,
            onOpenNote = onOpenNote,
        )
    }
}

@Composable
private fun PlacesAndHistoryList(
    uiState: LocationTimelineUiState.Content,
    onSelectPlace: (String) -> Unit,
    onDeleteStop: (String) -> Unit,
    onSelectFilter: (LocationMemoryTimeFilter) -> Unit,
    onLoadMorePlaces: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PlacesFilterRow(
                selectedFilter = uiState.selectedFilter,
                onSelectFilter = onSelectFilter,
            )
        }

        // Current location section
        item {
            AnimatedVisibility(
                visible = !uiState.isLoadingCurrentLocation && uiState.currentLocation != null,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                uiState.currentLocation?.let { currentLocation ->
                    CurrentLocationCard(currentLocation = currentLocation)
                }
            }
            AnimatedVisibility(
                visible = uiState.isLoadingCurrentLocation,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(150)),
            ) {
                CurrentLocationPlaceholder()
            }
        }

        item {
            SectionHeader(
                title = stringResource(Res.string.recent_memories),
                subtitle = stringResource(Res.string.memories_count, uiState.places.sumOf(LocationPlaceUiModel::memoryCount)),
            )
        }

        // Places section
        if (uiState.isLoadingPlaces && uiState.visiblePlaces.isEmpty()) {
            items(3) {
                PlacePlaceholder()
            }
        }

        if (!uiState.isLoadingPlaces && uiState.visiblePlaces.isEmpty()) {
            item {
                Card {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.no_memories_for_this_range),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(Res.string.location_timeline_empty_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(
            items = uiState.visiblePlaces,
            key = { it.id },
        ) { place ->
            LocationPlaceCard(
                place = place,
                selected = place.id == uiState.selectedPlaceId,
                onSelect = { onSelectPlace(place.id) },
            )
        }

        if (uiState.canLoadMorePlaces) {
            item {
                TextButton(
                    onClick = onLoadMorePlaces,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.load_more_places))
                }
            }
        }

        // Stops section
        if (uiState.isLoadingStops && uiState.recentStops.isEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(Res.string.recent_stays),
                    subtitle = "",
                )
            }
            items(2) {
                StopPlaceholder()
            }
        }

        if (uiState.recentStops.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(Res.string.recent_stays),
                    subtitle = stringResource(Res.string.samples_count, uiState.recentStops.sumOf(LocationStopUiModel::sampleCount)),
                )
            }

            items(
                items = uiState.recentStops,
                key = { it.id },
            ) { stop ->
                StopCard(
                    stop = stop,
                    onDelete = { onDeleteStop(stop.id) },
                )
            }
        }
    }
}

@Composable
private fun PlacesFilterRow(
    selectedFilter: LocationMemoryTimeFilter,
    onSelectFilter: (LocationMemoryTimeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (filter in LocationMemoryTimeFilter.Presets) {
            FilterChip(
                selected = filter == selectedFilter,
                onClick = { onSelectFilter(filter) },
                label = { Text(FilterLabel(filter)) },
            )
        }
    }
}

@Composable
private fun FilterLabel(filter: LocationMemoryTimeFilter): String =
    when (filter) {
        is LocationMemoryTimeFilter.Last30Days -> stringResource(Res.string.last_30_days)
        is LocationMemoryTimeFilter.Last90Days -> stringResource(Res.string.last_90_days)
        is LocationMemoryTimeFilter.YearToDate -> stringResource(Res.string.year_to_date)
        is LocationMemoryTimeFilter.AllTime -> stringResource(Res.string.all_time)
        is LocationMemoryTimeFilter.Custom -> stringResource(Res.string.custom_range)
    }

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CurrentLocationCard(
    currentLocation: CurrentLocationUiModel,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(Res.string.current_location),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = currentLocation.title.asString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            currentLocation.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LocationPlaceCard(
    place: LocationPlaceUiModel,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onSelect,
        modifier = modifier,
        border =
            if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = place.title.asString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = place.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.memories_count, place.memoryCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.last_visited_label, place.lastVisitedLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            place.memories.firstOrNull()?.let { memory ->
                Text(
                    text = memory.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StopCard(
    stop: LocationStopUiModel,
    onDelete: () -> Unit,
) {
    var showActions by remember(stop.id) { mutableStateOf(false) }
    var showDeleteConfirmation by remember(stop.id) { mutableStateOf(false) }

    Card {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stop.title.asString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stop.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box {
                    IconButton(onClick = { showActions = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }

                    app.logdate.ui.common.DropdownMenu(
                        expanded = showActions,
                        onDismissRequest = { showActions = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(Res.string.delete_location)) },
                            onClick = {
                                showActions = false
                                showDeleteConfirmation = true
                            },
                        )
                    }
                }
            }

            Text(
                text = stop.timeRange,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stop.duration.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.samples_count, stop.sampleCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(Res.string.delete_location)) },
            text = { Text(stringResource(Res.string.delete_location_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(Res.string.delete_location))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(UiRes.string.common_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPlaceDetailSheet(
    place: LocationPlaceUiModel,
    onDismissRequest: () -> Unit,
    onOpenNote: (Uuid) -> Unit,
) {
    PlatformSheet(
        onDismissRequest = onDismissRequest,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = place.title.asString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = place.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(Res.string.memories_count, place.memoryCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(Res.string.last_visited_label, place.lastVisitedLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (place.memories.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(Res.string.recent_memories),
                        subtitle = stringResource(Res.string.memories_count, place.memoryCount),
                    )
                }

                items(
                    items = place.memories,
                    key = { it.noteId },
                ) { memory ->
                    LocationMemoryPreviewCard(
                        memory = memory,
                        onOpenNote = { onOpenNote(memory.noteId) },
                    )
                }
            }

            if (place.relatedStops.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(Res.string.recent_stays),
                        subtitle = stringResource(Res.string.samples_count, place.relatedStops.sumOf(LocationStopUiModel::sampleCount)),
                    )
                }

                items(
                    items = place.relatedStops,
                    key = { it.id },
                ) { stop ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stop.timeRange,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stop.duration.asString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationMemoryPreviewCard(
    memory: LocationMemoryPreviewUiModel,
    onOpenNote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sharedBoundsModifier = rememberLocationMemoryPreviewSharedBoundsModifier(memory.noteId)

    Card(
        onClick = onOpenNote,
        modifier = modifier.then(sharedBoundsModifier),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = memory.title.ifBlank { stringResource(Res.string.pinned_memory) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = memory.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onOpenNote,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(Res.string.view_note))
            }
        }
    }
}

@Composable
private fun rememberLocationMemoryPreviewSharedBoundsModifier(noteId: Uuid): Modifier {
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedVisibilityScope.current

    return if (sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                rememberSharedContentState(TransitionKeys.noteViewerTransition(noteId)),
                animatedVisibilityScope = animatedVisibilityScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            )
        }
    } else {
        Modifier
    }
}

@Composable
private fun EmptyLocationTimeline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(72.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = stringResource(Res.string.no_location_history_yet),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(Res.string.location_timeline_empty_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─── Skeleton Placeholders ──────────────────────────────────────────────────────

@Composable
private fun MapPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CurrentLocationPlaceholder(modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlaceholderLine(width = 96.dp)
            PlaceholderLine(width = 200.dp, height = 18.dp)
            PlaceholderLine(width = 160.dp)
        }
    }
}

@Composable
private fun PlacePlaceholder(modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlaceholderLine(width = 180.dp, height = 18.dp)
            PlaceholderLine(width = 240.dp)
            PlaceholderLine(width = 80.dp)
            PlaceholderLine(width = 120.dp)
        }
    }
}

@Composable
private fun StopPlaceholder(modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PlaceholderLine(width = 160.dp, height = 18.dp)
            PlaceholderLine(width = 200.dp)
            PlaceholderLine(width = 140.dp)
            PlaceholderLine(width = 100.dp)
        }
    }
}

@Composable
private fun PlaceholderLine(
    width: Dp,
    height: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .widthIn(max = width)
                .fillMaxWidth()
                .height(height),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
    ) {
        Spacer(Modifier)
    }
}
