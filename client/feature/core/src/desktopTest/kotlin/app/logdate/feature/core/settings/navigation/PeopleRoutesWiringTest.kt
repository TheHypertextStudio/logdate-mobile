@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.navigation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.domain.entities.GetPeopleUseCase
import app.logdate.feature.core.people.ui.FakeDeviceContactsReader
import app.logdate.feature.core.people.ui.FakePeopleStore
import app.logdate.feature.core.people.ui.InMemoryPreferencesDataStore
import app.logdate.feature.core.people.ui.PeopleDirectoryViewModel
import app.logdate.feature.core.people.ui.PeopleInboxViewModel
import app.logdate.feature.core.people.ui.PeopleSettingsViewModel
import app.logdate.feature.core.people.ui.PersonDetailViewModel
import app.logdate.feature.core.settings.ui.PeopleSwitch
import app.logdate.feature.core.settings.ui.ResumedLifecycle
import app.logdate.feature.core.settings.ui.StoredPeopleFlag
import app.logdate.feature.core.settings.ui.peopleSwitch
import org.koin.compose.KoinIsolatedContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * The four People routes are built by the real Settings entry provider. Each one has to wait for
 * the stored switch before it builds its page, because a restored back stack can open any of them
 * whether or not People is on. Each test counts the view models the opened route builds: a page
 * that is built while People is off or unread starts reading entries, which is the failure these
 * tests catch.
 */
@OptIn(ExperimentalTestApi::class)
class PeopleRoutesWiringTest {
    @Test
    fun `with People off the settings route closes once and builds no page`() = assertClosedWhileOff(PeopleSettingsRoute)

    @Test
    fun `with People off the directory route closes once and builds no page`() = assertClosedWhileOff(PeopleDirectoryRoute)

    @Test
    fun `with People off the inbox route closes once and builds no page`() = assertClosedWhileOff(PeopleInboxRoute)

    @Test
    fun `with People off the person route closes once and builds no page`() = assertClosedWhileOff(personRoute)

    @Test
    fun `until the stored switch is read the settings route stays open and builds no page`() = assertWaitingWhileUnread(PeopleSettingsRoute)

    @Test
    fun `until the stored switch is read the directory route stays open and builds no page`() =
        assertWaitingWhileUnread(PeopleDirectoryRoute)

    @Test
    fun `until the stored switch is read the inbox route stays open and builds no page`() = assertWaitingWhileUnread(PeopleInboxRoute)

    @Test
    fun `until the stored switch is read the person route stays open and builds no page`() = assertWaitingWhileUnread(personRoute)

    @Test
    fun `with People on the settings route builds its page and stays open`() = assertShownWhileOn(PeopleSettingsRoute)

    @Test
    fun `with People on the directory route builds its page and stays open`() = assertShownWhileOn(PeopleDirectoryRoute)

    @Test
    fun `with People on the inbox route builds its page and stays open`() = assertShownWhileOn(PeopleInboxRoute)

    @Test
    fun `with People on the person route builds its page and stays open`() = assertShownWhileOn(personRoute)

    private fun assertClosedWhileOff(route: NavKey) =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            val opened = openRoute(route, StoredPeopleFlag(initial = false))
            waitForIdle()

            assertEquals(1, opened.exits)
            assertEquals(0, opened.built.total)
        }

    private fun assertWaitingWhileUnread(route: NavKey) =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            val opened = openRoute(route, StoredPeopleFlag(initial = null))
            waitForIdle()

            assertEquals(0, opened.exits)
            assertEquals(0, opened.built.total)
        }

    private fun assertShownWhileOn(route: NavKey) =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            val opened = openRoute(route, StoredPeopleFlag(initial = true))
            waitForIdle()

            assertEquals(0, opened.exits)
            assertEquals(1, opened.built.total)
        }

    private fun ComposeUiTest.openRoute(
        route: NavKey,
        flags: StoredPeopleFlag,
    ): OpenedRoute {
        val opened = OpenedRoute()
        val provider =
            entryProvider<NavKey> {
                settingsEntries(
                    onBack = { opened.exits++ },
                    onResetApp = {},
                    onNavigateToProfile = {},
                    onNavigateToCloudAccountCreation = {},
                    onNavigateToSignIn = {},
                    onNavigateToEvent = {},
                    onNavigateTo = {},
                )
            }
        setContent {
            val owner = remember { TestViewModelStoreOwner() }
            val koin = remember { koinApplication { modules(peopleModule(flags, opened.built)) } }
            ResumedLifecycle {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    KoinIsolatedContext(koin) {
                        provider(route).Content()
                    }
                }
            }
        }
        return opened
    }
}

private val personRoute = PersonDetailRoute(Uuid.parse("00000000-0000-0000-0000-000000000001"))

private class TestViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

private class OpenedRoute {
    var exits: Int = 0
    val built = BuiltViewModels()
}

private class BuiltViewModels {
    var total: Int = 0
}

private fun peopleModule(
    flags: StoredPeopleFlag,
    built: BuiltViewModels,
) = module {
    val store = FakePeopleStore()
    single<PeopleSwitch> { peopleSwitch(flags) }
    viewModel {
        built.total++
        PeopleSettingsViewModel(
            preferencesDataSource = LogdatePreferencesDataSource(InMemoryPreferencesDataStore()),
            peopleRepository = store,
            peopleContactsRepository = store,
            deviceContactsReader = FakeDeviceContactsReader(),
            inferredPeopleRepository = store,
            contactImportAvailable = false,
        )
    }
    viewModel {
        built.total++
        PeopleDirectoryViewModel(GetPeopleUseCase(store), store)
    }
    viewModel {
        built.total++
        PeopleInboxViewModel(store)
    }
    viewModel {
        built.total++
        PersonDetailViewModel(store, store, eventsAvailable = false)
    }
}
