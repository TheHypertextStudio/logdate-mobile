@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import kotlinx.coroutines.CancellationException
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.camera_recording_locked
import logdate.client.feature.editor.generated.resources.camera_start_recording
import logdate.client.feature.editor.generated.resources.stop_recording
import logdate.client.feature.editor.generated.resources.take_photo
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** How long a press must last before it records instead of taking a photo. */
internal const val SHUTTER_HOLD_TO_RECORD_MS = 300L

private val ShutterSize = 80.dp
private val LockDistance = 72.dp
private val ZoomDoublingDistance = 160.dp

/**
 * The camera's one solid control. Tap for a photo, hold to record, slide toward the trailing
 * edge to keep recording hands-free, and slide up while recording to zoom.
 *
 * Gesture rules live in [ShutterPressTracker]; this composable feeds it pointer events, shows
 * the lock target and zoom readout next to the finger, and plays haptics. Screen readers get the
 * same choices as plain actions, since hold and slide gestures are hard or impossible for some
 * people.
 *
 * @param isRecording whether the camera is recording, from the view model. A recording that ends
 *   elsewhere resets the gesture.
 * @param onAction receives every [ShutterAction]; zoom factors are relative to [baseZoom].
 * @param recordingProgress how far through the current minute the recording is, 0 to 1, traced
 *   around the shutter while it records.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CaptureShutter(
    isRecording: Boolean,
    enabled: Boolean,
    baseZoom: Float,
    onAction: (ShutterAction) -> Unit,
    modifier: Modifier = Modifier,
    recordingProgress: Float = 0f,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val trailingSign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val tracker =
        remember(density, trailingSign) {
            with(density) {
                ShutterPressTracker(
                    lockDistancePx = LockDistance.toPx(),
                    zoomDoublingDistancePx = ZoomDoublingDistance.toPx(),
                    trailingSign = trailingSign,
                )
            }
        }
    var pressed by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var lockPull by remember { mutableFloatStateOf(0f) }
    var zoomFactor by remember { mutableFloatStateOf(1f) }
    val currentOnAction by rememberUpdatedState(onAction)
    val currentBaseZoom by rememberUpdatedState(baseZoom)
    var gestureBaseZoom by remember { mutableFloatStateOf(1f) }

    fun dispatch(actions: List<ShutterAction>) {
        actions.forEach { action ->
            when (action) {
                ShutterAction.TakePhoto -> {
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                }

                ShutterAction.StartRecording -> {
                    holding = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }

                ShutterAction.LockRecording -> {
                    locked = true
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                }

                ShutterAction.StopRecording -> {
                    // A stop from a screen reader bypasses the gesture, so reset it here too.
                    tracker.recordingEnded()
                    holding = false
                    locked = false
                    zoomFactor = 1f
                    haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                }

                is ShutterAction.Zoom -> {
                    zoomFactor = action.factor
                    currentOnAction(ShutterAction.Zoom(gestureBaseZoom * action.factor))
                    return@forEach
                }
            }
            currentOnAction(action)
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording && !tracker.isRecording) {
            tracker.recordingStartedElsewhere()
            locked = true
        }
        if (!isRecording && (holding || locked || tracker.isRecording)) {
            tracker.recordingEnded()
            holding = false
            locked = false
            zoomFactor = 1f
        }
    }

    val takePhotoLabel = stringResource(Res.string.take_photo)
    val stopLabel = stringResource(Res.string.stop_recording)
    val startLabel = stringResource(Res.string.camera_start_recording)
    val lockedLabel = stringResource(Res.string.camera_recording_locked)
    val recordingNow = isRecording || holding || locked

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val pressScale by animateFloatAsState(
            targetValue =
                if (pressed && !recordingNow) {
                    0.88f
                } else if (holding && !locked) {
                    1.1f
                } else {
                    1f
                },
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "shutterScale",
        )

        AnimatedVisibility(
            visible = holding && !locked,
            enter = fadeIn() + scaleIn(initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.6f),
            modifier = Modifier.align(Alignment.Center).offset(x = (LockDistance + 8.dp) * trailingSign),
        ) {
            LockTarget(pull = lockPull)
        }

        AnimatedVisibility(
            visible = zoomFactor > 1.01f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center).offset(y = -(ShutterSize / 2 + 28.dp)),
        ) {
            ZoomReadout(currentBaseZoom)
        }

        Box(
            modifier =
                Modifier
                    .size(ShutterSize)
                    .scale(pressScale)
                    .semantics {
                        role = Role.Button
                        contentDescription = if (recordingNow) stopLabel else takePhotoLabel
                        if (locked) stateDescription = lockedLabel
                        if (!enabled) disabled()
                        onClick {
                            dispatch(if (recordingNow) listOf(ShutterAction.StopRecording) else listOf(ShutterAction.TakePhoto))
                            true
                        }
                        customActions =
                            if (recordingNow) {
                                emptyList()
                            } else {
                                listOf(
                                    CustomAccessibilityAction(startLabel) {
                                        tracker.recordingStartedElsewhere()
                                        dispatch(listOf(ShutterAction.StartRecording, ShutterAction.LockRecording))
                                        true
                                    },
                                )
                            }
                    }.pointerInput(enabled, tracker) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            pressed = true
                            gestureBaseZoom = currentBaseZoom
                            dispatch(tracker.press())
                            val origin = down.position
                            val pressedAt = down.uptimeMillis
                            var lastEventAt = pressedAt
                            var holdFired = false
                            try {
                                while (true) {
                                    val remaining = SHUTTER_HOLD_TO_RECORD_MS - (lastEventAt - pressedAt)
                                    val event =
                                        if (!holdFired && remaining > 0) {
                                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                        } else {
                                            awaitPointerEvent()
                                        }
                                    if (event == null) {
                                        holdFired = true
                                        dispatch(tracker.holdElapsed())
                                        continue
                                    }
                                    val change: PointerInputChange =
                                        event.changes.firstOrNull { it.id == down.id } ?: break
                                    lastEventAt = change.uptimeMillis
                                    if (!change.pressed) {
                                        change.consume()
                                        dispatch(tracker.release())
                                        break
                                    }
                                    val dx = change.position.x - origin.x
                                    val dy = change.position.y - origin.y
                                    lockPull = ((dx * trailingSign) / LockDistance.toPx()).coerceIn(0f, 1f)
                                    dispatch(tracker.drag(dx, dy))
                                    change.consume()
                                }
                            } catch (cancellation: CancellationException) {
                                dispatch(tracker.cancel())
                                throw cancellation
                            } finally {
                                pressed = false
                                lockPull = 0f
                            }
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            ShutterFace(
                recording = recordingNow,
                locked = locked,
                pressed = pressed,
                recordingProgress = recordingProgress,
                modifier = Modifier.size(ShutterSize),
            )
        }
    }
}

/**
 * The shutter's face: a scalloped cookie in the theme's primary color, ready for a photo. It
 * squishes and turns a little under the finger, and while recording it morphs into a rounded
 * square in the error color with the recording's progress traced around it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShutterFace(
    recording: Boolean,
    locked: Boolean,
    pressed: Boolean,
    recordingProgress: Float,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val morph by animateFloatAsState(if (recording) 1f else 0f, spatial, label = "shutterMorph")
    val faceScale by animateFloatAsState(
        targetValue =
            when {
                locked -> 0.5f
                recording -> 0.7f
                else -> 1f
            },
        animationSpec = spatial,
        label = "shutterFaceScale",
    )
    var turns by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pressed) { if (pressed) turns += SHUTTER_PRESS_TURN_DEGREES }
    val rotation by animateFloatAsState(turns, MaterialTheme.motionScheme.slowSpatialSpec(), label = "shutterTurn")
    val fill by animateColorAsState(
        if (recording) colors.error else colors.primaryFixed,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "shutterFill",
    )
    val ringAlpha by animateFloatAsState(if (recording) 1f else 0f, MaterialTheme.motionScheme.defaultEffectsSpec(), label = "shutterRing")
    val sweep by animateFloatAsState(
        recordingProgress.coerceIn(0f, 1f),
        MaterialTheme.motionScheme.slowEffectsSpec(),
        label = "shutterSweep",
    )
    val path = remember { Path() }
    val ringColor = colors.primaryFixed

    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        if (ringAlpha > 0f) {
            val inset = stroke / 2
            drawCircle(
                color = Color.White.copy(alpha = 0.28f * ringAlpha),
                radius = size.minDimension / 2 - inset,
                style = Stroke(stroke),
            )
            drawArc(
                color = ringColor.copy(alpha = ringAlpha),
                startAngle = -90f,
                sweepAngle = 360f * sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        val face = size.minDimension * faceScale
        rotate(rotation * (1f - morph)) {
            translate((size.width - face) / 2, (size.height - face) / 2) {
                drawPath(ShutterShape.outline(morph, face, path), fill)
            }
        }
    }
}

/** The cookie the shutter rests as, and the rounded square it records as, in a unit box. */
private object ShutterShape {
    private val stop =
        RoundedPolygon.rectangle(
            width = 0.74f,
            height = 0.74f,
            rounding = CornerRounding(radius = 0.2f),
            centerX = 0.5f,
            centerY = 0.5f,
        )

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    private val morph = Morph(MaterialShapes.Cookie9Sided, stop)

