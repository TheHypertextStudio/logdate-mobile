@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.shared.model.location.SemanticPlace

@Composable
internal expect fun HumanPlacePicker(
    selected: SemanticPlace?,
    onPoint: (Double, Double) -> Unit,
    modifier: Modifier,
    radiusMeters: Double = selected?.radiusMeters ?: 75.0,
    fallbackCoordinate: HistoryCoordinate? = null,
)
