@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.logdate.client.text.UiText
import app.logdate.client.text.asString
import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import kotlinx.datetime.LocalDate
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_arrived_date
import logdate.client.feature.location.timeline.generated.resources.history_category_coffee
import logdate.client.feature.location.timeline.generated.resources.history_category_fitness
import logdate.client.feature.location.timeline.generated.resources.history_category_food
import logdate.client.feature.location.timeline.generated.resources.history_category_home
import logdate.client.feature.location.timeline.generated.resources.history_category_label
import logdate.client.feature.location.timeline.generated.resources.history_category_other
import logdate.client.feature.location.timeline.generated.resources.history_category_outdoors
import logdate.client.feature.location.timeline.generated.resources.history_category_work
import logdate.client.feature.location.timeline.generated.resources.history_distance_km_away
import logdate.client.feature.location.timeline.generated.resources.history_distance_m_away
import logdate.client.feature.location.timeline.generated.resources.history_hide_map_pin
import logdate.client.feature.location.timeline.generated.resources.history_left_date
import logdate.client.feature.location.timeline.generated.resources.history_looking_nearby
import logdate.client.feature.location.timeline.generated.resources.history_merge_next
import logdate.client.feature.location.timeline.generated.resources.history_merge_previous
import logdate.client.feature.location.timeline.generated.resources.history_nearby_suggestions_heading
import logdate.client.feature.location.timeline.generated.resources.history_no_nearby_matches
import logdate.client.feature.location.timeline.generated.resources.history_place_boundary_radius
import logdate.client.feature.location.timeline.generated.resources.history_place_name_label
import logdate.client.feature.location.timeline.generated.resources.history_position_on_map
import logdate.client.feature.location.timeline.generated.resources.history_saved_places_heading
import logdate.client.feature.location.timeline.generated.resources.history_search_saved_nearby
import logdate.client.feature.location.timeline.generated.resources.history_selected_place_fallback
import logdate.client.feature.location.timeline.generated.resources.history_split_half
import logdate.client.feature.location.timeline.generated.resources.history_switch_to_clock
import logdate.client.feature.location.timeline.generated.resources.history_switch_to_keyboard
import logdate.client.feature.location.timeline.generated.resources.history_visit_structure
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal data class CategoryPresetUi(
    val key: String,
    val labelRes: StringResource,
)

internal val EDIT_CATEGORY_PRESETS =
    listOf(
        CategoryPresetUi("HOME", Res.string.history_category_home),
        CategoryPresetUi("WORK", Res.string.history_category_work),
        CategoryPresetUi("COFFEE", Res.string.history_category_coffee),
        CategoryPresetUi("FOOD", Res.string.history_category_food),
        CategoryPresetUi("OUTDOORS", Res.string.history_category_outdoors),
        CategoryPresetUi("FITNESS", Res.string.history_category_fitness),
        CategoryPresetUi("OTHER", Res.string.history_category_other),
    )

