@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.ui.maps.rememberGoogleMapsEnabled
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_picker_drag_hint
import logdate.client.feature.location.timeline.generated.resources.history_picker_map_unavailable
import logdate.client.feature.location.timeline.generated.resources.history_picker_pin_cd
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable
internal actual fun HumanPlacePicker(
    selected: SemanticPlace?,
    onPoint: (Double, Double) -> Unit,
    modifier: Modifier,
    radiusMeters: Double,
    fallbackCoordinate: HistoryCoordinate?,
) {
    if (!rememberGoogleMapsEnabled()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.history_picker_map_unavailable))
        }
        return
    }
    val currentOnPoint by rememberUpdatedState(onPoint)
    val initialCamera =
        remember {
            selected?.let { LatLng(it.latitude, it.longitude) to 16f }
                ?: fallbackCoordinate?.let { LatLng(it.latitude, it.longitude) to 14f }
                ?: (LatLng(20.0, 0.0) to 2f)
        }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(initialCamera.first, initialCamera.second) }

    LaunchedEffect(selected?.id, selected?.latitude, selected?.longitude) {
        val target = selected?.let { LatLng(it.latitude, it.longitude) } ?: return@LaunchedEffect
        val delta = abs(camera.position.target.latitude - target.latitude) + abs(camera.position.target.longitude - target.longitude)
        if (delta > 0.0001) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(target, camera.position.zoom.coerceAtLeast(15f)), 250)
        }
    }

    LaunchedEffect(camera) {
        snapshotFlow { camera.isMoving to camera.cameraMoveStartedReason }
            .distinctUntilChanged()
            .filter { (moving, reason) -> !moving && reason == CameraMoveStartedReason.GESTURE }
            .collect {
                val target = camera.position.target
                currentOnPoint(target.latitude, target.longitude)
            }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.matchParentSize(),
            cameraPositionState = camera,
            onMapClick = { latLng -> currentOnPoint(latLng.latitude, latLng.longitude) },
        ) {
            val circleCenter = selected?.let { LatLng(it.latitude, it.longitude) } ?: camera.position.target
            Circle(
                center = circleCenter,
                radius = radiusMeters.coerceIn(30.0, 500.0),
                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                strokeColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                strokeWidth = 3f,
            )
        }
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = stringResource(Res.string.history_picker_pin_cd),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).padding(bottom = 18.dp).size(36.dp),
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        ) {
            Text(
                stringResource(Res.string.history_picker_drag_hint),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
