@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.logdate.client.text.asString
import app.logdate.shared.model.location.TravelMode
import app.logdate.ui.theme.Spacing
import app.logdate.ui.theme.WorkspaceSurface
import app.logdate.ui.theme.workspaceContainer
import app.logdate.ui.workspace.workspaceControlBackdrop
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Dot
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PatternItem
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberUpdatedMarkerState
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_fit_day_on_map
import logdate.client.feature.location.timeline.generated.resources.history_marker_approx_distance
import logdate.client.feature.location.timeline.generated.resources.history_satellite_map
import logdate.client.feature.location.timeline.generated.resources.history_standard_map
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun RenderHistoryMarker(
    marker: HistoryMapMarker,
    selected: Boolean,
    darkTheme: Boolean,
    primaryArgb: Int,
    density: Float,
    densityBucket: Int,
    onSelect: () -> Unit,
) {
    val kind = if (marker.confirmedStay) MarkerVisualKind.Confirmed else MarkerVisualKind.Approximate
    val icon =
        remember(kind, marker.ordinal, marker.category, selected, marker.memoryCount, darkTheme, primaryArgb, densityBucket) {
            HistoryMarkerBitmapCache.descriptor(
                MarkerBitmapKey(
                    kind = kind,
                    ordinal = marker.ordinal ?: 0,
                    category = marker.category,
                    selected = selected,
                    memoryCount = marker.memoryCount,
                    clusterCount = 0,
                    darkTheme = darkTheme,
                    primaryArgb = primaryArgb,
                    densityBucket = densityBucket,
                ),
                density,
            )
        }
    val zIndex =
        when {
            selected -> 50f
            marker.memoryCount > 0 -> 15f
            marker.confirmedStay -> 10f
            else -> 5f
        }
    Marker(
        state = rememberUpdatedMarkerState(position = marker.coordinate.toLatLng()),
        title = marker.label.asString(),
        icon = icon,
        zIndex = zIndex,
        anchor = Offset(0.5f, 0.5f),
        onClick = {
            onSelect()
            true
        },
    )
}

@Composable
internal fun BoxScope.HistoryMapFloatingControls(
    useHybridMap: Boolean,
    onFitDay: () -> Unit,
    onToggleMapType: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier =
            Modifier
                .align(Alignment.TopEnd)
                .padding(Spacing.md)
                .workspaceControlBackdrop(true),
        shape = MaterialTheme.shapes.large,
        color = colors.workspaceContainer(WorkspaceSurface.Raised).copy(alpha = 0.94f),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onFitDay, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.CenterFocusStrong,
                    contentDescription = stringResource(Res.string.history_fit_day_on_map),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleMapType, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Layers,
                    contentDescription =
                        stringResource(
                            if (useHybridMap) Res.string.history_standard_map else Res.string.history_satellite_map,
                        ),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
internal fun BoxScope.HistorySelectedMarkerPill(marker: HistoryMapMarker) {
    val colors = MaterialTheme.colorScheme
    val label = marker.label.asString()
    val pillLabel =
        if (marker.uncertaintyRadiusMeters > 0f && !marker.confirmedStay) {
            stringResource(Res.string.history_marker_approx_distance, label, marker.uncertaintyRadiusMeters.roundToInt())
        } else {
            label
        }
    Surface(
        modifier = Modifier.align(Alignment.TopStart).padding(Spacing.md),
        shape = CircleShape,
        color = colors.workspaceContainer(WorkspaceSurface.Raised).copy(alpha = 0.95f),
        tonalElevation = 2.dp,
    ) {
        Text(
            text = pillLabel,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

internal fun patternForMode(
    mode: TravelMode,
    isConnector: Boolean,
): List<PatternItem>? =
    when {
        isConnector -> listOf(Dot(), Gap(16f))
        mode == TravelMode.WALKING -> listOf(Dot(), Gap(10f))
        mode == TravelMode.RUNNING -> listOf(Dash(18f), Gap(8f))
        mode == TravelMode.CYCLING -> listOf(Dash(26f), Gap(10f))
        mode == TravelMode.UNKNOWN -> listOf(Dash(14f), Gap(10f))
        else -> null
    }

internal suspend fun fitDayBounds(
    camera: CameraPositionState,
    coordinates: List<HistoryCoordinate>,
    animate: Boolean,
    reduceMotion: Boolean,
) {
    val points = coordinates.map { it.toLatLng() }.distinct()
    if (points.isEmpty()) return
    val update =
        if (points.size == 1) {
            CameraUpdateFactory.newLatLngZoom(points.first(), 15f)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.builder().apply { points.forEach(::include) }.build(),
                48,
            )
        }
    if (animate && !reduceMotion) camera.animate(update, 350) else camera.move(update)
}

internal suspend fun fitClusterBounds(
    camera: CameraPositionState,
    coordinates: List<HistoryCoordinate>,
    reduceMotion: Boolean,
) {
    val points = coordinates.map { it.toLatLng() }.distinct()
    if (points.isEmpty()) return
    val update =
        if (points.size == 1) {
            CameraUpdateFactory.newLatLngZoom(points.first(), 15f)
        } else {
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.builder().apply { points.forEach(::include) }.build(),
                64,
            )
        }
    if (reduceMotion) camera.move(update) else camera.animate(update, 350)
    if (camera.position.zoom > 15f) {
        camera.move(CameraUpdateFactory.zoomTo(15f))
    }
}

internal fun HistoryCoordinate.toLatLng() = LatLng(latitude, longitude)
