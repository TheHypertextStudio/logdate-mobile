@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.client.text.UiText
import app.logdate.client.text.asString
import app.logdate.client.text.toUiText
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.TravelMode
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_map_approximate_location
import logdate.client.feature.location.timeline.generated.resources.history_map_no_locations
import logdate.client.feature.location.timeline.generated.resources.history_map_overview_cd
import logdate.client.feature.location.timeline.generated.resources.history_map_places_and_paths
import logdate.client.feature.location.timeline.generated.resources.history_map_recorded_place
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

@Immutable
data class HistoryCoordinate(
    val latitude: Double,
    val longitude: Double,
)

@Immutable
internal data class HistoryMapMarker(
    val id: String,
    val coordinate: HistoryCoordinate,
    val label: UiText,
    val ordinal: Int? = null,
    val category: String? = null,
    val memoryCount: Int = 0,
    val confirmedStay: Boolean = true,
    val uncertaintyRadiusMeters: Float = 0f,
    val visitCount: Int = 1,
)

@Immutable
internal data class HistoryMapPath(
    val id: String,
    val points: List<HistoryCoordinate>,
    val mode: TravelMode = TravelMode.UNKNOWN,
    val isConnector: Boolean = false,
)

@Immutable
internal data class HistoryMapGeometry(
    val markers: List<HistoryMapMarker>,
    val paths: List<HistoryMapPath>,
) {
    val coordinates: List<HistoryCoordinate> get() = markers.map { it.coordinate } + paths.flatMap { it.points }
}

internal fun historyMapGeometry(items: List<LocationDayItem>): HistoryMapGeometry {
    var confirmedOrdinal = 0
    val markers =
        items.filterIsInstance<PlaceVisit>().filter { valid(it.latitude, it.longitude) }.map { visit ->
            val ordinal = if (visit.confirmedStay) ++confirmedOrdinal else null
            val defaultLabelRes =
                if (visit.confirmedStay) {
                    Res.string.history_map_recorded_place
                } else {
                    Res.string.history_map_approximate_location
                }
            HistoryMapMarker(
                id = visit.id,
                coordinate = HistoryCoordinate(visit.latitude, visit.longitude),
                label = visit.place?.name?.toUiText() ?: UiText.Resource(defaultLabelRes),
                ordinal = ordinal,
                category = visit.place?.category,
                memoryCount = visit.memoryIds.size,
                confirmedStay = visit.confirmedStay,
                uncertaintyRadiusMeters = visit.uncertaintyRadiusMeters,
                visitCount = visit.sampleCount.coerceAtLeast(1),
            )
        }
    val paths =
        items.filterIsInstance<JourneyLeg>().mapNotNull { journey ->
            val rawPoints =
                (journey.smoothedRoute.ifEmpty { journey.route })
                    .filter { valid(it.latitude, it.longitude) }
                    .map { HistoryCoordinate(it.latitude, it.longitude) }
            val resolvedPoints =
                when {
                    rawPoints.size >= 2 -> {
                        rawPoints
                    }

                    journey.endpointCoordinates != null -> {
                        val ends = journey.endpointCoordinates!!
                        listOf(
                            HistoryCoordinate(ends.first.first, ends.first.second),
                            HistoryCoordinate(ends.second.first, ends.second.second),
                        ).filter { valid(it.latitude, it.longitude) }
                    }

                    else -> {
                        emptyList()
                    }
                }
            resolvedPoints
                .takeIf { it.size >= 2 }
                ?.let { points ->
                    HistoryMapPath(
                        id = journey.id,
                        points = points,
                        mode = journey.mode,
                        isConnector = journey.route.isEmpty() && journey.smoothedRoute.isEmpty(),
                    )
                }
        }
    return HistoryMapGeometry(markers = markers, paths = paths)
}

