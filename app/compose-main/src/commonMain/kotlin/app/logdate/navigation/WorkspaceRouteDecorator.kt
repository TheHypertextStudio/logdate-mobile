@file:Suppress("ktlint:standard:function-naming")

package app.logdate.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntryDecorator
import app.logdate.ui.adaptive.ActivityRouteFrame
import app.logdate.ui.navigation.ActivityPresentation
import app.logdate.ui.navigation.activityPresentation

/** Frames registered reading content without enrolling its activity in Home. */
@Composable
fun <T : Any> rememberWorkspaceRouteDecorator(): NavEntryDecorator<T> =
    remember {
        NavEntryDecorator { entry ->
            when (entry.activityPresentation()) {
                is ActivityPresentation.Collection,
                is ActivityPresentation.Detail,
                -> ActivityRouteFrame { entry.Content() }

                else -> entry.Content()
            }
        }
    }
