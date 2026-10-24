@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.text.UiText
import app.logdate.client.text.asString
import app.logdate.feature.location.timeline.ui.model.CurrentLocationUiModel
import app.logdate.feature.location.timeline.ui.model.LocationMemoryPreviewUiModel
import app.logdate.feature.location.timeline.ui.model.LocationPlaceUiModel
import app.logdate.ui.maps.rememberGoogleMapsEnabled
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.current_location
import logdate.client.feature.location.timeline.generated.resources.memories_count
import logdate.client.feature.location.timeline.generated.resources.recent_memories
import org.jetbrains.compose.resources.stringResource
import kotlin.math.pow

@Composable
internal actual fun LocationTimelineMap(
    places: List<LocationPlaceUiModel>,
    currentLocation: CurrentLocationUiModel?,
    selectedPlaceId: String?,
    onSelectPlace: (String) -> Unit,
    modifier: Modifier,
) {
    val selectedPlace = places.firstOrNull { it.id == selectedPlaceId } ?: places.firstOrNull()
    val initialPoint = selectedPlace?.toLatLng() ?: currentLocation?.toLatLng() ?: places.firstOrNull()?.toLatLng()
    val googleMapsEnabled = rememberGoogleMapsEnabled()

    if (initialPoint == null) {
        LocationTimelineMapFallback(
            places = places,
            selectedPlace = selectedPlace,
            currentLocation = currentLocation,
            modifier = modifier,
        )
        return
    }

    if (!googleMapsEnabled) {
        LocationTimelineMapFallback(
            places = places,
            selectedPlace = selectedPlace,
            currentLocation = currentLocation,
            modifier = modifier,
        )
        return
    }

    val cameraPositionState =
        rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(initialPoint, if (places.size > 1) 11f else 14f)
        }

    LaunchedEffect(selectedPlaceId, places, currentLocation) {
        val target = selectedPlace?.toLatLng() ?: currentLocation?.toLatLng() ?: places.firstOrNull()?.toLatLng()
        if (target != null) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(target, if (selectedPlace != null) 14f else 11f),
                durationMs = 600,
            )
        }
    }

    val zoom by remember {
        derivedStateOf { cameraPositionState.position.zoom }
    }
    val mapAnnotations by remember(places, selectedPlaceId, zoom) {
        derivedStateOf {
            buildMapAnnotations(
                places = places,
                zoom = zoom,
                selectedPlaceId = selectedPlaceId,
            )
        }
    }

    GoogleMap(
        modifier =
            modifier
                .clip(RoundedCornerShape(24.dp)),
        cameraPositionState = cameraPositionState,
        properties = remember { MapProperties(isMyLocationEnabled = false) },
        uiSettings =
            remember {
                MapUiSettings(
                    compassEnabled = false,
                    mapToolbarEnabled = false,
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                    zoomGesturesEnabled = true,
                    scrollGesturesEnabled = true,
                    tiltGesturesEnabled = false,
                    rotationGesturesEnabled = false,
                )
            },
    ) {
        currentLocation?.let { location ->
            Marker(
                state = MarkerState(position = location.toLatLng()),
                title = location.title.asString(),
                snippet = location.subtitle,
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE),
            )
        }

        mapAnnotations.forEach { annotation ->
            when (annotation) {
                is LocationMapAnnotation.Cluster -> {
                    Marker(
                        state = MarkerState(position = annotation.position),
                        title = stringResource(Res.string.memories_count, annotation.memoryCount),
                        snippet = annotation.placeTitles.map { it.asString() }.joinToString(" • "),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE),
                        onClick = {
                            onSelectPlace(annotation.placeIds.first())
                            cameraPositionState.move(
                                CameraUpdateFactory.newLatLngZoom(
                                    annotation.position,
                                    (zoom + 2f).coerceAtMost(16f),
                                ),
                            )
                            true
                        },
                    )
                }

                is LocationMapAnnotation.Place -> {
                    Marker(
                        state = MarkerState(position = annotation.place.toLatLng()),
                        title = annotation.place.title.asString(),
                        snippet = stringResource(Res.string.memories_count, annotation.place.memoryCount),
                        onClick = {
                            onSelectPlace(annotation.place.id)
                            false
                        },
                    )
                }

                is LocationMapAnnotation.Memory -> {
                    Marker(
                        state = MarkerState(position = annotation.memory.toLatLng()),
                        title = annotation.memory.title,
                        snippet = annotation.placeTitle.asString(),
                        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET),
                        onClick = {
                            onSelectPlace(annotation.placeId)
                            false
                        },
                    )
                }
            }
        }
    }
}

