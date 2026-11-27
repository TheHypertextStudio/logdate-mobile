@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.shared.model.location.SemanticPlace
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_picker_desktop_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun HumanPlacePicker(
    selected: SemanticPlace?,
    onPoint: (Double, Double) -> Unit,
    modifier: Modifier,
    radiusMeters: Double,
    fallbackCoordinate: HistoryCoordinate?,
) {
    Text(stringResource(Res.string.history_picker_desktop_unavailable), modifier)
}