    fun outline(
        progress: Float,
        size: Float,
        path: Path,
    ): Path {
        path.rewind()
        var started = false
        morph.forEachCubic(progress.coerceIn(0f, 1f)) { cubic ->
            if (!started) {
                path.moveTo(cubic.anchor0X * size, cubic.anchor0Y * size)
                started = true
            }
            path.cubicTo(
                cubic.control0X * size,
                cubic.control0Y * size,
                cubic.control1X * size,
                cubic.control1Y * size,
                cubic.anchor1X * size,
                cubic.anchor1Y * size,
            )
        }
        path.close()
        return path
    }
}

/** How far the cookie turns each time it's pressed, so it reads as alive under the finger. */
private const val SHUTTER_PRESS_TURN_DEGREES = 40f

@Composable
private fun LockTarget(pull: Float) {
    val scale by animateFloatAsState(targetValue = 1f + pull * 0.25f, label = "lockPull")
    Surface(
        modifier = Modifier.size(40.dp).scale(scale),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f + pull * 0.4f),
        contentColor = Color.White,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun ZoomReadout(
    zoom: Float,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
    ) {
        Text(
            text = formatZoom(zoom),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Formats a zoom ratio as "1.4×", with one decimal place. */
internal fun formatZoom(zoom: Float): String {
    val tenths = (zoom * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}×"
}
