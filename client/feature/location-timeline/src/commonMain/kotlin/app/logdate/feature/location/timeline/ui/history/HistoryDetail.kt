@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.PanelGroup
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_add_memory
import logdate.client.feature.location.timeline.generated.resources.history_add_visit
import logdate.client.feature.location.timeline.generated.resources.history_cancel
import logdate.client.feature.location.timeline.generated.resources.history_change_activity
import logdate.client.feature.location.timeline.generated.resources.history_change_place
import logdate.client.feature.location.timeline.generated.resources.history_change_time
import logdate.client.feature.location.timeline.generated.resources.history_close
import logdate.client.feature.location.timeline.generated.resources.history_confirm_delete
import logdate.client.feature.location.timeline.generated.resources.history_delete
import logdate.client.feature.location.timeline.generated.resources.history_delete_body
import logdate.client.feature.location.timeline.generated.resources.history_delete_title
import logdate.client.feature.location.timeline.generated.resources.history_link_memory
import logdate.client.feature.location.timeline.generated.resources.history_visit_settings
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HistoryDetail(
    item: HistoryItemUi,
    actions: HumanLocationHistoryActions,
) {
    var confirmDelete by remember(item.id) { mutableStateOf(false) }
    HumanLocationHistoryDetailContent(item, actions, onRequestDelete = { confirmDelete = true })
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(Res.string.history_delete_title)) },
            text = { Text(stringResource(Res.string.history_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    actions.onEdit(item.id, HistoryEditAction.Delete)
                }) { Text(stringResource(Res.string.history_confirm_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(Res.string.history_cancel)) }
            },
        )
    }
}

@Composable
fun HumanLocationHistoryDetailContent(
    item: HistoryItemUi,
    actions: HumanLocationHistoryActions,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.title.asString(),
                Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = actions.onCloseDetail) { Icon(Icons.Default.Close, stringResource(Res.string.history_close)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.timeLabel.asString(), style = MaterialTheme.typography.labelLarge)
            val supportingText = item.supportingText.asString()
            if (supportingText.isNotBlank()) {
                Text(
                    supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item.memories.forEach { HistoryMemoryPreview(it, actions.onOpenMemory) }
        if (item.kind == HistoryItemKind.Visit) {
            FilledTonalButton(onClick = { actions.onEdit(item.id, HistoryEditAction.AddMemory) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Text(stringResource(Res.string.history_add_memory), Modifier.padding(start = 8.dp))
            }
        }
        Text(
            stringResource(Res.string.history_visit_settings),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HistoryDetailActions(item, actions)
        if (item.kind == HistoryItemKind.Visit) {
            TextButton(onClick = onRequestDelete, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(Res.string.history_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HistoryDetailActions(
    item: HistoryItemUi,
    actions: HumanLocationHistoryActions,
) {
    val edits =
        when (item.kind) {
            HistoryItemKind.Visit -> listOf(HistoryEditAction.ChangePlace, HistoryEditAction.ChangeTime, HistoryEditAction.LinkMemory)
            HistoryItemKind.Journey -> listOf(HistoryEditAction.ChangeActivity, HistoryEditAction.ChangeTime)
            HistoryItemKind.Gap -> listOf(HistoryEditAction.AddVisit)
        }
    PanelGroup {
        Column {
            edits.forEachIndexed { index, action ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Surface(onClick = { actions.onEdit(item.id, action) }, color = Color.Transparent) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (action) {
                                HistoryEditAction.ChangePlace -> Icons.Default.EditLocationAlt
                                HistoryEditAction.ChangeTime -> Icons.Default.Schedule
                                HistoryEditAction.LinkMemory -> Icons.Default.Link
                                HistoryEditAction.ChangeActivity -> Icons.Default.Route
                                else -> Icons.Default.Add
                            },
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(stringResource(action.label()), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private fun HistoryEditAction.label() =
    when (this) {
        HistoryEditAction.ChangePlace -> Res.string.history_change_place
        HistoryEditAction.ChangeActivity -> Res.string.history_change_activity
        HistoryEditAction.ChangeTime -> Res.string.history_change_time
        HistoryEditAction.AddVisit -> Res.string.history_add_visit
        HistoryEditAction.AddMemory -> Res.string.history_add_memory
        HistoryEditAction.LinkMemory -> Res.string.history_link_memory
        HistoryEditAction.Delete -> Res.string.history_delete
    }
