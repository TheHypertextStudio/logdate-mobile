@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
 */
@Composable
internal fun CaptureShutter(
    isRecording: Boolean,
    enabled: Boolean,
    baseZoom: Float,
    onAction: (ShutterAction) -> Unit,
    modifier: Modifier = Modifier,
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
        val innerSize by animateDpAsState(
            targetValue =
                when {
                    locked -> 30.dp
                    recordingNow -> 58.dp
                    else -> 64.dp
                },
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
            label = "shutterInner",
        )
        val innerCorner by animateDpAsState(
            targetValue = if (locked) 8.dp else 32.dp,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
            label = "shutterCorner",
        )
        val pressScale by animateFloatAsState(
            targetValue =
                if (pressed && !recordingNow) {
                    0.9f
                } else if (holding && !locked) {
                    1.12f
                } else {
                    1f
                },
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
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
            Box(
                modifier =
                    Modifier
                        .size(ShutterSize)
                        .border(width = 4.dp, color = Color.White, shape = CircleShape),
            )
            Surface(
                modifier = Modifier.size(innerSize).clip(RoundedCornerShape(innerCorner)),
                shape = RoundedCornerShape(innerCorner),
                color = if (recordingNow) MaterialTheme.colorScheme.error else Color.White,
                content = {},
            )
        }
    }
}

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
