@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.logdate.client.text.asString
import app.logdate.ui.audio.MomentAudioCard
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_add_memory
import logdate.client.feature.location.timeline.generated.resources.history_details
import logdate.client.feature.location.timeline.generated.resources.history_open_memory
import org.jetbrains.compose.resources.stringResource

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
                    if (item.kind ==
                        HistoryItemKind.Gap
                    ) {
                        PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                    } else {
                        null
                    },
            )
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            Modifier.padding(top = 16.dp).size(24.dp),
            shape = CircleShape,
            color =
                when {
                    isSelected && item.kind != HistoryItemKind.Visit -> colors.primary
                    else -> colors.surface
                },
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
                        Icon(Icons.Default.Route, null, Modifier.size(18.dp))
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
                    .padding(
                        bottom =
                            if (item.kind ==
                                HistoryItemKind.Visit
                            ) {
                                4.dp
                            } else {
                                0.dp
                            },
                    ).semantics { selected = isSelected },
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
                HistoryConnection(item)
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
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = if (item.isApproximate) 10.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (item.isApproximate) 4.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.timeLabel.asString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.74f),
                )
                Text(
                    item.title.asString(),
                    Modifier.semantics { heading() },
                    style = if (item.isApproximate) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = LocalContentColor.current,
                )
            }
            IconButton(onClick = { actions.onOpenDetail(item.id) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.history_details))
            }
        }
        val supportingText = item.supportingText.asString()
        if (supportingText.isNotBlank()) {
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.74f))
        }
        if (item.memories.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item.memories.forEach { HistoryMemoryPreview(it, actions.onOpenMemory) }
            }
        }
        if (item.memories.isEmpty() && !item.isApproximate && isSelected) {
            TextButton(
                onClick = { actions.onEdit(item.id, HistoryEditAction.AddMemory) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Text(stringResource(Res.string.history_add_memory), Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun HistoryConnection(item: HistoryItemUi) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val title = item.title.asString()
        val supportingText = item.supportingText.asString()
        Text(
            if (item.kind ==
                HistoryItemKind.Journey
            ) {
                listOf(title, supportingText).filter { it.isNotBlank() }.joinToString(" · ")
            } else {
                title
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(item.timeLabel.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun HistoryMemoryPreview(
    memory: HistoryMemoryUi,
    onOpenMemory: (String) -> Unit,
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
            Modifier.heightIn(min = 48.dp).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (memory.thumbnailUri != null) {
                HistoryMemoryThumbnail(memory.thumbnailUri, memory.kind == HistoryMemoryKind.Video)
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    memory.title.asString(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
                    color = LocalContentColor.current.copy(alpha = 0.78f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (memory.supportingText.isNotBlank()) Text(memory.supportingText, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