private sealed interface LocationMapAnnotation {
    data class Cluster(
        val position: LatLng,
        val memoryCount: Int,
        val placeTitles: List<UiText>,
        val placeIds: List<String>,
    ) : LocationMapAnnotation

    data class Place(
        val place: LocationPlaceUiModel,
    ) : LocationMapAnnotation

    data class Memory(
        val placeId: String,
        val placeTitle: UiText,
        val memory: LocationMemoryPreviewUiModel,
    ) : LocationMapAnnotation
}

private fun buildMapAnnotations(
    places: List<LocationPlaceUiModel>,
    zoom: Float,
    selectedPlaceId: String?,
): List<LocationMapAnnotation> =
    when {
        zoom < 10f -> {
            clusterPlaces(places, zoom)
        }

        zoom >= 14f && selectedPlaceId != null -> {
            val selectedPlace = places.firstOrNull { it.id == selectedPlaceId }
            buildList {
                selectedPlace?.let { place ->
                    addAll(
                        place.memories.map { memory ->
                            LocationMapAnnotation.Memory(
                                placeId = place.id,
                                placeTitle = place.title,
                                memory = memory,
                            )
                        },
                    )
                }
                addAll(
                    places
                        .filterNot { it.id == selectedPlaceId }
                        .map(LocationMapAnnotation::Place),
                )
            }
        }

        else -> {
            places.map(LocationMapAnnotation::Place)
        }
    }

private fun clusterPlaces(
    places: List<LocationPlaceUiModel>,
    zoom: Float,
): List<LocationMapAnnotation.Cluster> {
    val cellSize = clusterCellSize(zoom)
    return places
        .groupBy { place ->
            val latCell = (place.latitude / cellSize).toInt()
            val lonCell = (place.longitude / cellSize).toInt()
            latCell to lonCell
        }.map { (_, groupedPlaces) ->
            val latitude = groupedPlaces.map(LocationPlaceUiModel::latitude).average()
            val longitude = groupedPlaces.map(LocationPlaceUiModel::longitude).average()
            val memoryCount = groupedPlaces.sumOf(LocationPlaceUiModel::memoryCount)
            LocationMapAnnotation.Cluster(
                position = LatLng(latitude, longitude),
                memoryCount = memoryCount,
                placeTitles = groupedPlaces.take(2).map(LocationPlaceUiModel::title),
                placeIds = groupedPlaces.map(LocationPlaceUiModel::id),
            )
        }
}

private fun clusterCellSize(zoom: Float): Double =
    when {
        zoom < 4f -> 12.0
        zoom < 6f -> 5.0
        zoom < 8f -> 1.5
        zoom < 10f -> 0.4
        else -> (0.15 / 2.0.pow((zoom - 10f).coerceAtLeast(0f).toDouble())).coerceAtLeast(0.01)
    }

@Composable
private fun LocationTimelineMapFallback(
    places: List<LocationPlaceUiModel>,
    selectedPlace: LocationPlaceUiModel?,
    currentLocation: CurrentLocationUiModel?,
    modifier: Modifier = Modifier,
) {
    val primaryPlace = selectedPlace ?: places.firstOrNull()
    val supportingPlaces = places.filterNot { it.id == primaryPlace?.id }.take(2)
    val primaryTitle = (primaryPlace?.title ?: currentLocation?.title)?.asString() ?: stringResource(Res.string.current_location)
    val primarySubtitle = primaryPlace?.subtitle ?: currentLocation?.subtitle.orEmpty()

    Surface(
        modifier = modifier.clip(RoundedCornerShape(24.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        brush =
                            Brush.linearGradient(
                                colors =
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceContainerHighest,
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                    ),
                            ),
                    ),
        ) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 18.dp, end = 18.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(28.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                    ) {
                        Text(
                            text = stringResource(Res.string.recent_memories),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = primaryTitle,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (primarySubtitle.isNotBlank()) {
                        Text(
                            text = primarySubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    primaryPlace?.let { place ->
                        PlaceSummaryPill(
                            title = place.title.asString(),
                            subtitle = stringResource(Res.string.memories_count, place.memoryCount),
                        )
                    } ?: currentLocation?.let { location ->
                        PlaceSummaryPill(
                            title = location.title.asString(),
                            subtitle = location.subtitle ?: "",
                        )
                    }

                    supportingPlaces.forEach { place ->
                        PlaceSummaryPill(
                            title = place.title.asString(),
                            subtitle = stringResource(Res.string.memories_count, place.memoryCount),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceSummaryPill(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun CurrentLocationUiModel.toLatLng(): LatLng = LatLng(latitude, longitude)

private fun LocationPlaceUiModel.toLatLng(): LatLng = LatLng(latitude, longitude)

private fun LocationMemoryPreviewUiModel.toLatLng(): LatLng = LatLng(latitude, longitude)
