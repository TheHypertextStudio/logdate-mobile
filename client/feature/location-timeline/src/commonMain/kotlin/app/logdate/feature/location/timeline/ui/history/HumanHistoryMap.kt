@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_map_empty
import logdate.client.feature.location.timeline.generated.resources.history_map_overview_description
import logdate.client.feature.location.timeline.generated.resources.history_map_recorded_places
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

internal data class HistoryCoordinate(
    val latitude: Double,
    val longitude: Double,
)

internal data class HistoryMapMarker(
    val id: String,
    val coordinate: HistoryCoordinate,
    val label: String,
)

internal data class HistoryMapPath(
    val id: String,
    val points: List<HistoryCoordinate>,
)

internal data class HistoryMapGeometry(
    val markers: List<HistoryMapMarker>,
    val paths: List<HistoryMapPath>,
) {
    val coordinates: List<HistoryCoordinate> get() = markers.map { it.coordinate } + paths.flatMap { it.points }
}

internal fun historyMapGeometry(items: List<LocationDayItem>): HistoryMapGeometry =
    HistoryMapGeometry(
        markers =
            items.filterIsInstance<PlaceVisit>().filter { valid(it.latitude, it.longitude) }.map {
                HistoryMapMarker(it.id, HistoryCoordinate(it.latitude, it.longitude), it.place?.name ?: "Recorded location")
            },
        paths =
            items.filterIsInstance<JourneyLeg>().mapNotNull { journey ->
                journey.route
                    .filter { valid(it.latitude, it.longitude) }
                    .map { HistoryCoordinate(it.latitude, it.longitude) }
                    .takeIf { it.size >= 2 }
                    ?.let { HistoryMapPath(journey.id, it) }
            },
    )

private fun valid(
    latitude: Double,
    longitude: Double,
) = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0

internal data class HistoryMapProjection(
    val width: Float,
    val height: Float,
    val longitudeScale: Double,
    val middleX: Double,
    val middleY: Double,
    val scale: Double,
) {
    fun project(coordinate: HistoryCoordinate): Offset =
        Offset(
            (width / 2 + (coordinate.longitude * longitudeScale - middleX) * scale).toFloat(),
            (height / 2 - (coordinate.latitude - middleY) * scale).toFloat(),
        )
}

internal fun historyMapProjection(
    all: List<HistoryCoordinate>,
    width: Float,
    height: Float,
): HistoryMapProjection {
    if (all.isEmpty() || width <= 0f || height <= 0f) return HistoryMapProjection(width, height, 1.0, 0.0, 0.0, 0.0)
    val longitudeScale = cos(all.map { it.latitude }.average() * PI / 180).coerceAtLeast(0.01)
    val xs = all.map { it.longitude * longitudeScale }
    val ys = all.map { it.latitude }
    val minX = xs.minOrNull() ?: 0.0
    val maxX = xs.maxOrNull() ?: 0.0
    val minY = ys.minOrNull() ?: 0.0
    val maxY = ys.maxOrNull() ?: 0.0
    val rangeX = maxX - minX
    val rangeY = maxY - minY
    val inset = min(width, height) / 6
    val scale =
        min(
            if (rangeX > 0.0) (width - inset * 2) / rangeX else Double.POSITIVE_INFINITY,
            if (rangeY > 0.0) (height - inset * 2) / rangeY else Double.POSITIVE_INFINITY,
        ).takeIf { it.isFinite() } ?: 1.0
    return HistoryMapProjection(width, height, longitudeScale, (minX + maxX) / 2, (minY + maxY) / 2, scale)
}

@Composable
fun HumanHistoryMap(
    items: List<LocationDayItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    onInteraction: () -> Unit,
    historyKey: String? = null,
    showStatus: Boolean = true,
    onReadyChanged: (Boolean) -> Unit = {},
) {
    if (!LocalInspectionMode.current) {
        NativeHistoryMap(items, selectedId, onSelect, modifier, onInteraction, historyKey, showStatus, onReadyChanged)
        return
    }
    androidx.compose.runtime.SideEffect { onReadyChanged(true) }
    val geometry = remember(items) { historyMapGeometry(items) }
    val colors = MaterialTheme.colorScheme
    val mapDescription = stringResource(Res.string.history_map_overview_description)
    BoxWithConstraints(
        modifier
            .background(colors.surfaceContainerLow, RoundedCornerShape(16.dp))
            .semantics { contentDescription = mapDescription },
    ) {
        val coordinates = geometry.coordinates
        if (coordinates.isEmpty()) {
            Text(
                stringResource(Res.string.history_map_empty),
                Modifier.align(Alignment.Center).padding(16.dp),
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@BoxWithConstraints
        }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val projection = historyMapProjection(coordinates, width, height)
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(geometry, width, height) {
                    detectTapGestures { tap ->
                        val nearest =
                            geometry.markers.minByOrNull { marker ->
                                val position = projection.project(marker.coordinate)
                                hypot((position.x - tap.x).toDouble(), (position.y - tap.y).toDouble())
                            }
                        if (nearest != null) {
                            val position = projection.project(nearest.coordinate)
                            if (hypot((position.x - tap.x).toDouble(), (position.y - tap.y).toDouble()) <= 32.dp.toPx()) {
                                onInteraction()
                                onSelect(nearest.id)
                            }
                        }
                    }
                },
        ) {
            geometry.paths.forEach { path ->
                path.points.zipWithNext().forEach { (first, second) ->
                    drawLine(
                        color = if (path.id == selectedId) colors.primary else colors.tertiary,
                        start = projection.project(first),
                        end = projection.project(second),
                        strokeWidth = if (path.id == selectedId) 4.dp.toPx() else 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            geometry.markers.forEach { marker ->
                val position = projection.project(marker.coordinate)
                val selected = marker.id == selectedId
                drawCircle(colors.surface, radius = if (selected) 11.dp.toPx() else 7.dp.toPx(), center = position)
                drawCircle(colors.primary, radius = if (selected) 8.dp.toPx() else 4.dp.toPx(), center = position)
            }
        }
        val selected = geometry.markers.firstOrNull { it.id == selectedId }
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            color = colors.surfaceContainerHigh,
        ) {
            Text(
                selected?.label ?: stringResource(Res.string.history_map_recorded_places),
                Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal expect fun NativeHistoryMap(
    items: List<LocationDayItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier,
    onInteraction: () -> Unit,
    historyKey: String?,
    showStatus: Boolean,
    onReadyChanged: (Boolean) -> Unit,
)
