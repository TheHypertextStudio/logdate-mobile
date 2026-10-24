package app.logdate.wear.v2.recorder.gesture

/** Where a touch on Record stands. Positions are pixels from the top of the button. */
sealed interface RecordGesturePhase {
    /** No finger down, or the gesture is over. */
    data object Idle : RecordGesturePhase

    /** A finger is down and the long-press timeout hasn't passed; the pager may still take the touch. */
    data class Pressed(
        val downY: Float,
    ) : RecordGesturePhase

    /** Hold to talk: past the timeout, sliding up toward the lock. */
    data class Holding(
        val downY: Float,
        val lockProgress: Float,
    ) : RecordGesturePhase

    /** Slid all the way up: the recording goes on after the finger lifts. */
    data object Locked : RecordGesturePhase
}

/** What happened to the touch. */
sealed interface RecordGestureEvent {
    data class Down(
        val y: Float,
    ) : RecordGestureEvent

    data class Move(
        val y: Float,
    ) : RecordGestureEvent

    /** Before the timeout, a list or pager took the touch, or it dragged past the touch slop. */
    data object Claimed : RecordGestureEvent

    /** The system long-press timeout passed with the finger still down. */
    data object Timeout : RecordGestureEvent

    data object Up : RecordGestureEvent

    /** The system cancelled the touch, as it does for a palm or a window taking focus. */
    data object Cancel : RecordGestureEvent
}

/** What the gesture tells the recorder; see [RecordGestureListener]. */
sealed interface RecordGestureSignal {
    data object Pressed : RecordGestureSignal

    data object Yielded : RecordGestureSignal

    data object Tapped : RecordGestureSignal

    data object Committed : RecordGestureSignal

    data class LockProgress(
        val progress: Float,
    ) : RecordGestureSignal

    data object Locked : RecordGestureSignal

    data object Released : RecordGestureSignal
}

/** The phase after an event, and what to tell the recorder about it. */
data class RecordGestureStep(
    val phase: RecordGesturePhase,
    val signals: List<RecordGestureSignal> = emptyList(),
)

/**
 * The rules of Record's gesture, apart from any touch system.
 *
 * A finger down presses. Before the long-press timeout, a lift is a tap and a claim by the pager
 * or a cancelled touch yields. At the timeout the gesture commits to hold to talk; sliding up
 * [lockDistancePx] then locks it, and a lift or a cancel before that releases it. After the lock
 * the gesture ignores everything until the finger lifts.
 */
class RecordGestureMachine(
    private val lockDistancePx: Float,
) {
    init {
        require(lockDistancePx > 0f) { "The lock distance must be positive, not $lockDistancePx" }
    }

    fun step(
        phase: RecordGesturePhase,
        event: RecordGestureEvent,
    ): RecordGestureStep =
        when (phase) {
            RecordGesturePhase.Idle -> fromIdle(event)
            is RecordGesturePhase.Pressed -> fromPressed(phase, event)
            is RecordGesturePhase.Holding -> fromHolding(phase, event)
            RecordGesturePhase.Locked -> fromLocked(event)
        }

    private fun fromIdle(event: RecordGestureEvent): RecordGestureStep {
        if (event !is RecordGestureEvent.Down) return RecordGestureStep(RecordGesturePhase.Idle)
        return RecordGestureStep(RecordGesturePhase.Pressed(event.y), listOf(RecordGestureSignal.Pressed))
    }

    private fun fromPressed(
        phase: RecordGesturePhase.Pressed,
        event: RecordGestureEvent,
    ): RecordGestureStep =
        when (event) {
            is RecordGestureEvent.Down, is RecordGestureEvent.Move -> {
                RecordGestureStep(phase)
            }

            RecordGestureEvent.Claimed, RecordGestureEvent.Cancel -> {
                RecordGestureStep(RecordGesturePhase.Idle, listOf(RecordGestureSignal.Yielded))
            }

            RecordGestureEvent.Up -> {
                RecordGestureStep(RecordGesturePhase.Idle, listOf(RecordGestureSignal.Tapped))
            }

            RecordGestureEvent.Timeout -> {
                RecordGestureStep(RecordGesturePhase.Holding(phase.downY, lockProgress = 0f), listOf(RecordGestureSignal.Committed))
            }
        }

    private fun fromHolding(
        phase: RecordGesturePhase.Holding,
        event: RecordGestureEvent,
    ): RecordGestureStep =
        when (event) {
            is RecordGestureEvent.Down, RecordGestureEvent.Claimed, RecordGestureEvent.Timeout -> {
                RecordGestureStep(phase)
            }

            is RecordGestureEvent.Move -> {
                slide(phase, event.y)
            }

            RecordGestureEvent.Up, RecordGestureEvent.Cancel -> {
                RecordGestureStep(RecordGesturePhase.Idle, listOf(RecordGestureSignal.Released))
            }
        }

    private fun slide(
        phase: RecordGesturePhase.Holding,
        y: Float,
    ): RecordGestureStep {
        val progress = ((phase.downY - y) / lockDistancePx).coerceIn(0f, 1f)
        if (progress >= 1f) {
            return RecordGestureStep(
                RecordGesturePhase.Locked,
                listOf(RecordGestureSignal.LockProgress(1f), RecordGestureSignal.Locked),
            )
        }
        if (progress == phase.lockProgress) return RecordGestureStep(phase)
        return RecordGestureStep(phase.copy(lockProgress = progress), listOf(RecordGestureSignal.LockProgress(progress)))
    }

    private fun fromLocked(event: RecordGestureEvent): RecordGestureStep =
        when (event) {
            RecordGestureEvent.Up, RecordGestureEvent.Cancel -> RecordGestureStep(RecordGesturePhase.Idle)
            else -> RecordGestureStep(RecordGesturePhase.Locked)
        }
}
