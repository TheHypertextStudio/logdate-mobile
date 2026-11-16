@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.feature.core.settings.ui.ResumedLifecycle
import app.logdate.feature.core.settings.ui.StoredPeopleFlag
import app.logdate.feature.core.settings.ui.peopleSwitch
import app.logdate.feature.core.settings.ui.rememberPeopleEnabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val PAGE = "People page"

/**
 * The People pages are reachable from a restored back stack whether or not People is on, so the
 * route itself decides: it waits for the stored value, closes while People is off, and shows the
 * page while it is on.
 */
@OptIn(ExperimentalTestApi::class)
class PeopleRouteTest {
    @Test
    fun `until the stored value is read the route shows nothing and stays open`() =
        runDesktopComposeUiTest {
            val flags = StoredPeopleFlag(initial = null)
            var exits = 0
            setContent { Route(flags) { exits++ } }

            onNodeWithText(PAGE).assertDoesNotExist()
            waitForIdle()
            assertEquals(0, exits, "nobody is sent back while the value loads")
        }

    @Test
    fun `a value that loads as on shows the page without closing the route first`() =
        runDesktopComposeUiTest {
            val flags = StoredPeopleFlag(initial = null)
            var exits = 0
            setContent { Route(flags) { exits++ } }
            waitForIdle()

            runOnIdle { flags.set(enabled = true) }

            onNodeWithText(PAGE).assertIsDisplayed()
            assertEquals(0, exits)
        }

    @Test
    fun `with People off the route closes once and never shows the page`() =
        runDesktopComposeUiTest {
            val flags = StoredPeopleFlag(initial = false)
            var exits = 0
            var pageComposed = false
            val switch = peopleSwitch(flags)
            setContent {
                ResumedLifecycle {
                    PeopleRoute(onExit = { exits++ }, peopleSwitch = switch) {
                        pageComposed = true
                        Text(PAGE)
                    }
                }
            }

            onNodeWithText(PAGE).assertDoesNotExist()
            waitForIdle()
            assertEquals(1, exits)
            assertFalse(pageComposed, "a page that is never composed never starts its view model")
        }

    @Test
    fun `with People on the route shows the page and stays open`() =
        runDesktopComposeUiTest {
            var exits = 0
            setContent { Route(StoredPeopleFlag(initial = true)) { exits++ } }

            onNodeWithText(PAGE).assertIsDisplayed()
            assertEquals(0, exits)
        }

    @Test
    fun `a page opened after the stored value was read starts from it`() =
        runDesktopComposeUiTest {
            val switch = peopleSwitch(StoredPeopleFlag(initial = true))
            val seen = mutableListOf<Boolean?>()
            setContent { ResumedLifecycle { seen += rememberPeopleEnabled(switch) } }

            waitForIdle()

            assertEquals(listOf<Boolean?>(true), seen.distinct(), "the page is never composed as not yet loaded")
        }

    @Test
    fun `turning People off while a page is open closes it`() =
        runDesktopComposeUiTest {
            val flags = StoredPeopleFlag(initial = true)
            var exits = 0
            setContent { Route(flags) { exits++ } }
            onNodeWithText(PAGE).assertIsDisplayed()

            runOnIdle { flags.set(enabled = false) }

            onNodeWithText(PAGE).assertDoesNotExist()
            waitForIdle()
            assertEquals(1, exits)
        }
}

@Composable
private fun Route(
    flags: StoredPeopleFlag,
    onExit: () -> Unit,
) {
    ResumedLifecycle {
        PeopleRoute(onExit = onExit, peopleSwitch = remember(flags) { peopleSwitch(flags) }) { Text(PAGE) }
    }
}
