@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.DatePickerDialog
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_add_visit
import logdate.client.feature.location.timeline.generated.resources.history_arrived_on
import logdate.client.feature.location.timeline.generated.resources.history_arrived_time_label
import logdate.client.feature.location.timeline.generated.resources.history_arrived_time_placeholder
import logdate.client.feature.location.timeline.generated.resources.history_cancel
import logdate.client.feature.location.timeline.generated.resources.history_choose_date
import logdate.client.feature.location.timeline.generated.resources.history_choose_on_map
import logdate.client.feature.location.timeline.generated.resources.history_choose_on_map_hint
import logdate.client.feature.location.timeline.generated.resources.history_choose_place
import logdate.client.feature.location.timeline.generated.resources.history_edit_activity_title
import logdate.client.feature.location.timeline.generated.resources.history_edit_invalid_times
import logdate.client.feature.location.timeline.generated.resources.history_edit_name_required
import logdate.client.feature.location.timeline.generated.resources.history_edit_place_required
import logdate.client.feature.location.timeline.generated.resources.history_edit_place_title
import logdate.client.feature.location.timeline.generated.resources.history_edit_time_title
import logdate.client.feature.location.timeline.generated.resources.history_left_on
import logdate.client.feature.location.timeline.generated.resources.history_left_time_label
import logdate.client.feature.location.timeline.generated.resources.history_left_time_placeholder
import logdate.client.feature.location.timeline.generated.resources.history_nearby_empty
import logdate.client.feature.location.timeline.generated.resources.history_nearby_loading
import logdate.client.feature.location.timeline.generated.resources.history_nearby_suggestions
import logdate.client.feature.location.timeline.generated.resources.history_place_name
import logdate.client.ui.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryEditDialog(
    action: HistoryEditAction,
    item: LocationDayItem?,
    places: List<SemanticPlace>,
    viewModel: HumanLocationHistoryViewModel,
    onDismiss: () -> Unit,
) {
    val zone = TimeZone.currentSystemDefault()
    var name by remember(item?.id) { mutableStateOf((item as? PlaceVisit)?.place?.name.orEmpty()) }
    var start by remember(item?.id) {
        mutableStateOf(
            item
                ?.start
                ?.toLocalDateTime(zone)
                ?.time
                ?.toString()
                ?.take(5) ?: "12:00",
        )
    }
    var end by remember(item?.id) {
        mutableStateOf(
            item
                ?.end
                ?.toLocalDateTime(zone)
                ?.time
                ?.toString()
                ?.take(5) ?: "12:30",
        )
    }
    var place by remember { mutableStateOf((item as? PlaceVisit)?.place) }
    var chooseNew by remember { mutableStateOf(places.isEmpty()) }
    var arrivalDate by remember { mutableStateOf(item?.start?.toLocalDateTime(zone)?.date ?: viewModel.date) }
    var departureDate by remember { mutableStateOf(item?.end?.toLocalDateTime(zone)?.date ?: viewModel.date) }
    var choosingArrival by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameRequiredError = stringResource(Res.string.history_edit_name_required)
    val invalidTimesError = stringResource(Res.string.history_edit_invalid_times)
    val placeRequiredError = stringResource(Res.string.history_edit_place_required)
    var nearby by remember(item?.id) { mutableStateOf(emptyList<SemanticPlace>()) }
    var loadingNearby by remember(item?.id) { mutableStateOf(action == HistoryEditAction.ChangePlace) }
    LaunchedEffect(item?.id, action) {
        if (action == HistoryEditAction.ChangePlace) {
            nearby = viewModel.nearbyPlacesFor(item)
            loadingNearby = false
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = {
        Text(
            when (action) {
                HistoryEditAction.ChangePlace -> stringResource(Res.string.history_edit_place_title)
                HistoryEditAction.ChangeActivity -> stringResource(Res.string.history_edit_activity_title)
                HistoryEditAction.ChangeTime -> stringResource(Res.string.history_edit_time_title)
                else -> stringResource(Res.string.history_add_visit)
            },
        )
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (action) {
                HistoryEditAction.ChangeActivity -> {
                    listOf(
                        TravelMode.WALKING,
                        TravelMode.RUNNING,
                        TravelMode.CYCLING,
                        TravelMode.DRIVING,
                        TravelMode.BUS,
                        TravelMode.TRAIN,
                        TravelMode.UNKNOWN,
                    ).forEach { mode ->
                        TextButton(onClick = {
                            item?.let { viewModel.changeActivity(it.id, mode.name) }
                            onDismiss()
                        }) { Text(stringResource(mode.humanName())) }
                    }
                }

                HistoryEditAction.ChangeTime, HistoryEditAction.AddVisit -> {
                    TextButton(onClick = { choosingArrival = true }) { Text(stringResource(Res.string.history_arrived_on, arrivalDate)) }
                    OutlinedTextField(
                        start,
                        { start = it },
                        label = { Text(stringResource(Res.string.history_arrived_time_label)) },
                        placeholder = { Text(stringResource(Res.string.history_arrived_time_placeholder)) },
                    )
                    TextButton(onClick = { choosingArrival = false }) { Text(stringResource(Res.string.history_left_on, departureDate)) }
                    OutlinedTextField(
                        end,
                        { end = it },
                        label = { Text(stringResource(Res.string.history_left_time_label)) },
                        placeholder = { Text(stringResource(Res.string.history_left_time_placeholder)) },
                    )
                }

                else -> {
                    Unit
                }
            }
            if (action == HistoryEditAction.ChangePlace || action == HistoryEditAction.AddVisit) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.history_place_name)) })
                Text(stringResource(Res.string.history_choose_place))
                places.forEach { option ->
                    TextButton(onClick = {
                        place = option
                        name = option.name
                        chooseNew = false
                    }) {
                        Text(if (place?.id == option.id) "✓ ${option.name}" else option.name)
                    }
                }
                if (action == HistoryEditAction.ChangePlace) {
                    Text(stringResource(Res.string.history_nearby_suggestions))
                    if (loadingNearby) {
                        Text(stringResource(Res.string.history_nearby_loading))
                    } else if (nearby.isEmpty()) {
                        Text(stringResource(Res.string.history_nearby_empty))
                    }
                    nearby.forEach { suggestion ->
                        TextButton(onClick = {
                            place = suggestion.copy(userConfirmed = true)
                            name = suggestion.name
                            chooseNew = false
                        }) { Text(suggestion.name) }
                    }
                }
                TextButton(onClick = { chooseNew = !chooseNew }) { Text(stringResource(Res.string.history_choose_on_map)) }
                if (chooseNew) {
                    Text(stringResource(Res.string.history_choose_on_map_hint))
                    HumanPlacePicker(place, { latitude, longitude ->
                        place = SemanticPlace(Uuid.random().toString(), name, latitude, longitude, userConfirmed = true)
                    }, Modifier.fillMaxWidth().height(240.dp))
                }
            }
            error?.let { Text(it) }
        }
    }, confirmButton = {
        if (action != HistoryEditAction.ChangeActivity) {
            TextButton(onClick = {
                if (action == HistoryEditAction.ChangePlace) {
                    if (name.isBlank()) {
                        error = nameRequiredError
                        return@TextButton
                    }
                    item?.let { visit ->
                        val selected = place
                        if (selected == null) {
                            viewModel.namePlace(visit.id, name)
                        } else {
                            viewModel.choosePlace(visit.id, selected.copy(name = name.trim()))
                        }
                    }
                    onDismiss()
                } else {
                    val startDate = arrivalDate
                    val endDate = departureDate
                    val first = runCatching { LocalDateTime(startDate, LocalTime.parse(start)).toInstant(zone) }.getOrNull()
                    val last = runCatching { LocalDateTime(endDate, LocalTime.parse(end)).toInstant(zone) }.getOrNull()
                    if (first == null ||
                        last == null ||
                        last < first
                    ) {
                        error = invalidTimesError
                        return@TextButton
                    }
                    if (action == HistoryEditAction.AddVisit) {
                        val selected = place
                        if (selected == null || name.isBlank()) {
                            error = placeRequiredError
                            return@TextButton
                        }
                        viewModel.addVisit(first, last, selected.copy(name = name.trim()))
                    } else {
                        item?.let { viewModel.changeTime(it.id, first, last) }
                    }
                    onDismiss()
                }
            }) { Text(stringResource(UiRes.string.common_save)) }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.history_cancel)) } })
    choosingArrival?.let { isArrival ->
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis =
                    (if (isArrival) arrivalDate else departureDate)
                        .atStartOfDayIn(TimeZone.UTC)
                        .toEpochMilliseconds(),
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
