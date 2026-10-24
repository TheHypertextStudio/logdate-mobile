@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import app.logdate.client.text.asString
import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.HistoryField
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import app.logdate.shared.model.location.VisitMemoryContext
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.common.DatePickerDialog
import app.logdate.ui.common.DropdownMenu
import app.logdate.ui.common.PlatformBackHandler
import app.logdate.ui.workspace.LocalWorkspaceEnabled
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_app_permission_settings
import logdate.client.feature.location.timeline.generated.resources.history_cancel
import logdate.client.feature.location.timeline.generated.resources.history_conflict_activity
import logdate.client.feature.location.timeline.generated.resources.history_conflict_arrival
import logdate.client.feature.location.timeline.generated.resources.history_conflict_deleted_visit
import logdate.client.feature.location.timeline.generated.resources.history_conflict_departure
import logdate.client.feature.location.timeline.generated.resources.history_conflict_place
import logdate.client.feature.location.timeline.generated.resources.history_conflict_saved_place
import logdate.client.feature.location.timeline.generated.resources.history_conflict_travelling
import logdate.client.feature.location.timeline.generated.resources.history_conflicts_body
import logdate.client.feature.location.timeline.generated.resources.history_conflicts_title
import logdate.client.feature.location.timeline.generated.resources.history_device_number
import logdate.client.feature.location.timeline.generated.resources.history_link_memory_body
import logdate.client.feature.location.timeline.generated.resources.history_link_memory_empty
import logdate.client.feature.location.timeline.generated.resources.history_link_memory_title
import logdate.client.feature.location.timeline.generated.resources.history_more_place_options
import logdate.client.feature.location.timeline.generated.resources.history_pause_recording
import logdate.client.feature.location.timeline.generated.resources.history_place_name
import logdate.client.feature.location.timeline.generated.resources.history_recording_interrupted
import logdate.client.feature.location.timeline.generated.resources.history_recording_settings
import logdate.client.feature.location.timeline.generated.resources.history_recording_title
import logdate.client.feature.location.timeline.generated.resources.history_resume_recording
import logdate.client.feature.location.timeline.generated.resources.history_review_changes
import logdate.client.feature.location.timeline.generated.resources.history_show_day
import logdate.client.feature.location.timeline.generated.resources.history_through_date
import logdate.client.ui.generated.resources.common_done
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Instant
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HumanLocationHistoryScreen(
    onOpenNote: (Uuid) -> Unit,
    onAddMemory: (VisitMemoryContext) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
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
        val workspace = LocalWorkspaceEnabled.current
        val placeDetail: (@Composable () -> Unit)? =
            openedPlace?.let { placeId ->
                snapshot?.let { data ->
                    {
                        HumanPlaceDetail(
                            placeId,
                            data,
                            onVisit = { visit ->
                                viewModel.setDate(visit.start.toLocalDateTime(TimeZone.currentSystemDefault()).date)
                                viewModel.select(visit.id, true)
                                openedPlace = null
                            },
                            onMemory = { Uuid.parseOrNull(it)?.let(onOpenNote) },
                            onMerge = viewModel::mergePlace,
                            onDismiss = { openedPlace = null },
                            embedded = true,
                        )
                    }
                }
            }
        app.logdate.ui.common
            .PlatformBackHandler(enabled = workspace && openedPlace != null) { openedPlace = null }
        HumanLocationHistoryContent(
            when {
                state.canRetry -> {
                    state
                }

                recordingEnabled && !access.ready -> {
                    state.copy(recoveryMessage = access.message.toUiText(), recoveryActionLabel = access.actionLabel.toUiText())
                }

                interrupted -> {
                    state.copy(
                        recoveryMessage = Res.string.history_recording_interrupted.toUiText(),
                        recoveryActionLabel = Res.string.history_resume_recording.toUiText(),
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
                onCalendar = { calendar = true },
                onTab = {
                    openedPlace = null
                    viewModel.setTab(it)
                },
                onPlacesQuery = viewModel::setQuery,
                onPlacesMapVisible = viewModel::setMapVisible,
                onPlacesFilter = viewModel::cycleRange,
                onOpenPlace = { openedPlace = it },
                onOpenMemory = { Uuid.parseOrNull(it)?.let(onOpenNote) },
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
                onRecover = {
                    when {
                        state.canRetry -> viewModel.retry()
                        recordingEnabled && !access.ready -> access.resolve()
                        interrupted -> access.resolve()
                        snapshot?.conflicts?.isNotEmpty() == true -> reviewingChanges = true
                        else -> recording = true
                    }
                },
            ),
            modifier = Modifier.weight(1f),
            showTitle = showTitle,
            supportingDetail = placeDetail.takeIf { workspace },
            toolbarActions = {
                IconButton(onClick = { moreMenu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(Res.string.history_more_place_options), Modifier.size(20.dp))
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.history_recording_settings)) }, onClick = {
                        moreMenu = false
                        recording = true
                    })
                    if (state.tab == HistoryTab.Places) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.history_through_date, state.dateLabel.asString())) },
                            onClick = {
                                moreMenu = false
                                calendar = true
                            },
                        )
                    }
                    if (snapshot?.conflicts?.isNotEmpty() == true) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.history_review_changes)) }, onClick = {
                            moreMenu = false
                            reviewingChanges = true
                        })
                    }
                    snapshot?.recordingDevices?.takeIf { it.size > 1 }?.forEachIndexed { index, device ->
                        DropdownMenuItem(text = { Text(stringResource(Res.string.history_device_number, index + 1)) }, onClick = {
                            moreMenu = false
                            viewModel.selectSource(device)
                        })
                    }
                }
            },
            mapAvailable = !workspace || state.tab != HistoryTab.Places || mapReady,
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
                    mapItems,
                    if (state.tab == HistoryTab.Places) openedPlace else state.selectedItemId,
                    {
                        if (state.tab == HistoryTab.Places) openedPlace = it else viewModel.select(it)
                    },
                    mapModifier,
                    viewModel::pauseReplay,
                    historyKey = mapSnapshot?.first,
                    showStatus = !workspace || state.tab != HistoryTab.Places,
                    onReadyChanged = { mapReady = it },
                )
            },
        )
    }
    if (calendar) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = viewModel.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(onDismissRequest = { calendar = false }, confirmButton = {
            TextButton(onClick = {
                picker.selectedDateMillis?.let {
                    viewModel.setDate(
                        Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date,
                        state.tab,
                    )
                }
                calendar =
                    false
            }) { Text(stringResource(Res.string.history_show_day)) }
        }, dismissButton = {
            TextButton(onClick = { calendar = false }) { Text(stringResource(Res.string.history_cancel)) }
        }) { DatePicker(picker) }
    }
    linkingVisit?.let { visitId ->
        val assigned =
            snapshot
                ?.items
                .orEmpty()
                .filterIsInstance<PlaceVisit>()
                .flatMap { it.memoryIds }
                .toSet()
        val available = snapshot?.notes.orEmpty().filterNot { it.uid.toString() in assigned }
        AlertDialog(
            onDismissRequest = { linkingVisit = null },
            title = { Text(stringResource(Res.string.history_link_memory_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(Res.string.history_link_memory_body))
                    if (available.isEmpty()) Text(stringResource(Res.string.history_link_memory_empty))
                    available.forEach { memory ->
                        TextButton(onClick = {
                            viewModel.linkMemory(visitId, memory.uid.toString())
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
            title = { Text(stringResource(Res.string.history_recording_title)) },
            text = {
                Column {
                    Text(stringResource(access.message))
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
                }) { Text(stringResource(access.actionLabel)) }
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
            action,
            id?.let { key -> snapshot?.items?.firstOrNull { it.id == key } },
            snapshot?.collectionPlaces().orEmpty(),
            viewModel,
            onDismiss = { edit = null },
        )
    }
    if (!LocalWorkspaceEnabled.current) {
        openedPlace?.let { placeId ->
            snapshot?.let { data ->
                HumanPlaceDetail(
                    placeId,
                    data,
                    onVisit = { visit ->
                        viewModel.setDate(visit.start.toLocalDateTime(TimeZone.currentSystemDefault()).date)
                        viewModel.select(visit.id, true)
                        openedPlace = null
                    },
                    onMemory = { Uuid.parseOrNull(it)?.let(onOpenNote) },
                    onMerge = viewModel::mergePlace,
                    onDismiss = { openedPlace = null },
                )
            }
        }
    }
    if (reviewingChanges) {
        AlertDialog(
            onDismissRequest = { reviewingChanges = false },
            title = { Text(stringResource(Res.string.history_conflicts_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(Res.string.history_conflicts_body))
                    snapshot?.conflicts.orEmpty().groupBy { it.targetEvidenceId to it.field }.forEach { (key, edits) ->
                        Text(
                            when (key.second) {
                                HistoryField.PLACE -> stringResource(Res.string.history_conflict_place)
                                HistoryField.LABEL -> stringResource(Res.string.history_place_name)
                                HistoryField.ACTIVITY -> stringResource(Res.string.history_conflict_activity)
                                HistoryField.START -> stringResource(Res.string.history_conflict_arrival)
                                HistoryField.END -> stringResource(Res.string.history_conflict_departure)
                                HistoryField.DELETE -> stringResource(Res.string.history_conflict_deleted_visit)
                            },
                        )
                        edits.distinctBy { it.value }.forEach { edit ->
                            val label =
                                when (edit.field) {
                                    HistoryField.PLACE -> {
                                        snapshot?.places?.firstOrNull { it.id == edit.value }?.name
                                            ?: stringResource(Res.string.history_conflict_saved_place)
                                    }

                                    HistoryField.ACTIVITY -> {
                                        stringResource(
                                            TravelMode.entries.firstOrNull { it.name == edit.value }?.humanName()
                                                ?: Res.string.history_conflict_travelling,
                                        )
                                    }

                                    else -> {
                                        edit.value
                                    }
                                }
                            TextButton(
                                onClick = { viewModel.resolveConflict(edit.targetEvidenceId, edit.field, edit.value) },
                            ) { Text(label) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { reviewingChanges = false }) { Text(stringResource(UiRes.string.common_done)) } },
        )
    }
}
