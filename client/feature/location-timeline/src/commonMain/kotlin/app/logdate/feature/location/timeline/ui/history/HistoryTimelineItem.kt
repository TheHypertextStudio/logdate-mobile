@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.client.text.asString
import app.logdate.shared.model.location.TravelMode
import app.logdate.ui.audio.MomentAudioCard
import app.logdate.ui.handwriting.HandwritingPreview
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_add_memory
import logdate.client.feature.location.timeline.generated.resources.history_add_visit_chip
import logdate.client.feature.location.timeline.generated.resources.history_details
import logdate.client.feature.location.timeline.generated.resources.history_open_memory
import logdate.client.feature.location.timeline.generated.resources.history_overflow_count
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid

@Composable
internal fun HistoryTimelineItem(
    item: HistoryItemUi,
    isSelected: Boolean,
    actions: HumanLocationHistoryActions,
    modifier: Modifier = Modifier,
    isLast: Boolean = false,
    isFirst: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().drawBehind {
            drawLine(
                colors.outlineVariant,
                Offset(12.dp.toPx(), if (isFirst) 24.dp.toPx() else 0f),
                Offset(12.dp.toPx(), if (isLast) 32.dp.toPx() else size.height),
                2.dp.toPx(),
                pathEffect =
                    if (item.kind == HistoryItemKind.Gap || item.isConnector) {
                        PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    } else {
                        null
                    },
            )
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            Modifier.padding(top = 14.dp).size(24.dp),
            shape = CircleShape,
            color = if (isSelected && item.kind != HistoryItemKind.Visit) colors.primary else colors.surface,
            contentColor = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (item.kind) {
                    HistoryItemKind.Visit -> {
                        Box(
                            Modifier
                                .size(if (isSelected) 12.dp else 8.dp)
                                .background(if (isSelected) colors.primary else colors.outline, CircleShape),
                        )
                    }

                    HistoryItemKind.Journey -> {
                        Icon(travelModeIcon(item.travelMode), null, Modifier.size(18.dp))
                    }

                    HistoryItemKind.Gap -> {
                        Icon(Icons.Default.MoreHoriz, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        Surface(
            onClick = {
                actions.onSelectItem(item.id)
                if (item.kind != HistoryItemKind.Visit) actions.onOpenDetail(item.id)
            },
            modifier =
                Modifier
                    .weight(1f)
                    .padding(bottom = if (item.kind == HistoryItemKind.Visit) 4.dp else 0.dp)
                    .semantics { selected = isSelected },
            shape = RoundedCornerShape(if (item.kind == HistoryItemKind.Visit && isSelected) 24.dp else 12.dp),
            color =
                when {
                    item.kind != HistoryItemKind.Visit -> Color.Transparent
                    isSelected -> colors.primaryContainer
                    else -> colors.surfaceContainerHigh
                },
            contentColor = if (item.kind == HistoryItemKind.Visit && isSelected) colors.onPrimaryContainer else colors.onSurface,
        ) {
            if (item.kind == HistoryItemKind.Visit) {
                HistoryVisit(item, isSelected, actions)
            } else {
                HistoryConnection(item, actions)
            }
        }
    }
}

@Composable
private fun HistoryVisit(
    item: HistoryItemUi,
    isSelected: Boolean,
    actions: HumanLocationHistoryActions,
) {
    val visualMemories =
        item.memories.filter {
            (it.kind == HistoryMemoryKind.Image || it.kind == HistoryMemoryKind.Video) &&
                it.thumbnailUri != null
        }
    val nonVisualMemories = item.memories.filterNot { it in visualMemories }
    val supporting = item.supportingText?.asString().orEmpty()
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = if (item.isApproximate) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (item.isApproximate) 4.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.timeLabel.asString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.74f),
                )
                Text(
                    item.title.asString(),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = LocalContentColor.current,
                )
            }
            IconButton(onClick = { actions.onOpenDetail(item.id) }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.history_details))
            }
        }
        if (supporting.isNotBlank()) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.74f))
        }
        if (isSelected && !item.isApproximate && item.disambiguationCandidates.isNotEmpty() && actions.onConfirmPlace != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.disambiguationCandidates.forEach { chip ->
                    SuggestionChip(
                        onClick = { actions.onConfirmPlace.invoke(item.id, chip.place) },
                        icon = if (chip.isConfirmation) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                        label = { Text(chip.label.asString(), style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
        if (visualMemories.isNotEmpty()) {
            val shown = visualMemories.take(5)
            val overflow = visualMemories.size - shown.size
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                items(shown, key = { it.id }) { memory ->
                    Surface(onClick = { actions.onOpenMemory(memory.id) }, shape = MaterialTheme.shapes.small, color = Color.Transparent) {
                        HistoryMemoryThumbnail(memory.thumbnailUri.orEmpty(), memory.kind == HistoryMemoryKind.Video, 56.dp)
                    }
                }
                if (overflow > 0) {
                    item(key = "overflow") {
                        Surface(
                            onClick = { actions.onOpenDetail(item.id) },
                            modifier = Modifier.size(56.dp),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(Res.string.history_overflow_count, overflow),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (nonVisualMemories.isNotEmpty()) {
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                nonVisualMemories.forEach { HistoryMemoryPreview(it, actions.onOpenMemory) }
            }
        }
        if (item.memories.isEmpty() && !item.isApproximate) {
            TextButton(onClick = { actions.onEdit(item.id, HistoryEditAction.AddMemory) }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Text(stringResource(Res.string.history_add_memory), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun HistoryConnection(
    item: HistoryItemUi,
    actions: HumanLocationHistoryActions,
) {
    val title = item.title.asString()
    val supporting = item.supportingText?.asString().orEmpty()
    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (item.kind == HistoryItemKind.Journey) listOf(title, supporting).filter { it.isNotBlank() }.joinToString(" · ") else title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(item.timeLabel.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.kind == HistoryItemKind.Gap) {
            AssistChip(
                onClick = { actions.onEdit(item.id, HistoryEditAction.AddVisit) },
                leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) },
                label = { Text(stringResource(Res.string.history_add_visit_chip)) },
            )
        }
    }
}

@Composable
internal fun HistoryMemoryPreview(
    memory: HistoryMemoryUi,
    onOpenMemory: (Uuid) -> Unit,
) {
    if (memory.kind == HistoryMemoryKind.Audio && memory.audio != null) {
        Column(Modifier.fillMaxWidth()) {
            MomentAudioCard(audio = memory.audio, timeOfDay = null, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { onOpenMemory(memory.id) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.history_open_memory))
            }
        }
        return
    }
    Surface(
        onClick = { onOpenMemory(memory.id) },
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = LocalContentColor.current,
    ) {
        Row(
            Modifier.heightIn(min = 44.dp).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (memory.thumbnailUri != null) {
                HistoryMemoryThumbnail(memory.thumbnailUri, memory.kind == HistoryMemoryKind.Video, 56.dp)
            } else if (memory.handwriting != null && !memory.handwritingUnreadable) {
                HandwritingPreview(memory.handwriting, Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
            } else {
                Icon(
                    when (memory.kind) {
                        HistoryMemoryKind.Text -> Icons.AutoMirrored.Filled.Notes
                        HistoryMemoryKind.Image -> Icons.Default.Photo
                        HistoryMemoryKind.Audio -> Icons.Default.Mic
                        HistoryMemoryKind.Video -> Icons.Default.Videocam
                    },
                    null,
                    Modifier.size(20.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.78f),
                )
            }
            val supporting = memory.supportingText?.asString().orEmpty()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    memory.title.asString(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = LocalContentColor.current.copy(alpha = 0.78f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supporting.isNotBlank()) Text(supporting, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun travelModeIcon(mode: TravelMode?): ImageVector =
    when (mode) {
        TravelMode.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
        TravelMode.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
        TravelMode.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
        TravelMode.VEHICLE, TravelMode.DRIVING -> Icons.Default.DirectionsCar
        TravelMode.BUS -> Icons.Default.DirectionsBus
        TravelMode.TRAIN -> Icons.Default.Train
        else -> Icons.Default.Route
    }
