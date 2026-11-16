package app.logdate.ui.motion

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import app.logdate.client.sensor.GyroOffset
import app.logdate.client.sensor.GyroSensorProvider
import app.logdate.ui.platform.rememberSystemReduceMotion
import kotlin.math.exp
import kotlin.time.TimeSource

/** The device's turn sensor, supplied by the app. Screens drawn without it simply hold still. */
val LocalGyroSensorProvider = staticCompositionLocalOf<GyroSensorProvider?> { null }

/**
 * A small offset, between -1 and 1 on each axis, that follows how the device is being turned and
 * settles back to zero when it is held still.
 *
 * Layers that sit at different depths multiply it by different distances, so nearer things move
 * further and the screen reads as having depth. It stays at zero when the person has asked for
 * reduced motion, when [enabled] is false, and on a device with no turn sensor.
 */
@Composable
fun rememberDeviceParallax(enabled: Boolean = true): State<Offset> {
    val reducedMotion by rememberSystemReduceMotion()
    val sensor = LocalGyroSensorProvider.current.takeIf { enabled && !reducedMotion }
    var target by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(sensor) {
        if (sensor == null) {
            target = Offset.Zero
            return@LaunchedEffect
        }
        var last = TimeSource.Monotonic.markNow()
        sensor.currentValue.collect { rate ->
            val now = TimeSource.Monotonic.markNow()
            target = nextParallax(target, rate, seconds = (now - last).inWholeMicroseconds / 1_000_000f)
            last = now
        }
    }
    return animateOffsetAsState(target, MaterialTheme.motionScheme.slowEffectsSpec(), label = "Device parallax")
}

/**
 * Where the parallax offset moves to after the device has turned at [rate] for [seconds].
 *
 * Turning adds to the offset and time drains it, so a turn shifts the layers and holding the
 * device at any angle lets them drift back to rest. A reading that arrives late counts for at most
 * [LONGEST_STEP_SECONDS], so a stall cannot throw the layers across the screen.
 */
internal fun nextParallax(
    current: Offset,
    rate: GyroOffset,
    seconds: Float,
): Offset {
    val step = seconds.coerceIn(0f, LONGEST_STEP_SECONDS)
    val settle = exp(-step / SETTLE_SECONDS)
    return Offset(
        x = ((current.x + rate.y * TURN_GAIN * step) * settle).coerceIn(-1f, 1f),
        y = ((current.y + rate.x * TURN_GAIN * step) * settle).coerceIn(-1f, 1f),
    )
}

// A turn of about half a radian, a relaxed tilt of the wrist, carries the offset to its limit.
private const val TURN_GAIN = 2f
private const val SETTLE_SECONDS = 1.4f
private const val LONGEST_STEP_SECONDS = 0.1f
