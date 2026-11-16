@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.navigation

import androidx.compose.runtime.Composable
import app.logdate.feature.core.settings.ui.PeopleSwitch
import app.logdate.feature.core.settings.ui.rememberPeopleEnabled
import org.koin.compose.koinInject

/**
 * Shows a People page only while People is turned on in Labs for this installation.
 *
 * Until the stored value has been read for the first time the route renders nothing, so someone
 * who has People on is not sent back while it loads. While People is off the route closes through [onExit], the way a
 * deferred route does, and [content] is never composed, so its view model does not start reading
 * entries. Turning People off closes the pages and leaves the stored people as they are.
 */
@Composable
internal fun PeopleRoute(
    onExit: () -> Unit,
    peopleSwitch: PeopleSwitch = koinInject(),
    content: @Composable () -> Unit,
) {
    when (rememberPeopleEnabled(peopleSwitch)) {
        null -> Unit
        false -> ExitDeferredRoute(onExit)
        true -> content()
    }
}
