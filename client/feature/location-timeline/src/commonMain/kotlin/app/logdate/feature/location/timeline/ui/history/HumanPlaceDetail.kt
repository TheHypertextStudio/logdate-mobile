@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.logdate.client.domain.location.history.LocationHistorySnapshot
import app.logdate.client.text.asString
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.ui.common.AlertDialog
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.ui.workspace.PanelHeader
import app.logdate.util.toReadableDateShort
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_cancel
import logdate.client.feature.location.timeline.generated.resources.history_close_place
import logdate.client.feature.location.timeline.generated.resources.history_merge_body
import logdate.client.feature.location.timeline.generated.resources.history_merge_confirm
import logdate.client.feature.location.timeline.generated.resources.history_merge_title
import logdate.client.feature.location.timeline.generated.resources.history_merge_with_another_place
import logdate.client.feature.location.timeline.generated.resources.history_place_detail_title
import logdate.client.feature.location.timeline.generated.resources.history_place_memories
import logdate.client.feature.location.timeline.generated.resources.history_place_no_visits
import logdate.client.feature.location.timeline.generated.resources.history_place_visits
import logdate.client.ui.generated.resources.common_done
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun HumanPlaceDetail(
    placeId: String,
    snapshot: LocationHistorySnapshot,
    onVisit: (PlaceVisit) -> Unit,
    onMemory: (String) -> Unit,
    onMerge: (String, SemanticPlace) -> Unit,
    onDismiss: () -> Unit,
) {
    val row = snapshot.placeRows().firstOrNull { placeId in it.sourceIds }
    val visits =
        snapshot.items.filterIsInstance<PlaceVisit>().filter {
            (it.place?.id ?: "evidence:${it.evidenceIds.first()}") in row?.sourceIds.orEmpty()
        }
    var merging by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<SemanticPlace?>(null) }
    val detail: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            PanelHeader(
                row?.title ?: stringResource(Res.string.history_place_detail_title),
                subtitle = row?.supportingText?.map { it.asString() }?.joinToString("\n"),
                actions = {
                    IconButton(onClick = onDismiss) { Icon(PlatformIcons.close(), stringResource(Res.string.history_close_place)) }
                },
            )
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(Res.string.history_place_visits), style = MaterialTheme.typography.titleMedium)
                if (visits.isEmpty()) Text(stringResource(Res.string.history_place_no_visits))
                visits.forEach { visit ->
                    TextButton(
                        onClick = { onVisit(visit) },
                    ) { Text("${visit.start.toReadableDateShort()} · ${visit.toHistoryUi(emptyList()).timeLabel.asString()}") }
                }
                Text(stringResource(Res.string.history_place_memories), style = MaterialTheme.typography.titleMedium)
                row?.memories?.forEach { HistoryMemoryPreview(it, onMemory) }
                if (snapshot.places.any { it.id == placeId }) {
                    TextButton(onClick = { merging = !merging }) { Text(stringResource(Res.string.history_merge_with_another_place)) }
                    if (merging) {
                        snapshot.collectionPlaces().filter { it.id != placeId }.forEach { place ->
                            TextButton(onClick = { target = place }) { Text(place.name) }
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(UiRes.string.common_done)) }
            }
        }
    }
    detail()
    target?.let { place ->
        AlertDialog(
            onDismissRequest = { target = null },
            title = { Text(stringResource(Res.string.history_merge_title)) },
            text = { Text(stringResource(Res.string.history_merge_body, place.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onMerge(placeId, place)
                    target = null
                    onDismiss()
                }) { Text(stringResource(Res.string.history_merge_confirm)) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text(stringResource(Res.string.history_cancel)) } },
        )
    }
}
