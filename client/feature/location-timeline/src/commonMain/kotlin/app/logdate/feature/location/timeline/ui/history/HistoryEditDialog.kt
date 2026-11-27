@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.ui.common.DatePickerDialog
import app.logdate.ui.common.ModalBottomSheet
import app.logdate.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_add_visit
import logdate.client.feature.location.timeline.generated.resources.history_cancel
import logdate.client.feature.location.timeline.generated.resources.history_choose_date
import logdate.client.feature.location.timeline.generated.resources.history_edit_title_activity
import logdate.client.feature.location.timeline.generated.resources.history_edit_title_place
import logdate.client.feature.location.timeline.generated.resources.history_edit_title_time
import logdate.client.feature.location.timeline.generated.resources.history_error_choose_and_name_place
import logdate.client.feature.location.timeline.generated.resources.history_error_departure_order
import logdate.client.feature.location.timeline.generated.resources.history_error_name_required
import logdate.client.feature.location.timeline.generated.resources.history_save
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryEditDialog(
    action: HistoryEditAction,
    item: LocationDayItem?,
    places: List<SemanticPlace>,
    viewModel: HumanLocationHistoryViewModel,
    allItems: List<LocationDayItem> = emptyList(),
    fallbackCoordinate: HistoryCoordinate? = null,
    onDismiss: () -> Unit,
) {
    val zone = TimeZone.currentSystemDefault()
    val visit = item as? PlaceVisit
    var name by remember(item?.id) { mutableStateOf(visit?.place?.name.orEmpty()) }
    var searchQuery by remember(item?.id) { mutableStateOf("") }
    var selectedCategory by remember(item?.id) { mutableStateOf(visit?.place?.category) }
    var radiusMeters by remember(item?.id) {
        mutableFloatStateOf((visit?.place?.radiusMeters ?: 75.0).toFloat().coerceIn(30f, 500f))
    }
    val initialStart = (item?.start ?: viewModel.date.atStartOfDayIn(zone)).toLocalDateTime(zone)
    val initialEnd = (item?.end ?: viewModel.date.atStartOfDayIn(zone)).toLocalDateTime(zone)
    var arrivalDate by remember(item?.id) { mutableStateOf(initialStart.date) }
    var departureDate by remember(item?.id) { mutableStateOf(initialEnd.date) }
    val startTimePicker = rememberTimePickerState(initialHour = initialStart.hour, initialMinute = initialStart.minute)
    val endTimePicker = rememberTimePickerState(initialHour = initialEnd.hour, initialMinute = initialEnd.minute)
    var useTimeInput by remember { mutableStateOf(false) }
    var place by remember(item?.id) { mutableStateOf(visit?.place) }
    var showMapPicker by remember(item?.id) { mutableStateOf(places.isEmpty() || action == HistoryEditAction.AddVisit) }
    var choosingArrival by remember { mutableStateOf<Boolean?>(null) }
    var errorRes by remember { mutableStateOf<StringResource?>(null) }
    var nearby by remember(item?.id) { mutableStateOf(emptyList<SemanticPlace>()) }
    var loadingNearby by remember(item?.id) { mutableStateOf(action == HistoryEditAction.ChangePlace) }

    LaunchedEffect(item?.id, action, searchQuery) {
        if (action == HistoryEditAction.ChangePlace) {
            if (searchQuery.isNotBlank()) delay(300)
            loadingNearby = true
            nearby = viewModel.nearbyPlacesFor(item)
            loadingNearby = false
        }
    }

    val visitsOnly = remember(allItems) { allItems.filterIsInstance<PlaceVisit>() }
    val currentVisitIndex = remember(visitsOnly, visit?.id) { visitsOnly.indexOfFirst { it.id == visit?.id } }
    val previousVisit = if (currentVisitIndex > 0) visitsOnly[currentVisitIndex - 1] else null
    val nextVisit = if (currentVisitIndex in 0 until visitsOnly.lastIndex) visitsOnly[currentVisitIndex + 1] else null
    val trimmedQuery = searchQuery.trim()
    val filteredSaved =
        remember(places, trimmedQuery) {
            if (trimmedQuery.isEmpty()) places else places.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
        }
    val filteredNearby =
        remember(nearby, trimmedQuery) {
            if (trimmedQuery.isEmpty()) nearby else nearby.filter { it.name.contains(trimmedQuery, ignoreCase = true) }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(
                        rememberScrollState(),
                    ).padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text =
                    stringResource(
                        when (action) {
                            HistoryEditAction.ChangePlace -> Res.string.history_edit_title_place
                            HistoryEditAction.ChangeActivity -> Res.string.history_edit_title_activity
                            HistoryEditAction.ChangeTime -> Res.string.history_edit_title_time
                            else -> Res.string.history_add_visit
                        },
                    ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            when (action) {
                HistoryEditAction.ChangeActivity -> {
                    HistoryActivityEditSection(item, viewModel, onDismiss)
                }

                HistoryEditAction.ChangeTime, HistoryEditAction.AddVisit -> {
                    HistoryTimeEditSection(
                        action,
                        visit,
                        previousVisit,
                        nextVisit,
                        arrivalDate,
                        departureDate,
                        startTimePicker,
                        endTimePicker,
                        useTimeInput,
                        { useTimeInput = !useTimeInput },
                        { choosingArrival = true },
                        { choosingArrival = false },
                        viewModel,
                        onDismiss,
                    )
                }

                else -> {}
            }

            if (action == HistoryEditAction.ChangePlace || action == HistoryEditAction.AddVisit) {
                HistoryPlaceEditSection(
                    action = action,
                    visit = visit,
                    name = name,
                    onNameChange = { name = it },
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    selectedCategory = selectedCategory,
                    onSelectCategory = { selectedCategory = it },
                    radiusMeters = radiusMeters,
                    onRadiusChange = { radiusMeters = it },
                    place = place,
                    onSelectPlace = { selected ->
                        place = selected
                        name = selected.name
                        selectedCategory = selected.category ?: selectedCategory
                        if (selected.radiusMeters > 0.0) radiusMeters = selected.radiusMeters.toFloat().coerceIn(30f, 500f)
                        showMapPicker = false
                    },
                    filteredSaved = filteredSaved,
                    filteredNearby = filteredNearby,
                    loadingNearby = loadingNearby,
                    showMapPicker = showMapPicker,
                    onToggleMapPicker = { showMapPicker = !showMapPicker },
                    anchorLat = visit?.latitude ?: fallbackCoordinate?.latitude,
                    anchorLon = visit?.longitude ?: fallbackCoordinate?.longitude,
                    fallbackCoordinate = fallbackCoordinate,
                )
            }

            errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (action != HistoryEditAction.ChangeActivity) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.history_cancel)) }
                    Button(onClick = {
                        if (action == HistoryEditAction.ChangePlace) {
                            if (name.isBlank()) {
                                errorRes = Res.string.history_error_name_required
                                return@Button
                            }
                            item?.let { target ->
                                val basePlace =
                                    place ?: SemanticPlace(
                                        id = visit?.place?.id ?: Uuid.random().toString(),
                                        name = name.trim(),
                                        latitude = visit?.latitude ?: 0.0,
                                        longitude = visit?.longitude ?: 0.0,
                                        userConfirmed = true,
                                    )
                                viewModel.choosePlace(
                                    target.id,
                                    basePlace.copy(
                                        name = name.trim(),
                                        radiusMeters = radiusMeters.toDouble(),
                                        category = selectedCategory,
                                        userConfirmed = true,
                                    ),
                                )
                            }
                            onDismiss()
                        } else {
                            val first = LocalDateTime(arrivalDate, LocalTime(startTimePicker.hour, startTimePicker.minute)).toInstant(zone)
                            val last = LocalDateTime(departureDate, LocalTime(endTimePicker.hour, endTimePicker.minute)).toInstant(zone)
                            if (last < first) {
                                errorRes = Res.string.history_error_departure_order
                                return@Button
                            }
                            if (action == HistoryEditAction.AddVisit) {
                                val selected = place
                                if (selected == null || name.isBlank()) {
                                    errorRes = Res.string.history_error_choose_and_name_place
                                    return@Button
                                }
                                viewModel.addVisit(
                                    first,
                                    last,
                                    selected.copy(
                                        name = name.trim(),
                                        radiusMeters = radiusMeters.toDouble(),
                                        category = selectedCategory,
                                        userConfirmed = true,
                                    ),
                                )
                            } else {
                                item?.let { viewModel.changeTime(it.id, first, last) }
                            }
                            onDismiss()
                        }
                    }) { Text(stringResource(Res.string.history_save)) }
                }
            }
        }
    }

    choosingArrival?.let { isArrival ->
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    (if (isArrival) arrivalDate else departureDate)
                        .atStartOfDayIn(
                            TimeZone.UTC,
                        ).toEpochMilliseconds(),
            )
        DatePickerDialog(onDismissRequest = { choosingArrival = null }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    val picked = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date
                    if (isArrival) arrivalDate = picked else departureDate = picked
                }
                choosingArrival = null
            }) { Text(stringResource(Res.string.history_choose_date)) }
        }, dismissButton = {
            TextButton(onClick = { choosingArrival = null }) { Text(stringResource(Res.string.history_cancel)) }
        }) { DatePicker(picker) }
    }
}
