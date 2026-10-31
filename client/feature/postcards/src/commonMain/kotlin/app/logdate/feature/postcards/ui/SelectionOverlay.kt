package app.logdate.feature.postcards.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.uuid.Uuid

/**
 * Overlay that captures drag and transform gestures for the selected canvas element.
 *
 * Rendered on top of the viewport (in screen space). Converts screen-space gesture
 * deltas to canvas-space using the viewport scale factor.
 *
 * - Single-finger drag moves the selected element.
 * - Two-finger pinch/rotate transforms and moves the selected element.
 * - Single tap deselects.
 *
 * [onBeginGesture] runs once when a drag or pinch passes the touch slop, before its first
 * move or transform, so each gesture is recorded as one undoable change.
 */
@Composable
fun SelectionOverlay(
    selectedElementId: Uuid,
    viewportScale: Float,
    onBeginGesture: () -> Unit,
    onMoveElement: (elementId: Uuid, dx: Float, dy: Float) -> Unit,
    onTransformElement: (elementId: Uuid, scaleDelta: Float, rotationDelta: Float) -> Unit,
    onDeselect: () -> Unit,
) {
    val density = LocalDensity.current

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(selectedElementId) {
                    detectElementGestures(
                        onGestureStart = onBeginGesture,
                        onGesture = { pan, zoom, rotation ->
                            if (zoom != 1f || rotation != 0f) {
                                onTransformElement(selectedElementId, zoom, rotation)
                            }
                            if (pan != Offset.Zero) {
                                val dxCanvas = pan.x / (viewportScale * density.density)
                                val dyCanvas = pan.y / (viewportScale * density.density)
                                onMoveElement(selectedElementId, dxCanvas, dyCanvas)
                            }
                        },
                    )
                }
                // Tap to deselect — only fires when no drag/transform consumed the gesture
                .pointerInput(selectedElementId) {
                    detectTapGestures {
                        onDeselect()
                    }
                },
    )
}

/**
 * Detects one-finger drags and multi-finger pan, zoom and rotation as a single gesture.
 *
 * Follows `detectTransformGestures`, and adds [onGestureStart], which runs exactly once per
 * gesture when its accumulated motion first passes the touch slop.
 */
private suspend fun PointerInputScope.detectElementGestures(
    onGestureStart: () -> Unit,
    onGesture: (pan: Offset, zoom: Float, rotation: Float) -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var rotation = 0f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val rotationChange = event.calculateRotation()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    rotation += rotationChange
                    pan += panChange

                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val rotationMotion = abs(rotation * PI.toFloat() * centroidSize / 180f)
                    val panMotion = pan.getDistance()

                    if (zoomMotion > touchSlop || rotationMotion > touchSlop || panMotion > touchSlop) {
                        pastTouchSlop = true
                        onGestureStart()
                    }
                }

                if (pastTouchSlop) {
                    if (zoomChange != 1f || rotationChange != 0f || panChange != Offset.Zero) {
                        onGesture(panChange, zoomChange, rotationChange)
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}
