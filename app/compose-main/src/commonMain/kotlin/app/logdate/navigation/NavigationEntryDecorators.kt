@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package app.logdate.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import app.logdate.ui.LocalNavAnimatedVisibilityScope

@Composable
internal fun <T : Any> rememberNavAnimatedVisibilityScopeEntryDecorator(): NavEntryDecorator<T> =
    remember {
        NavEntryDecorator { entry ->
            CompositionLocalProvider(
                LocalNavAnimatedVisibilityScope provides LocalNavAnimatedContentScope.current,
            ) {
                entry.Content()
            }
        }
    }
