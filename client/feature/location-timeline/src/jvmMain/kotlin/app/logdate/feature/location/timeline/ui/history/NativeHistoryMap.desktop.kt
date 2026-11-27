@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.ui.workspace.PanelHeader
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_map_unavailable_on_device
import logdate.client.feature.location.timeline.generated.resources.history_map_unavailable_on_device_body
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun NativeHistoryMap(
    items: List<LocationDayItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    onInteraction: () -> Unit,
    historyKey: String?,
    isPlacesCollection: Boolean,
    isToday: Boolean,
    replaySample: ReplaySample?,
    fallbackCenter: HistoryCoordinate?,
    showStatus: Boolean,
    onReadyChanged: (Boolean) -> Unit,
) {
    SideEffect { onReadyChanged(false) }
    if (showStatus) {
        PanelHeader(
            stringResource(Res.string.history_map_unavailable_on_device),
            modifier,
            stringResource(Res.string.history_map_unavailable_on_device_body),
        )
    }
}