internal fun travelModeColor(
    mode: TravelMode,
    isConnector: Boolean,
    darkTheme: Boolean,
): Color {
    if (isConnector) {
        val base = if (darkTheme) Color(0xFF9AA5B1) else Color(0xFF8B949E)
        return base.copy(alpha = 0.45f)
    }
    return when (mode) {
        TravelMode.WALKING -> if (darkTheme) Color(0xFF64DFDF) else Color(0xFF2B7A78)
        TravelMode.RUNNING -> if (darkTheme) Color(0xFFFF8C66) else Color(0xFFD95D39)
        TravelMode.CYCLING -> if (darkTheme) Color(0xFF72B0FF) else Color(0xFF3A86FF)
        TravelMode.VEHICLE, TravelMode.DRIVING -> if (darkTheme) Color(0xFF938EFF) else Color(0xFF4F46E5)
        TravelMode.BUS, TravelMode.TRAIN -> if (darkTheme) Color(0xFFB794F4) else Color(0xFF7C3AED)
        TravelMode.STILL, TravelMode.UNKNOWN -> if (darkTheme) Color(0xFF9AA5B1) else Color(0xFF687381)
    }
}

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
    isPlacesCollection: Boolean = false,
    isToday: Boolean = false,
    replaySample: ReplaySample? = null,
    fallbackCenter: HistoryCoordinate? = null,
    showStatus: Boolean = true,
    onReadyChanged: (Boolean) -> Unit = {},
) {
    if (!LocalInspectionMode.current) {
        NativeHistoryMap(
            items = items,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = modifier,
            onInteraction = onInteraction,
            historyKey = historyKey,
            isPlacesCollection = isPlacesCollection,
            isToday = isToday,
            replaySample = replaySample,
            fallbackCenter = fallbackCenter,
            showStatus = showStatus,
            onReadyChanged = onReadyChanged,
        )
        return
    }
    SideEffect { onReadyChanged(true) }
    val geometry = remember(items) { historyMapGeometry(items) }
    val colors = MaterialTheme.colorScheme
    val darkTheme = colors.surface.luminance() < 0.5f
    val activeItemIndex =
        remember(items, replaySample?.activeItemId) {
            replaySample?.activeItemId?.let { activeId -> items.indexOfFirst { it.id == activeId } } ?: -1
        }
    val itemIndices = remember(items) { items.withIndex().associate { (idx, item) -> item.id to idx } }
    val mapOverviewCd = stringResource(Res.string.history_map_overview_cd)
    Surface(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .semantics { contentDescription = mapOverviewCd },
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerLow,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val coordinates = geometry.coordinates
            if (coordinates.isEmpty()) {
                Text(
                    stringResource(Res.string.history_map_no_locations),
                    Modifier.align(Alignment.Center).padding(16.dp),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@BoxWithConstraints
            }
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            val projection = remember(coordinates, width, height) { historyMapProjection(coordinates, width, height) }
            Spacer(
                Modifier
                    .fillMaxSize()
                    .pointerInput(geometry, projection) {
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
                    }.drawWithCache {
                        val dashPaths =
                            geometry.paths.map { path ->
                                val effect =
                                    when {
                                        path.isConnector -> {
                                            PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx()))
                                        }

                                        path.mode == TravelMode.WALKING -> {
                                            PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
                                        }

                                        path.mode == TravelMode.RUNNING -> {
                                            PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx()))
                                        }

                                        path.mode == TravelMode.CYCLING -> {
                                            PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 5.dp.toPx()))
                                        }

                                        path.mode == TravelMode.UNKNOWN -> {
                                            PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                                        }

                                        else -> {
                                            null
                                        }
                                    }
                                Triple(path, path.points.map(projection::project), effect)
                            }
                        val projectedMarkers = geometry.markers.map { it to projection.project(it.coordinate) }
                        val puckPosition =
                            replaySample
                                ?.takeIf { valid(it.latitude, it.longitude) }
                                ?.let { projection.project(HistoryCoordinate(it.latitude, it.longitude)) }
                        onDrawBehind {
                            dashPaths.forEach { (path, projected, effect) ->
                                val isUpcoming =
                                    activeItemIndex >= 0 && (itemIndices[path.id] ?: Int.MAX_VALUE) > activeItemIndex
                                val strokeColor =
                                    travelModeColor(path.mode, path.isConnector, darkTheme).let { base ->
                                        if (isUpcoming) base.copy(alpha = 0.30f) else base
                                    }
                                val widthPx =
                                    if (path.id == selectedId) {
                                        4.dp.toPx()
                                    } else if (path.isConnector) {
                                        2.dp.toPx()
                                    } else {
                                        3.dp.toPx()
                                    }
                                projected.zipWithNext().forEach { (first, second) ->
                                    drawLine(
                                        color = strokeColor,
                                        start = first,
                                        end = second,
                                        strokeWidth = widthPx,
                                        cap = StrokeCap.Round,
                                        pathEffect = effect,
                                    )
                                }
                            }
                            projectedMarkers.forEach { (marker, position) ->
                                val selected = marker.id == selectedId
                                if (selected && (!marker.confirmedStay || marker.uncertaintyRadiusMeters > 40f)) {
                                    drawCircle(
                                        color = colors.primary.copy(alpha = 0.14f),
                                        radius = 24.dp.toPx(),
                                        center = position,
                                    )
                                    drawCircle(
                                        color = colors.primary.copy(alpha = 0.45f),
                                        radius = 24.dp.toPx(),
                                        center = position,
                                        style = Stroke(width = 1.5.dp.toPx()),
                                    )
                                }
                                if (marker.confirmedStay) {
                                    drawCircle(colors.surface, radius = if (selected) 11.dp.toPx() else 8.dp.toPx(), center = position)
                                    drawCircle(colors.primary, radius = if (selected) 8.dp.toPx() else 5.dp.toPx(), center = position)
                                } else {
                                    drawCircle(colors.tertiary.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = position)
                                    drawCircle(colors.tertiary, radius = 3.dp.toPx(), center = position)
                                }
                            }
                            puckPosition?.let { pos ->
                                drawCircle(colors.primary.copy(alpha = 0.25f), radius = 14.dp.toPx(), center = pos)
                                drawCircle(colors.surface, radius = 9.dp.toPx(), center = pos)
                                drawCircle(colors.primary, radius = 6.dp.toPx(), center = pos)
                            }
                        }
                    },
            )
            val selected = geometry.markers.firstOrNull { it.id == selectedId }
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = colors.surfaceContainerHigh,
            ) {
                Text(
                    selected?.label?.asString() ?: stringResource(Res.string.history_map_places_and_paths),
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
    isPlacesCollection: Boolean = false,
    isToday: Boolean = false,
    replaySample: ReplaySample? = null,
    fallbackCenter: HistoryCoordinate? = null,
    showStatus: Boolean = true,
    onReadyChanged: (Boolean) -> Unit = {},
)
