package app.logdate.feature.editor.ui.camera

import kotlin.math.abs
import kotlin.math.pow

/**
 * What the shutter asks the camera to do in response to a gesture.
 */
sealed interface ShutterAction {
    data object TakePhoto : ShutterAction

    data object StartRecording : ShutterAction

    data object StopRecording : ShutterAction

    /** The recording keeps going after the finger lifts. */
    data object LockRecording : ShutterAction

    /** Zoom relative to the zoom level when the gesture started, never below 1. */
    data class Zoom(
        val factor: Float,
    ) : ShutterAction
}

/**
 * Turns one shutter gesture into [ShutterAction]s.
 *
 * - A tap takes a photo.
 * - Holding past the hold timeout records until the finger lifts.
 * - Sliding toward the trailing edge, while holding or straight from rest, locks the recording.
 *   A tap then stops it.
 * - Sliding up while recording zooms in, doubling every [zoomDoublingDistancePx].
 *
 * Drag offsets are measured from where the finger went down. The tracker holds no timers or
 * platform types so the gesture rules stay testable; the UI calls [holdElapsed] when its own
 * hold timeout fires.
 *
 * @param trailingSign 1 for left-to-right layouts and -1 for right-to-left, so the lock sits at
 *   the trailing edge in both.
 */
class ShutterPressTracker(
    private val lockDistancePx: Float,
    private val zoomDoublingDistancePx: Float,
    private val trailingSign: Float,
) {
    private enum class Phase {
        Idle,
        Pressed,
        Holding,
        LockedHeld,
        Locked,
        LockedTapped,
    }

    private var phase = Phase.Idle
    private var lastZoom = 1f

    val isLocked: Boolean
        get() = phase == Phase.LockedHeld || phase == Phase.Locked || phase == Phase.LockedTapped

    val isRecording: Boolean
        get() = phase == Phase.Holding || isLocked

    fun press(): List<ShutterAction> {
        phase =
            when (phase) {
                Phase.Locked -> Phase.LockedTapped
                else -> Phase.Pressed
            }
        lastZoom = 1f
        return emptyList()
    }

    fun holdElapsed(): List<ShutterAction> {
        if (phase != Phase.Pressed) return emptyList()
        phase = Phase.Holding
        return listOf(ShutterAction.StartRecording)
    }

    fun drag(
        dx: Float,
        dy: Float,
    ): List<ShutterAction> {
        val towardLock = dx * trailingSign
        return when (phase) {
            Phase.Pressed -> {
                if (towardLock >= lockDistancePx && abs(dy) < towardLock) {
                    phase = Phase.LockedHeld
                    listOf(ShutterAction.StartRecording, ShutterAction.LockRecording)
                } else {
                    emptyList()
                }
            }

            Phase.Holding -> {
                if (towardLock >= lockDistancePx) {
                    phase = Phase.LockedHeld
                    listOf(ShutterAction.LockRecording)
                } else {
                    zoomFor(dy)
                }
            }

            Phase.LockedHeld -> {
                zoomFor(dy)
            }

            else -> {
                emptyList()
            }
        }
    }

    fun release(): List<ShutterAction> =
        when (phase) {
            Phase.Pressed -> {
                reset(ShutterAction.TakePhoto)
            }

            Phase.Holding, Phase.LockedTapped -> {
                reset(ShutterAction.StopRecording)
            }

            Phase.LockedHeld -> {
                phase = Phase.Locked
                emptyList()
            }

            else -> {
                emptyList()
            }
        }

    /** The pointer was taken away, for example by a parent scroll or the system. */
    fun cancel(): List<ShutterAction> =
        when (phase) {
            Phase.Holding -> {
                reset(ShutterAction.StopRecording)
            }

            Phase.LockedHeld, Phase.LockedTapped -> {
                phase = Phase.Locked
                emptyList()
            }

            Phase.Pressed -> {
                reset()
            }

            else -> {
                emptyList()
            }
        }

    /**
     * A recording started somewhere else, such as the watch remote or an accessibility action.
     * The next tap stops it, as it would after a lock.
     */
    fun recordingStartedElsewhere() {
        if (!isRecording) phase = Phase.Locked
    }

    /** The recording stopped somewhere else, such as an accessibility action or an error. */
    fun recordingEnded() {
        reset()
    }

    private fun zoomFor(dy: Float): List<ShutterAction> {
        val factor = 2f.pow(-dy / zoomDoublingDistancePx).coerceAtLeast(1f)
        if (factor == lastZoom) return emptyList()
        lastZoom = factor
        return listOf(ShutterAction.Zoom(factor))
    }

    private fun reset(vararg actions: ShutterAction): List<ShutterAction> {
        phase = Phase.Idle
        lastZoom = 1f
        return actions.toList()
    }
}
