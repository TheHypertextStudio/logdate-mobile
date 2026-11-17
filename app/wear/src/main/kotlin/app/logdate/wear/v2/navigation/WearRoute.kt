package app.logdate.wear.v2.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * The screens of the new watch app. The back stack is saved with the activity's state, so every
 * route is serializable and names a moment by its note ID as a string.
 */
@Serializable
sealed interface WearRoute : NavKey {
    /** Today and the moments of the last 7 days. */
    @Serializable
    data object Home : WearRoute

    @Serializable
    data object Flow : WearRoute

    @Serializable
    data object Memories : WearRoute

    @Serializable
    data object Settings : WearRoute

    /** One moment, by the ID of its note. */
    @Serializable
    data class Moment(
        val id: String,
    ) : WearRoute

    /** Write, Mood, Phone camera and Microphone, opened from Today's +. */
    @Serializable
    data object CaptureSheet : WearRoute

    /** The rest of the library window, past the last 7 days. */
    @Serializable
    data object Archive : WearRoute

    /** The phone's camera, driven from the watch. */
    @Serializable
    data object PhoneCamera : WearRoute
}
