@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.permissions.ContactsPermissionState
import app.logdate.client.repository.knowledge.DeviceContact
import app.logdate.feature.core.settings.ui.ResumedLifecycle
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val IMPORT = "Import all contacts"
private val ava = DeviceContact(lookupKey = "ava", displayName = "Ava")
private const val ACCESS_NEEDED = "LogDate needs permission to read your contacts"

/**
 * The People page decides what its contact import does from the system's contacts permission: it
 * imports straight away when access is granted, asks for access when it is not and imports once
 * the grant arrives, and says where to allow access after a refusal.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PeopleSettingsScreenTest {
    private val store = FakePeopleStore()
    private var permissionRequests = 0

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `with contacts access granted the import runs at once`() =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            setContent { Screen(permission(hasPermission = true, permissionRequested = true)) }

            onNodeWithText(IMPORT).performClick()
            waitForIdle()

            assertEquals(1, store.importCalls)
            assertEquals(0, permissionRequests)
        }

    @Test
    fun `without contacts access the import asks for it and runs once it is granted`() =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            var state by mutableStateOf(permission(hasPermission = false, permissionRequested = false))
            setContent { Screen(state) }

            onNodeWithText(IMPORT).performClick()
            waitForIdle()
            assertEquals(1, permissionRequests)
            assertEquals(0, store.importCalls)

            runOnIdle { state = permission(hasPermission = true, permissionRequested = true) }
            waitForIdle()

            assertEquals(1, store.importCalls)
        }

    @Test
    fun `a refusal shows where to allow contacts access and imports nothing`() =
        runDesktopComposeUiTest(width = 411, height = 1600) {
            var state by mutableStateOf(permission(hasPermission = false, permissionRequested = false))
            setContent { Screen(state) }

            onNodeWithText(IMPORT).performClick()
            waitForIdle()
            runOnIdle { state = permission(hasPermission = false, permissionRequested = true) }
            waitForIdle()

            onNodeWithText(ACCESS_NEEDED, substring = true).assertExists()
            assertEquals(0, store.importCalls)
        }

    private fun permission(
        hasPermission: Boolean,
        permissionRequested: Boolean,
    ) = ContactsPermissionState(
        hasPermission = hasPermission,
        shouldShowRationale = false,
        permissionRequested = permissionRequested,
        requestPermission = { permissionRequests++ },
    )

    @Composable
    private fun Screen(permissionState: ContactsPermissionState) {
        val viewModel =
            remember {
                PeopleSettingsViewModel(
                    preferencesDataSource = LogdatePreferencesDataSource(InMemoryPreferencesDataStore()),
                    peopleRepository = store,
                    peopleContactsRepository = store,
                    deviceContactsReader = FakeDeviceContactsReader(fullContacts = listOf(ava)),
                    inferredPeopleRepository = store,
                    contactImportAvailable = true,
                )
            }
        ResumedLifecycle {
            LogDateTheme(dynamicColor = false, darkTheme = false) {
                PeopleSettingsScreen(
                    onBack = {},
                    onBrowsePeople = {},
                    onOpenReviewInbox = {},
                    contactsPermissionState = permissionState,
                    viewModel = viewModel,
                )
            }
        }
    }
}
