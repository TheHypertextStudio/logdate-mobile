@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.text.asString
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.PanelGroup
import app.logdate.ui.workspace.PanelHeader
import app.logdate.util.toReadableDateShort
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_boundary_radius_label
import logdate.client.feature.location.timeline.generated.resources.history_cancel
import logdate.client.feature.location.timeline.generated.resources.history_category_label
import logdate.client.feature.location.timeline.generated.resources.history_close_place
import logdate.client.feature.location.timeline.generated.resources.history_date_range_span
import logdate.client.feature.location.timeline.generated.resources.history_done
import logdate.client.feature.location.timeline.generated.resources.history_filter_places_to_merge
import logdate.client.feature.location.timeline.generated.resources.history_memories_heading
import logdate.client.feature.location.timeline.generated.resources.history_merge_places_body
import logdate.client.feature.location.timeline.generated.resources.history_merge_places_confirm
import logdate.client.feature.location.timeline.generated.resources.history_merge_places_title
import logdate.client.feature.location.timeline.generated.resources.history_merge_with_another_place
import logdate.client.feature.location.timeline.generated.resources.history_place_default_title
import logdate.client.feature.location.timeline.generated.resources.history_place_name_label
import logdate.client.feature.location.timeline.generated.resources.history_radius_chip
import logdate.client.feature.location.timeline.generated.resources.history_rename
import logdate.client.feature.location.timeline.generated.resources.history_rename_place
import logdate.client.feature.location.timeline.generated.resources.history_save
import logdate.client.feature.location.timeline.generated.resources.history_total_hours
import logdate.client.feature.location.timeline.generated.resources.history_total_minutes
import logdate.client.feature.location.timeline.generated.resources.history_visit_summary_duration
import logdate.client.feature.location.timeline.generated.resources.history_visited_date
import logdate.client.feature.location.timeline.generated.resources.history_visits_count
import logdate.client.feature.location.timeline.generated.resources.history_visits_empty_in_range
import logdate.client.feature.location.timeline.generated.resources.history_visits_heading
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HumanPlaceDetail(
    placeId: String,
    snapshot: LocationHistorySnapshot,
    onVisit: (PlaceVisit) -> Unit,
    onMemory: (Uuid) -> Unit,
    onMerge: (String, SemanticPlace) -> Unit,
    onDismiss: () -> Unit,
    onRename: ((SemanticPlace, String) -> Unit)? = null,
    onUpdateRadius: ((String, Double) -> Unit)? = null,
    onUpdateCategory: ((String, String?) -> Unit)? = null,
) {
    val row = snapshot.placeRows().firstOrNull { placeId in it.sourceIds }
    val semanticPlace = snapshot.places.firstOrNull { it.id == placeId }
    val visits =
        snapshot.items.filterIsInstance<PlaceVisit>().filter {
            (it.place?.id ?: "evidence:${it.evidenceIds.first()}") in row?.sourceIds.orEmpty()
        }
    val confirmedVisits = visits.filter { it.confirmedStay }
    val totalMinutes = confirmedVisits.sumOf { (it.end - it.start).inWholeMinutes.coerceAtLeast(0) }
    val totalHoursText =
        if (totalMinutes >= 60) {
            val tenths = ((totalMinutes / 60.0) * 10.0).roundToInt()
            stringResource(Res.string.history_total_hours, "${tenths / 10}.${tenths % 10}")
        } else {
            stringResource(Res.string.history_total_minutes, totalMinutes.toInt())
        }
    val spanText =
        if (confirmedVisits.isNotEmpty()) {
            val firstDate = confirmedVisits.minOf { it.start }.toReadableDateShort()
            val lastDate = confirmedVisits.maxOf { it.start }.toReadableDateShort()
            if (firstDate == lastDate) {
                stringResource(Res.string.history_visited_date, firstDate)
            } else {
                stringResource(Res.string.history_date_range_span, firstDate, lastDate)
            }
        } else {
            null
        }

    var merging by remember { mutableStateOf(false) }
    var mergeSearch by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<SemanticPlace?>(null) }
    var renaming by remember(placeId) { mutableStateOf(false) }
    val initialPlaceName = semanticPlace?.name ?: row?.title?.literalOrEmpty().orEmpty()
    var editedName by remember(placeId, initialPlaceName) { mutableStateOf(initialPlaceName) }
    var editingRadius by remember(placeId) { mutableStateOf(false) }
    var sliderRadius by remember(placeId, semanticPlace?.radiusMeters) {
        mutableFloatStateOf((semanticPlace?.radiusMeters ?: 75.0).toFloat().coerceIn(30f, 500f))
    }
    var editingCategory by remember(placeId) { mutableStateOf(false) }

    val detail: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            PanelHeader(
                title = row?.title?.asString() ?: stringResource(Res.string.history_place_default_title),
                subtitle = row?.supportingText?.asString(),
                actions = {
                    if (semanticPlace != null && onRename != null) {
                        IconButton(onClick = { renaming = !renaming }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(Res.string.history_rename_place))
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(PlatformIcons.close(), stringResource(Res.string.history_close_place))
                    }
                },
            )
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PanelGroup {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val visitsLabel =
                                pluralStringResource(Res.plurals.history_visits_count, confirmedVisits.size, confirmedVisits.size)
                            Text(
                                stringResource(Res.string.history_visit_summary_duration, visitsLabel, totalHoursText),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            spanText?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        semanticPlace?.category?.let { cat ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(categoryIcon(cat), null, Modifier.size(14.dp))
                                    Text(
                                        EDIT_CATEGORY_PRESETS.firstOrNull { it.key.equals(cat, true) }?.let { stringResource(it.labelRes) }
                                            ?: cat,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }

                if (semanticPlace != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onRename != null) {
                            FilterChip(
                                selected = renaming,
                                onClick = { renaming = !renaming },
                                label = { Text(stringResource(Res.string.history_rename)) },
                            )
                        }
                        if (onUpdateRadius != null) {
                            FilterChip(
                                selected = editingRadius,
                                onClick = { editingRadius = !editingRadius },
                                label = { Text(stringResource(Res.string.history_radius_chip, sliderRadius.roundToInt())) },
                            )
                        }
                        if (onUpdateCategory != null) {
                            FilterChip(
                                selected = editingCategory,
                                onClick = { editingCategory = !editingCategory },
                                label = { Text(stringResource(Res.string.history_category_label)) },
                            )
                        }
                    }
                }

                if (renaming && semanticPlace != null && onRename != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            label = { Text(stringResource(Res.string.history_place_name_label)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            if (editedName.isNotBlank()) {
                                onRename(semanticPlace, editedName.trim())
                                renaming = false
                            }
                        }) { Text(stringResource(Res.string.history_save)) }
                    }
                }

                if (editingRadius && semanticPlace != null && onUpdateRadius != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(Res.string.history_boundary_radius_label, sliderRadius.roundToInt()),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Slider(
                            value = sliderRadius,
                            onValueChange = { sliderRadius = it },
                            onValueChangeFinished = { onUpdateRadius(placeId, sliderRadius.toDouble()) },
                            valueRange = 30f..500f,
                        )
                    }
                }

                if (editingCategory && semanticPlace != null && onUpdateCategory != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EDIT_CATEGORY_PRESETS.forEach { preset ->
                            val selected = semanticPlace.category.equals(preset.key, ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateCategory(placeId, if (selected) null else preset.key) },
                                leadingIcon = { Icon(categoryIcon(preset.key), null, Modifier.size(16.dp)) },
                                label = { Text(stringResource(preset.labelRes)) },
                            )
                        }
                    }
                }

                val visualMemories =
                    row?.memories.orEmpty().filter {
                        (it.kind == HistoryMemoryKind.Image || it.kind == HistoryMemoryKind.Video) && it.thumbnailUri != null
                    }
                if (visualMemories.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visualMemories, key = { it.id.toString() }) { memory ->
                            Surface(onClick = { onMemory(memory.id) }, color = Color.Transparent, shape = MaterialTheme.shapes.small) {
                                HistoryMemoryThumbnail(memory.thumbnailUri.orEmpty(), memory.kind == HistoryMemoryKind.Video, 72.dp)
                            }
                        }
                    }
                }

                Text(stringResource(Res.string.history_visits_heading), style = MaterialTheme.typography.titleMedium)
                if (visits.isEmpty()) Text(stringResource(Res.string.history_visits_empty_in_range))
                visits.sortedByDescending { it.start }.forEach { visit ->
                    TextButton(onClick = { onVisit(visit) }) {
                        Text(
                            stringResource(
                                Res.string.history_visit_summary_duration,
                                visit.start.toReadableDateShort(),
                                visit.toHistoryUi(emptyList()).timeLabel.asString(),
                            ),
                        )
                    }
                }

                val otherMemories = row?.memories.orEmpty().filterNot { it in visualMemories }
                if (otherMemories.isNotEmpty()) {
                    Text(stringResource(Res.string.history_memories_heading), style = MaterialTheme.typography.titleMedium)
                    otherMemories.forEach { HistoryMemoryPreview(it, onMemory) }
                }

                if (snapshot.places.any { it.id == placeId }) {
                    TextButton(onClick = { merging = !merging }) { Text(stringResource(Res.string.history_merge_with_another_place)) }
                    if (merging) {
                        OutlinedTextField(
                            value = mergeSearch,
                            onValueChange = { mergeSearch = it },
                            label = { Text(stringResource(Res.string.history_filter_places_to_merge)) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val query = mergeSearch.trim()
                        snapshot
                            .collectionPlaces()
                            .filter { it.id != placeId && (query.isEmpty() || it.name.contains(query, ignoreCase = true)) }
                            .forEach { place ->
                                TextButton(onClick = { target = place }) { Text(place.name) }
                            }
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.history_done)) }
            }
        }
    }
    detail()
    target?.let { place ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(Res.string.history_merge_places_title)) },
            text = { Text(stringResource(Res.string.history_merge_places_body, place.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onMerge(placeId, place)
                    target = null
                    onDismiss()
                }) { Text(stringResource(Res.string.history_merge_places_confirm)) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text(stringResource(Res.string.history_cancel)) } },
        )
    }
}
