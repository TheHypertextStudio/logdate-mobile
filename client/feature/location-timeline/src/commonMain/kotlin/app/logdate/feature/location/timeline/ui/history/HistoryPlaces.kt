@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.theme.Spacing
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_empty_places
import logdate.client.feature.location.timeline.generated.resources.history_empty_places_body
import logdate.client.feature.location.timeline.generated.resources.history_filter
import logdate.client.feature.location.timeline.generated.resources.history_no_matching_places
import logdate.client.feature.location.timeline.generated.resources.history_no_matching_places_body
import org.jetbrains.compose.resources.stringResource

@Composable
private fun HistoryPlaceSearch(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
) {
    var rangeMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                FilterChip(
                    selected = true,
                    onClick = {
                        if (actions.onPlacesRangePreset != null) {
                            rangeMenu = true
                        } else {
                            actions.onPlacesFilter()
                        }
                    },
                    label = {
                        Text(
                            state.placesFilterLabel?.asString()?.takeIf { it.isNotBlank() }
                                ?: stringResource(Res.string.history_filter),
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = rangeMenu, onDismissRequest = { rangeMenu = false }) {
                    PlacesRangePreset.entries.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(preset.labelRes)) },
                            onClick = {
                                rangeMenu = false
                                actions.onPlacesRangePreset?.invoke(preset)
                            },
                        )
                    }
                }
            }
            if (actions.onPlacesSort != null) {
                Box {
                    FilterChip(
                        selected = state.placesSort != PlacesSortOption.Recent,
                        onClick = { sortMenu = true },
                        label = { Text(stringResource(state.placesSort.labelRes)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(18.dp)) },
                    )
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        PlacesSortOption.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(stringResource(sort.labelRes)) },
                                onClick = {
                                    sortMenu = false
                                    actions.onPlacesSort.invoke(sort)
                                },
                            )
                        }
                    }
                }
            }
        }
        val availableCategories =
            remember(state.places) {
                val present = state.places.mapNotNull { it.category?.uppercase() }.toSet()
                EDIT_CATEGORY_PRESETS.filter { it.key in present }
            }
        if (availableCategories.isNotEmpty() && actions.onPlacesCategoryFilter != null) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availableCategories.forEach { preset ->
                    val selected = state.placesCategoryFilter.equals(preset.key, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { actions.onPlacesCategoryFilter.invoke(if (selected) null else preset.key) },
                        leadingIcon = { Icon(categoryIcon(preset.key), null, Modifier.size(16.dp)) },
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryPlaceRow(
    place: HistoryPlaceUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val image = place.memories.firstOrNull { it.thumbnailUri != null }
            if (image?.thumbnailUri != null) {
                HistoryMemoryThumbnail(image.thumbnailUri, image.kind == HistoryMemoryKind.Video, 48.dp)
            } else {
                Surface(Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(categoryIcon(place.category), null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(place.title.asString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    place.supportingText.asString(),
                    Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                place.memories.firstOrNull()?.let {
                    Text(
                        it.title.asString(),
                        Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun HistoryPlacesList(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy
            .rememberLazyListState(),
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        item(contentType = "header") { header() }
        item(contentType = "search") { HistoryPlaceSearch(state, actions) }
        val places = state.filteredPlaces()
        if (places.isEmpty()) {
            item(contentType = "empty") {
                val noResults = state.placesQuery.isNotBlank() || state.placesCategoryFilter != null
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            if (noResults) Res.string.history_no_matching_places else Res.string.history_empty_places,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(
                            if (noResults) Res.string.history_no_matching_places_body else Res.string.history_empty_places_body,
                        ),
                    )
                }
            }
        }
        items(places, key = { it.id }, contentType = { "place" }) { place ->
            HistoryPlaceRow(place, Modifier.animateItem()) { actions.onOpenPlace(place.id) }
        }
    }
}
