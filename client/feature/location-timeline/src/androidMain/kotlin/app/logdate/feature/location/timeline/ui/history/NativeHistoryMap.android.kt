@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.logdate.client.text.asString
import app.logdate.feature.location.timeline.R
import app.logdate.shared.model.location.LocationDayItem
import app.logdate.shared.model.location.TravelMode
import app.logdate.ui.maps.rememberGoogleMapsEnabled
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.workspace.LocalSupportingOverlap
import app.logdate.ui.workspace.PanelHeader
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_map_loaded_state
import logdate.client.feature.location.timeline.generated.resources.history_map_loading_state
import logdate.client.feature.location.timeline.generated.resources.history_map_slow_body
import logdate.client.feature.location.timeline.generated.resources.history_map_slow_title
import logdate.client.feature.location.timeline.generated.resources.history_map_unavailable_body
import logdate.client.feature.location.timeline.generated.resources.history_map_unavailable_title
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

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
    val geometry = remember(items) { historyMapGeometry(items) }
    val enabled = rememberGoogleMapsEnabled()
    var loaded by remember(enabled) { mutableStateOf(false) }
    SideEffect { onReadyChanged(enabled && loaded) }
    if (!enabled) {
        if (showStatus) {
            PanelHeader(
                stringResource(Res.string.history_map_unavailable_title),
                modifier,
                stringResource(Res.string.history_map_unavailable_body),
            )
        } else {
            Box(modifier)
        }
        return
    }
    val initialCamera =
        remember(geometry.coordinates, fallbackCenter) {
            geometry.coordinates.firstOrNull()?.let { it.toLatLng() to 12f }
                ?: fallbackCenter?.let { it.toLatLng() to 11f }
                ?: (LatLng(20.0, 0.0) to 2f)
        }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(initialCamera.first, initialCamera.second) }
    val overlap = LocalSupportingOverlap.current
    val reduceMotion by rememberSystemReduceMotion()
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val densityBucket = (density * 100).roundToInt()
    val colors = MaterialTheme.colorScheme
    val primaryArgb = colors.primary.toArgb()
    val darkTheme = colors.surface.luminance() < 0.5f
    val scope = rememberCoroutineScope()

    val hasLocationPermission =
        remember(isToday) {
            isToday &&
                (
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                )
        }

    var useHybridMap by rememberSaveable { mutableStateOf(false) }
    val mapStyle =
        remember(context, darkTheme) {
            runCatching {
                MapStyleOptions.loadRawResourceStyle(context, if (darkTheme) R.raw.map_style_dark else R.raw.map_style_light)
            }.getOrNull()
        }

    val dayKey = historyKey ?: items.firstOrNull()?.id
    var fitted by rememberSaveable(dayKey) { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    var previousSelection by remember { mutableStateOf(selectedId) }
    var currentZoom by remember { mutableFloatStateOf(camera.position.zoom) }
    var userExploring by remember { mutableStateOf(false) }

    LaunchedEffect(loaded) {
        if (!loaded) {
            delay(15_000)
            timedOut = true
        }
    }
    LaunchedEffect(camera) {
        snapshotFlow { camera.position.zoom }.distinctUntilChanged().collect { currentZoom = it }
    }
    LaunchedEffect(loaded, dayKey, geometry.coordinates.isEmpty()) {
        if (loaded && shouldFitHistoryDay(fitted, geometry.coordinates.isNotEmpty())) {
            fitDayBounds(camera, geometry.coordinates, animate = false, reduceMotion = reduceMotion)
            fitted = true
            userExploring = false
        }
    }
    LaunchedEffect(selectedId, loaded) {
        if (shouldMoveHistoryCamera(loaded, previousSelection, selectedId)) {
            userExploring = false
            val marker = geometry.markers.firstOrNull { it.id == selectedId }
            val path = geometry.paths.firstOrNull { it.id == selectedId }
            when {
                marker != null -> {
                    val update = CameraUpdateFactory.newLatLngZoom(marker.coordinate.toLatLng(), 16f)
                    if (reduceMotion) camera.move(update) else camera.animate(update, 350)
                }

                path != null && path.points.distinct().size >= 2 -> {
                    val bounds = LatLngBounds.builder().apply { path.points.forEach { include(it.toLatLng()) } }.build()
                    val update = CameraUpdateFactory.newLatLngBounds(bounds, 64)
                    if (reduceMotion) camera.move(update) else camera.animate(update, 350)
                }
            }
        }
        previousSelection = advanceHistorySelection(loaded, previousSelection, selectedId)
    }
    LaunchedEffect(camera) {
        snapshotFlow { camera.isMoving && camera.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE }
            .distinctUntilChanged()
            .collect { gestured ->
                if (gestured) {
                    userExploring = true
                    onInteraction()
                }
            }
    }
    LaunchedEffect(replaySample, loaded, userExploring) {
        if (loaded && replaySample != null && !userExploring) {
            val update = CameraUpdateFactory.newLatLng(LatLng(replaySample.latitude, replaySample.longitude))
            if (reduceMotion) camera.move(update) else camera.animate(update, 220)
        }
    }

    val activeItemIndex =
        remember(items, replaySample?.activeItemId) {
            replaySample?.activeItemId?.let { id ->
                items.indexOfFirst {
                    it.id ==
                        id
                }
            }
                ?: -1
        }
    val itemIndices = remember(items) { items.withIndex().associate { (idx, item) -> item.id to idx } }
    val clusters =
        remember(geometry.markers, currentZoom, isPlacesCollection) {
            if (isPlacesCollection) clusterMapMarkers(geometry.markers, currentZoom) else emptyList()
        }

    val mapStateDescription =
        stringResource(if (loaded) Res.string.history_map_loaded_state else Res.string.history_map_loading_state)
    Box(modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize().testTag("history-native-map").semantics { stateDescription = mapStateDescription },
            cameraPositionState = camera,
            contentPadding = PaddingValues(bottom = overlap + 8.dp),
            properties =
                MapProperties(
                    isMyLocationEnabled = hasLocationPermission,
                    mapType = if (useHybridMap) MapType.HYBRID else MapType.NORMAL,
                    mapStyleOptions = if (useHybridMap) null else mapStyle,
                ),
            uiSettings =
                MapUiSettings(
                    compassEnabled = false,
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    mapToolbarEnabled = false,
                ),
            onMapLoaded = {
                loaded = true
                timedOut = false
            },
        ) {
            if (isPlacesCollection) {
                clusters.forEach { cluster ->
                    if (cluster.placeCount > 1) {
                        val icon =
                            remember(cluster.placeCount, cluster.dominantCategory, darkTheme, primaryArgb, densityBucket) {
                                HistoryMarkerBitmapCache.descriptor(
                                    MarkerBitmapKey(
                                        MarkerVisualKind.Cluster,
                                        0,
                                        cluster.dominantCategory,
                                        false,
                                        0,
                                        cluster.placeCount,
                                        darkTheme,
                                        primaryArgb,
                                        densityBucket = densityBucket,
                                    ),
                                    density,
                                )
                            }
                        Marker(
                            state = rememberUpdatedMarkerState(position = cluster.coordinate.toLatLng()),
                            title = cluster.label.asString(),
                            icon = icon,
                            zIndex = 20f,
                            onClick = {
                                userExploring = false
                                onInteraction()
                                scope.launch { fitClusterBounds(camera, cluster.memberCoordinates, reduceMotion) }
                                true
                            },
                        )
                    } else {
                        geometry.markers.firstOrNull { it.id == cluster.id }?.let { marker ->
                            RenderHistoryMarker(marker, marker.id == selectedId, darkTheme, primaryArgb, density, densityBucket) {
                                onInteraction()
                                onSelect(marker.id)
                            }
                        }
                    }
                }
            } else {
                geometry.markers.forEach { marker ->
                    val selected = marker.id == selectedId
                    RenderHistoryMarker(marker, selected, darkTheme, primaryArgb, density, densityBucket) {
                        onInteraction()
                        onSelect(marker.id)
                    }
                    if (selected && (!marker.confirmedStay || marker.uncertaintyRadiusMeters > 40f)) {
                        Circle(
                            center = marker.coordinate.toLatLng(),
                            radius = marker.uncertaintyRadiusMeters.toDouble().coerceAtLeast(25.0),
                            fillColor = colors.primary.copy(alpha = 0.12f),
                            strokeColor = colors.primary.copy(alpha = 0.45f),
                            strokeWidth = 2f,
                        )
                    }
                }
            }

            geometry.paths.forEach { path ->
                val isSelected = path.id == selectedId
                val isUpcoming = activeItemIndex >= 0 && (itemIndices[path.id] ?: Int.MAX_VALUE) > activeItemIndex
                val strokeColor =
                    travelModeColor(
                        path.mode,
                        path.isConnector,
                        darkTheme,
                    ).let { if (isUpcoming) it.copy(alpha = 0.30f) else it }
                val baseWidth =
                    when {
                        path.isConnector -> if (isSelected) 7f else 4f

                        path.mode in
                            setOf(
                                TravelMode.VEHICLE,
                                TravelMode.DRIVING,
                                TravelMode.BUS,
                                TravelMode.TRAIN,
                            )
                        -> if (isSelected) 12f else 8f

                        path.mode == TravelMode.CYCLING -> if (isSelected) 11f else 7f

                        else -> if (isSelected) 10f else 6f
                    }
                Polyline(
                    points = path.points.map { it.toLatLng() },
                    clickable = true,
                    width = baseWidth,
                    color = strokeColor,
                    pattern = remember(path.mode, path.isConnector) { patternForMode(path.mode, path.isConnector) },
                    startCap = RoundCap(),
                    endCap = RoundCap(),
                    jointType = JointType.ROUND,
                    zIndex = if (isSelected) 8f else 2f,
                    onClick = {
                        onInteraction()
                        onSelect(path.id)
                    },
                )
            }

            replaySample?.let { puck ->
                val bearingBucket = ((puck.bearingDegrees / 15f).roundToInt() % 24 + 24) % 24
                val icon =
                    remember(bearingBucket, darkTheme, primaryArgb, densityBucket) {
                        HistoryMarkerBitmapCache.descriptor(
                            MarkerBitmapKey(
                                MarkerVisualKind.ReplayPuck,
                                0,
                                null,
                                true,
                                0,
                                0,
                                darkTheme,
                                primaryArgb,
                                bearingBucket,
                                densityBucket,
                            ),
                            density,
                        )
                    }
                Marker(
                    state = rememberUpdatedMarkerState(position = LatLng(puck.latitude, puck.longitude)),
                    icon = icon,
                    zIndex = 100f,
                    flat = true,
                    anchor = Offset(0.5f, 0.5f),
                    onClick = {
                        onInteraction()
                        true
                    },
                )
            }
        }

        if (geometry.coordinates.isNotEmpty()) {
            HistoryMapFloatingControls(
                useHybridMap = useHybridMap,
                onFitDay = {
                    userExploring = false
                    onInteraction()
                    scope.launch { fitDayBounds(camera, geometry.coordinates, animate = true, reduceMotion = reduceMotion) }
                },
                onToggleMapType = { useHybridMap = !useHybridMap },
            )
        }
        geometry.markers.firstOrNull { it.id == selectedId }?.let { HistorySelectedMarkerPill(it) }
        if (timedOut && showStatus) {
            PanelHeader(
                stringResource(Res.string.history_map_slow_title),
                subtitle = stringResource(Res.string.history_map_slow_body),
            )
        }
    }
}
