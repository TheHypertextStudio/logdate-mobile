package app.logdate.feature.rewind.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.metadata
import androidx.navigation3.ui.NavDisplay
import app.logdate.feature.rewind.ui.detail.RewindDetailScreen
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.asMetadata
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * A route corresponding to the Rewind overview screen.
 *
 * Here, a user can view a list of past rewinds and select one to view.
 */
@Serializable
data object RewindOverviewRoute : NavKey

/**
 * A route corresponding to the Rewind detail screen.
 *
 * Here, a user can interact with a Rewind.
 */
@Serializable
data class RewindDetailRoute(
    val id: String,
) : NavKey {
    constructor(id: Uuid) : this(id.toString())
}

/** Registers the Rewind detail entry. */
fun EntryProviderScope<NavKey>.rewindDetailEntry(onExitRewind: () -> Unit) {
    taggedEntry<RewindDetailRoute>(
        // RewindStoryView owns its own predictive-back animation — the story panel
        // translates itself off-screen while the backdrop just dims in place. Without
        // this, NavDisplay's own default predictive-pop preview also shrinks/slides the
        // *entire* screen at the same time, so the whole thing looks like it's moving
        // instead of just the card.
        metadata =
            ActivityPresentation.Replacement.asMetadata() +
                metadata {
                    put(NavDisplay.PredictivePopTransitionKey) {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
    ) { route ->
        RewindDetailScreen(
            rewindId = Uuid.parse(route.id),
            onExitRewind = onExitRewind,
        )
    }
}
