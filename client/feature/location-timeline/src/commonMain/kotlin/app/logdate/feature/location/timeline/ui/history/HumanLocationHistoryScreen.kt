@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.location.tracking.LocationCaptureStatus
import app.logdate.client.text.UiText
import app.logdate.client.text.asString
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import app.logdate.shared.model.location.VisitMemoryContext
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.common.PlatformBackHandler
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_app_permission_settings
import logdate.client.feature.location.timeline.generated.resources.history_cancel
import logdate.client.feature.location.timeline.generated.resources.history_conflicts_dialog_body
import logdate.client.feature.location.timeline.generated.resources.history_conflicts_dialog_title
import logdate.client.feature.location.timeline.generated.resources.history_done
import logdate.client.feature.location.timeline.generated.resources.history_field_activity
import logdate.client.feature.location.timeline.generated.resources.history_field_arrival
import logdate.client.feature.location.timeline.generated.resources.history_field_category
import logdate.client.feature.location.timeline.generated.resources.history_field_deleted_visit
import logdate.client.feature.location.timeline.generated.resources.history_field_departure
import logdate.client.feature.location.timeline.generated.resources.history_field_merged_visit
import logdate.client.feature.location.timeline.generated.resources.history_field_other
import logdate.client.feature.location.timeline.generated.resources.history_field_place
import logdate.client.feature.location.timeline.generated.resources.history_field_place_name
import logdate.client.feature.location.timeline.generated.resources.history_field_radius
import logdate.client.feature.location.timeline.generated.resources.history_field_split_visit
import logdate.client.feature.location.timeline.generated.resources.history_field_suggested_place
import logdate.client.feature.location.timeline.generated.resources.history_link_memory_body
import logdate.client.feature.location.timeline.generated.resources.history_link_memory_empty
import logdate.client.feature.location.timeline.generated.resources.history_link_memory_title
import logdate.client.feature.location.timeline.generated.resources.history_more_options
import logdate.client.feature.location.timeline.generated.resources.history_pause_recording
import logdate.client.feature.location.timeline.generated.resources.history_recording_interrupted_banner
import logdate.client.feature.location.timeline.generated.resources.history_recording_settings
import logdate.client.feature.location.timeline.generated.resources.history_remember_day_title
import logdate.client.feature.location.timeline.generated.resources.history_resume_recording
import logdate.client.feature.location.timeline.generated.resources.history_review_changes
import logdate.client.feature.location.timeline.generated.resources.history_saved_place_fallback
import logdate.client.feature.location.timeline.generated.resources.history_through_date
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HumanLocationHistoryScreen(
    onOpenNote: (Uuid) -> Unit,
    onAddMemory: (VisitMemoryContext) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HumanLocationHistoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mapSnapshot by viewModel.mapSnapshot.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val access = rememberHistoryRecordingAccess()
    val recordingEnabled by viewModel.recordingEnabled.collectAsStateWithLifecycle()
    val completeDayEnabled by viewModel.completeDayEnabled.collectAsStateWithLifecycle()
    val interrupted =
        completeDayEnabled &&
            access.ready &&
            (access.captureStatus == LocationCaptureStatus.Stopped || access.captureStatus == LocationCaptureStatus.Failed)
    var mapReady by remember { mutableStateOf(false) }
    var calendar by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<Pair<String?, HistoryEditAction>?>(null) }
    var reviewingChanges by remember { mutableStateOf(false) }
    var openedPlace by rememberSaveable { mutableStateOf<String?>(null) }
    var linkingVisit by remember { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }

    Column(modifier) {
        val placeDetail: (@Composable () -> Unit)? =
            openedPlace?.let { placeId ->
                snapshot?.let { data ->
                    {
                        HumanPlaceDetail(
                            placeId = placeId,
                            snapshot = data,
                            onVisit = { visit ->
                                viewModel.setDate(visit.start.toLocalDateTime(TimeZone.currentSystemDefault()).date)
                                viewModel.select(visit.id, true)
                                openedPlace = null
                            },
                            onMemory = onOpenNote,
                            onMerge = viewModel::mergePlace,
                            onDismiss = { openedPlace = null },
                            onRename = viewModel::updatePlaceName,
                            onUpdateRadius = viewModel::updatePlaceRadius,
                            onUpdateCategory = viewModel::updatePlaceCategory,
                        )
                    }
                }
            }
        PlatformBackHandler(enabled = openedPlace != null) { openedPlace = null }
        HumanLocationHistoryContent(
            when {
                state.isRetryRecovery -> {
                    state
                }

                recordingEnabled && !access.ready -> {
                    state.copy(recoveryMessage = access.message, recoveryActionLabel = access.actionLabel)
                }

                interrupted -> {
                    state.copy(
                        recoveryMessage = UiText.Resource(Res.string.history_recording_interrupted_banner),
                        recoveryActionLabel = UiText.Resource(Res.string.history_resume_recording),
                    )
                }

                else -> {
                    state
                }
            },
            HumanLocationHistoryActions(
                onSelectItem = { viewModel.select(it) },
                onOpenDetail = { viewModel.select(it, true) },
                onCloseDetail = viewModel::dismiss,
                onDayOffset = viewModel::changeDay,
                onSelectDate = { picked -> viewModel.setDate(picked, state.tab) },
                onJumpToToday = viewModel::jumpToToday,
                onCalendar = { calendar = true },
                onCalendarMonthOffset = viewModel::shiftCalendarMonth,
                onJumpToLatest = viewModel::jumpToLatestRecordedDay,
                onTab = {
                    openedPlace = null
                    viewModel.setTab(it)
                },
                onPlacesQuery = viewModel::setQuery,
                onPlacesMapVisible = viewModel::setMapVisible,
                onPlacesFilter = viewModel::cycleRange,
                onPlacesRangePreset = viewModel::setPlacesRangePreset,
                onPlacesSort = viewModel::setPlacesSort,
                onPlacesCategoryFilter = viewModel::setPlacesCategoryFilter,
                onConfirmPlace = viewModel::confirmPlaceSelection,
                onOpenPlace = { openedPlace = it },
                onOpenMemory = onOpenNote,
                onEdit = { id, action ->
                    when {
                        action == HistoryEditAction.AddMemory && id != null -> {
                            viewModel.contextFor(id)?.let(onAddMemory)
                        }

                        action == HistoryEditAction.LinkMemory && id != null -> {
                            viewModel.dismiss()
                            linkingVisit = id
                        }

                        action == HistoryEditAction.Delete && id != null -> {
                            viewModel.delete(id)
                        }

                        else -> {
                            edit = id to action
                        }
                    }
                },
                onReplayPlaying = if (access.reducedMotion) null else viewModel::replay,
                onReplayScrub = if (access.reducedMotion) null else viewModel::scrubReplay,
                onReplaySpeed = if (access.reducedMotion) null else viewModel::cycleReplaySpeed,
                onRecover = {
                    when {
                        state.isRetryRecovery -> viewModel.retry()
                        recordingEnabled && !access.ready -> access.resolve()
                        interrupted -> access.resolve()
                        snapshot?.conflicts?.isNotEmpty() == true -> reviewingChanges = true
                        else -> recording = true
                    }
                },
            ),
            modifier = Modifier.weight(1f),
            supportingDetail = placeDetail,
            toolbarActions = {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(Res.string.history_more_options), Modifier.size(20.dp))
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.history_recording_settings)) }, onClick = {
                        moreMenu = false
                        recording = true
                    })
                    if (state.tab == HistoryTab.Places) {
                        val dateText = state.dateLabel.asString()
                        DropdownMenuItem(text = { Text(stringResource(Res.string.history_through_date, dateText)) }, onClick = {
                            moreMenu = false
                            calendar = true
                        })
                    }
                    if (snapshot?.conflicts?.isNotEmpty() == true) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.history_review_changes)) }, onClick = {
                            moreMenu = false
                            reviewingChanges = true
                        })
                    }
                    state.recordingDeviceOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label.asString()) },
                            onClick = {
                                moreMenu = false
                                viewModel.selectSource(option.id)
                            },
                        )
                    }
                }
            },
            mapAvailable = state.tab != HistoryTab.Places || mapReady,
            mapContent = { mapModifier ->
                val mapItems =
                    if (state.tab == HistoryTab.Places) {
                        val visiblePlaceIds = state.filteredPlaces().flatMapTo(mutableSetOf()) { it.sourceIds }
                        mapSnapshot
                            ?.second
                            ?.collectionPlaces()
                            .orEmpty()
                            .filter { place -> place.id in visiblePlaceIds }
                            .map {
                                PlaceVisit(
                                    it.id,
                                    viewModel.date.atStartOfDayIn(TimeZone.currentSystemDefault()),
                                    viewModel.date.atStartOfDayIn(TimeZone.currentSystemDefault()),
                                    listOf(it.id),
                                    it.latitude,
                                    it.longitude,
                                    false,
                                    it,
                                )
                            }
                    } else {
                        mapSnapshot?.second?.items.orEmpty()
                    }
                HumanHistoryMap(
                    items = mapItems,
                    selectedId = if (state.tab == HistoryTab.Places) openedPlace else state.selectedItemId,
                    onSelect = { if (state.tab == HistoryTab.Places) openedPlace = it else viewModel.select(it) },
                    modifier = mapModifier,
                    onInteraction = viewModel::pauseReplay,
                    historyKey = mapSnapshot?.first,
                    isPlacesCollection = state.tab == HistoryTab.Places,
                    isToday = state.isToday,
                    replaySample = if (state.tab == HistoryTab.Day) state.replaySample else null,
                    fallbackCenter = state.fallbackCoordinate(),
                    showStatus = state.tab != HistoryTab.Places,
                    onReadyChanged = { mapReady = it },
                )
            },
        )
    }

    if (calendar) {
        HistoryCalendarSheet(
            monthLabel = state.calendarMonthLabel ?: state.dateLabel,
            days = state.calendarDays,
            selectedDate = viewModel.date,
            latestRecordedDate = state.latestRecordedDate,
            onSelectDate = { picked -> viewModel.setDate(picked, state.tab) },
            onMonthOffset = viewModel::shiftCalendarMonth,
            onJumpToLatest = viewModel::jumpToLatestRecordedDay,
            onDismiss = { calendar = false },
        )
    }
    linkingVisit?.let { visitId ->
        val assigned =
            snapshot
                ?.items
                .orEmpty()
                .filterIsInstance<PlaceVisit>()
                .flatMapTo(mutableSetOf()) { it.memoryIds }
        val available = snapshot?.notes.orEmpty().filterNot { it.uid in assigned }
        AlertDialog(
            onDismissRequest = { linkingVisit = null },
            title = { Text(stringResource(Res.string.history_link_memory_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(Res.string.history_link_memory_body))
                    if (available.isEmpty()) Text(stringResource(Res.string.history_link_memory_empty))
                    available.forEach { memory ->
                        TextButton(onClick = {
                            viewModel.linkMemory(visitId, memory.uid)
                            linkingVisit = null
                        }) {
                            Text(
                                memory
                                    .toHistoryMemory()
                                    .title
                                    .asString()
                                    .take(160),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { linkingVisit = null }) { Text(stringResource(Res.string.history_cancel)) } },
        )
    }
    if (recording) {
        AlertDialog(
            onDismissRequest = { recording = false },
            title = { Text(stringResource(Res.string.history_remember_day_title)) },
            text = {
                Column {
                    Text(access.message.asString())
                    access.openSettings?.let { open ->
                        TextButton(onClick = open) { Text(stringResource(Res.string.history_app_permission_settings)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (access.ready) {
                        viewModel.enableRecording()
                        access.resolve()
                        recording = false
                    } else {
                        access.resolve()
                    }
                }) { Text(access.actionLabel.asString()) }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.pauseRecording()
                    recording = false
                }) { Text(stringResource(Res.string.history_pause_recording)) }
            },
        )
    }
    edit?.let { (id, action) ->
        HistoryEditDialog(
            action = action,
            item = id?.let { key -> snapshot?.items?.firstOrNull { it.id == key } },
            places = snapshot?.collectionPlaces().orEmpty(),
            viewModel = viewModel,
            allItems = snapshot?.items.orEmpty(),
            fallbackCoordinate = state.fallbackCoordinate(),
            onDismiss = { edit = null },
        )
    }
    if (reviewingChanges) {
        AlertDialog(
            onDismissRequest = { reviewingChanges = false },
            title = { Text(stringResource(Res.string.history_conflicts_dialog_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(Res.string.history_conflicts_dialog_body))
                    snapshot?.conflicts.orEmpty().groupBy { it.targetEvidenceId to it.field }.forEach { (key, edits) ->
                        Text(stringResource(key.second.labelResource()))
                        edits.distinctBy { it.value }.forEach { conflict ->
                            val label =
                                when (conflict.field) {
                                    HistoryField.PLACE -> {
                                        snapshot?.places?.firstOrNull { it.id == conflict.value }?.name
                                            ?: stringResource(Res.string.history_saved_place_fallback)
                                    }

                                    HistoryField.ACTIVITY -> {
                                        (TravelMode.entries.firstOrNull { it.name == conflict.value } ?: TravelMode.UNKNOWN)
                                            .labelText()
                                            .asString()
                                    }

                                    else -> {
                                        conflict.value
                                    }
                                }
                            TextButton(onClick = { viewModel.resolveConflict(conflict.targetEvidenceId, conflict.field, conflict.value) }) {
                                Text(label)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reviewingChanges = false }) { Text(stringResource(Res.string.history_done)) } },
        )
    }
}

private fun HistoryField.labelResource() =
    when (this) {
        HistoryField.PLACE -> Res.string.history_field_place
        HistoryField.LABEL -> Res.string.history_field_place_name
        HistoryField.ACTIVITY -> Res.string.history_field_activity
        HistoryField.START -> Res.string.history_field_arrival
        HistoryField.END -> Res.string.history_field_departure
        HistoryField.DELETE -> Res.string.history_field_deleted_visit
        HistoryField.SUGGESTED_PLACE -> Res.string.history_field_suggested_place
        HistoryField.RADIUS -> Res.string.history_field_radius
        HistoryField.CATEGORY -> Res.string.history_field_category
        HistoryField.MERGE_WITH -> Res.string.history_field_merged_visit
        HistoryField.SPLIT_AT -> Res.string.history_field_split_visit
        HistoryField.UNKNOWN -> Res.string.history_field_other
    }
