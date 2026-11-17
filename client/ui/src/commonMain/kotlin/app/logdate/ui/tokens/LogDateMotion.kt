package app.logdate.ui.tokens

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * The springs every LogDate surface moves with, on the phone and on the watch.
 *
 * A spring starts from the velocity it already has, so a control that follows a finger, such as
 * the record button sliding up to lock, never jumps when the finger lets go. Use [quick] for small
 * state changes such as a press, and [expressive] only for the moment a screen confirms something,
 * such as a recording locking.
 */
object LogDateMotion {
    private const val QUICK_DAMPING = 1f
    private const val QUICK_STIFFNESS = 1_500f
    private const val EXPRESSIVE_DAMPING = 0.75f
    private const val EXPRESSIVE_STIFFNESS = 400f

    fun <T> quick(): SpringSpec<T> = spring(dampingRatio = QUICK_DAMPING, stiffness = QUICK_STIFFNESS)

    fun <T> expressive(): SpringSpec<T> = spring(dampingRatio = EXPRESSIVE_DAMPING, stiffness = EXPRESSIVE_STIFFNESS)
}