@Composable
internal fun HistoryActivityEditSection(
    item: LocationDayItem?,
    viewModel: HumanLocationHistoryViewModel,
    onDismiss: () -> Unit,
) {
    listOf(
        TravelMode.WALKING,
        TravelMode.RUNNING,
        TravelMode.CYCLING,
        TravelMode.DRIVING,
        TravelMode.BUS,
        TravelMode.TRAIN,
        TravelMode.UNKNOWN,
    ).forEach { mode ->
        ListItem(
            headlineContent = { Text(mode.labelText().asString()) },
            leadingContent = { Icon(travelModeIcon(mode), null) },
            modifier =
                Modifier.clickable {
                    item?.let { viewModel.changeActivity(it.id, mode.name) }
                    onDismiss()
                },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ColumnScope.HistoryTimeEditSection(
    action: HistoryEditAction,
    visit: PlaceVisit?,
    previousVisit: PlaceVisit?,
    nextVisit: PlaceVisit?,
    arrivalDate: LocalDate,
    departureDate: LocalDate,
    startTimePicker: TimePickerState,
    endTimePicker: TimePickerState,
    useTimeInput: Boolean,
    onToggleTimeInput: () -> Unit,
    onChooseArrival: () -> Unit,
    onChooseDeparture: () -> Unit,
    viewModel: HumanLocationHistoryViewModel,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = false,
            onClick = onChooseArrival,
            leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) },
            label = { Text(stringResource(Res.string.history_arrived_date, arrivalDate.toString())) },
        )
        IconButton(onClick = onToggleTimeInput) {
            Icon(
                if (useTimeInput) Icons.Default.AccessTime else Icons.Default.Keyboard,
                contentDescription =
                    stringResource(
                        if (useTimeInput) Res.string.history_switch_to_clock else Res.string.history_switch_to_keyboard,
                    ),
            )
        }
    }
    if (useTimeInput) TimeInput(state = startTimePicker) else TimePicker(state = startTimePicker)
    HorizontalDivider()
    FilterChip(
        selected = false,
        onClick = onChooseDeparture,
        leadingIcon = { Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp)) },
        label = { Text(stringResource(Res.string.history_left_date, departureDate.toString())) },
    )
    if (useTimeInput) TimeInput(state = endTimePicker) else TimePicker(state = endTimePicker)

    if (action == HistoryEditAction.ChangeTime && visit != null) {
        val anchor = visit.evidenceIds.firstOrNull()
        val canShowStructure = previousVisit != null || nextVisit != null || (visit.end - visit.start).inWholeMinutes >= 10
        if (anchor != null && canShowStructure) {
            HorizontalDivider()
            Text(stringResource(Res.string.history_visit_structure), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                previousVisit?.evidenceIds?.firstOrNull()?.let { prevAnchor ->
                    OutlinedButton(onClick = {
                        viewModel.mergeVisits(anchor, prevAnchor)
                        onDismiss()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.CallMerge, null, Modifier.size(16.dp))
                        Text(stringResource(Res.string.history_merge_previous), Modifier.padding(start = 6.dp))
                    }
                }
                nextVisit?.evidenceIds?.firstOrNull()?.let { nextAnchor ->
                    OutlinedButton(onClick = {
                        viewModel.mergeVisits(anchor, nextAnchor)
                        onDismiss()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.CallMerge, null, Modifier.size(16.dp))
                        Text(stringResource(Res.string.history_merge_next), Modifier.padding(start = 6.dp))
                    }
                }
                if ((visit.end - visit.start).inWholeMinutes >= 10) {
                    val midpoint = Instant.fromEpochMilliseconds((visit.start.toEpochMilliseconds() + visit.end.toEpochMilliseconds()) / 2)
                    OutlinedButton(onClick = {
                        viewModel.splitVisit(anchor, midpoint)
                        onDismiss()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.CallSplit, null, Modifier.size(16.dp))
                        Text(stringResource(Res.string.history_split_half), Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.HistoryPlaceEditSection(
    action: HistoryEditAction,
    visit: PlaceVisit?,
    name: String,
    onNameChange: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    radiusMeters: Float,
    onRadiusChange: (Float) -> Unit,
    place: SemanticPlace?,
    onSelectPlace: (SemanticPlace) -> Unit,
    filteredSaved: List<SemanticPlace>,
    filteredNearby: List<SemanticPlace>,
    loadingNearby: Boolean,
    showMapPicker: Boolean,
    onToggleMapPicker: () -> Unit,
    anchorLat: Double?,
    anchorLon: Double?,
    fallbackCoordinate: HistoryCoordinate?,
) {
    val selectedPlaceFallback = stringResource(Res.string.history_selected_place_fallback)
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(Res.string.history_place_name_label)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Text(stringResource(Res.string.history_category_label), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EDIT_CATEGORY_PRESETS.forEach { preset ->
            val selected = selectedCategory.equals(preset.key, ignoreCase = true)
            FilterChip(
                selected = selected,
                onClick = { onSelectCategory(if (selected) null else preset.key) },
                leadingIcon = { Icon(categoryIcon(preset.key), null, Modifier.size(16.dp)) },
                label = { Text(stringResource(preset.labelRes)) },
            )
        }
    }
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        label = { Text(stringResource(Res.string.history_search_saved_nearby)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (filteredSaved.isNotEmpty()) {
        Text(stringResource(Res.string.history_saved_places_heading), style = MaterialTheme.typography.labelLarge)
        filteredSaved.take(6).forEach { option ->
            val distanceText = formatDistanceAwayText(anchorLat, anchorLon, option.latitude, option.longitude)
            ListItem(
                headlineContent = { Text(option.name) },
                supportingContent = distanceText?.let { text -> { Text(text.asString()) } },
                leadingContent = { Icon(categoryIcon(option.category), null) },
                trailingContent =
                    if (place?.id == option.id) {
                        { Icon(Icons.Default.Check, null) }
                    } else {
                        null
                    },
                modifier = Modifier.clickable { onSelectPlace(option) },
            )
        }
    }
    if (action == HistoryEditAction.ChangePlace) {
        Text(stringResource(Res.string.history_nearby_suggestions_heading), style = MaterialTheme.typography.labelLarge)
        when {
            loadingNearby -> {
                Text(stringResource(Res.string.history_looking_nearby), style = MaterialTheme.typography.bodySmall)
            }

            filteredNearby.isEmpty() -> {
                Text(
                    stringResource(Res.string.history_no_nearby_matches),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> {
                filteredNearby.take(5).forEach { suggestion ->
                    val distText = formatDistanceAwayText(anchorLat, anchorLon, suggestion.latitude, suggestion.longitude)
                    val supporting =
                        listOfNotNull(distText, suggestion.locality?.takeIf { it.isNotBlank() }?.toUiText())
                            .takeIf { it.isNotEmpty() }
                            ?.let { UiText.Joined(it, " · ") }
                    ListItem(
                        headlineContent = { Text(suggestion.name) },
                        supportingContent = supporting?.let { text -> { Text(text.asString()) } },
                        leadingContent = { Icon(categoryIcon(suggestion.category), null) },
                        modifier = Modifier.clickable { onSelectPlace(suggestion.copy(userConfirmed = true)) },
                    )
                }
            }
        }
    }
    OutlinedButton(onClick = onToggleMapPicker, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Map, null, Modifier.size(18.dp))
        Text(
            stringResource(if (showMapPicker) Res.string.history_hide_map_pin else Res.string.history_position_on_map),
            Modifier.padding(start = 6.dp),
        )
    }
    if (showMapPicker) {
        HumanPlacePicker(
            selected = place,
            onPoint = { latitude, longitude ->
                onSelectPlace(
                    SemanticPlace(
                        id = place?.id ?: Uuid.random().toString(),
                        name = name.ifBlank { selectedPlaceFallback },
                        latitude = latitude,
                        longitude = longitude,
                        userConfirmed = true,
                        radiusMeters = radiusMeters.toDouble(),
                        category = selectedCategory,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(300.dp).clip(MaterialTheme.shapes.medium),
            radiusMeters = radiusMeters.toDouble(),
            fallbackCoordinate = visit?.let { HistoryCoordinate(it.latitude, it.longitude) } ?: fallbackCoordinate,
        )
        Text(
            stringResource(Res.string.history_place_boundary_radius, radiusMeters.roundToInt()),
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(value = radiusMeters, onValueChange = onRadiusChange, valueRange = 30f..500f)
    }
}

internal fun categoryIcon(category: String?): ImageVector =
    when (category?.uppercase()) {
        "HOME" -> Icons.Default.Home
        "WORK" -> Icons.Default.Work
        "COFFEE" -> Icons.Default.LocalCafe
        "FOOD" -> Icons.Default.Restaurant
        "OUTDOORS" -> Icons.Default.Park
        "FITNESS" -> Icons.Default.FitnessCenter
        else -> Icons.Default.Place
    }

internal fun formatDistanceAwayText(
    fromLat: Double?,
    fromLon: Double?,
    toLat: Double,
    toLon: Double,
): UiText? {
    if (fromLat == null || fromLon == null) return null
    val dLat = (toLat - fromLat) * PI / 180.0
    val dLon = (toLon - fromLon) * PI / 180.0
    val lat1 = fromLat * PI / 180.0
    val lat2 = toLat * PI / 180.0
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    val meters = 6_371_000.0 * 2.0 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1.0 - h).coerceIn(0.0, 1.0)))
    return if (meters >= 1000.0) {
        val tenths = ((meters / 1000.0) * 10.0).roundToInt()
        UiText.Resource(Res.string.history_distance_km_away, listOf("${tenths / 10}.${tenths % 10}"))
    } else {
        UiText.Resource(Res.string.history_distance_m_away, listOf(meters.roundToInt()))
    }
}
