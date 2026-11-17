package app.logdate.feature.core.people.ui

import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.LogdatePreferencesDataSource
import app.logdate.client.repository.knowledge.DeviceContact
import app.logdate.shared.model.InferredPersonCluster
import app.logdate.shared.model.Person
import app.logdate.ui.platform.PlatformKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private val ava = DeviceContact(lookupKey = "ava", displayName = "Ava")
private val sam = DeviceContact(lookupKey = "sam", displayName = "Sam")

/**
 * The People page in Settings has no switch of its own: Labs turns People on, and this page only
 * counts the list, runs the contact import and reports what happened. The import has to carry on
 * by itself once contacts access is granted and has to say where to allow access after a refusal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleSettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var preferences: LogdatePreferencesDataSource

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        preferences = LogdatePreferencesDataSource(InMemoryPreferencesDataStore())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `counts the people in the list and the names waiting for review`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            store.people.value = listOf(Person(name = "Ava"), Person(name = "Sam"))
            store.clusters.value = listOf(cluster("Maya"))
            val viewModel = newViewModel(store)
            val collectJob = startCollecting(viewModel)

            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.totalPeopleCount)
            assertEquals(1, viewModel.uiState.value.pendingReviewCount)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `importing all contacts adds them and reports the result`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava, sam))
            val collectJob = startCollecting(viewModel)

            viewModel.importAllContacts()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(2, state.totalPeopleCount)
            assertEquals(PeopleNotice.ContactsImported(importedCount = 2, updatedCount = 0), state.notice)
            assertFalse(state.isImporting)
            assertEquals(1, store.refreshCalls)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `a failed import says so and adds no one`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore().apply { importFailure = IllegalStateException("contacts unavailable") }
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importAllContacts()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertIs<PeopleNotice.ImportContactsFailed>(state.notice)
            assertEquals(0, state.totalPeopleCount)
            assertFalse(state.isImporting)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `an import still reports its result when looking for names afterwards fails`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore().apply { refreshFailure = IllegalStateException("scan failed") }
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importAllContacts()
            advanceUntilIdle()

            assertEquals(PeopleNotice.ContactsImported(importedCount = 1, updatedCount = 0), viewModel.uiState.value.notice)
            assertFalse(viewModel.uiState.value.isImporting)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `contact import is neither offered nor run where it is unavailable`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava), contactImportAvailable = false)
            val collectJob = startCollecting(viewModel)

            viewModel.importAllContacts()
            viewModel.importWhenContactsAllowed(deniedBefore = false)
            viewModel.onContactsAccessChanged(granted = true, answered = true)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.contactImportAvailable)
            assertEquals(0, store.importCalls)
            assertNull(viewModel.uiState.value.notice)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `contact import is offered on Android only`() {
        assertEquals(listOf(PlatformKind.Android), PlatformKind.entries.filter { it.supportsContactImport() })
    }

    @Test
    fun `an import asked for without contacts access starts as soon as access is granted`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava, sam))
            val collectJob = startCollecting(viewModel)

            viewModel.importWhenContactsAllowed(deniedBefore = false)
            advanceUntilIdle()
            assertEquals(0, store.importCalls)
            assertNull(viewModel.uiState.value.notice)

            viewModel.onContactsAccessChanged(granted = true, answered = true)
            advanceUntilIdle()

            assertEquals(1, store.importCalls)
            assertEquals(2, viewModel.uiState.value.totalPeopleCount)
            assertIs<PeopleNotice.ContactsImported>(viewModel.uiState.value.notice)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `a refused contacts request says where to allow access and imports nothing`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importWhenContactsAllowed(deniedBefore = false)
            viewModel.onContactsAccessChanged(granted = false, answered = true)
            advanceUntilIdle()

            assertIs<PeopleNotice.ContactsAccessNeeded>(viewModel.uiState.value.notice)
            assertEquals(0, store.importCalls)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `a contacts request that has not been answered yet shows nothing`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importWhenContactsAllowed(deniedBefore = false)
            viewModel.onContactsAccessChanged(granted = false, answered = false)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.notice)
            assertEquals(0, store.importCalls)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `asking again after a refusal says where to allow access at once and imports once it is granted`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importWhenContactsAllowed(deniedBefore = true)
            advanceUntilIdle()
            assertIs<PeopleNotice.ContactsAccessNeeded>(viewModel.uiState.value.notice)
            assertEquals(0, store.importCalls)

            viewModel.onContactsAccessChanged(granted = true, answered = true)
            advanceUntilIdle()

            assertEquals(1, store.importCalls)
            assertEquals(PeopleNotice.ContactsImported(importedCount = 1, updatedCount = 0), viewModel.uiState.value.notice)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `contacts access that changes with no import waiting imports nothing`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.onContactsAccessChanged(granted = true, answered = true)
            viewModel.onContactsAccessChanged(granted = false, answered = true)
            advanceUntilIdle()

            assertEquals(0, store.importCalls)
            assertNull(viewModel.uiState.value.notice)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `a refusal ends the wait so a later grant does not import by itself`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importWhenContactsAllowed(deniedBefore = false)
            viewModel.onContactsAccessChanged(granted = false, answered = true)
            viewModel.onContactsAccessChanged(granted = true, answered = true)
            advanceUntilIdle()

            assertEquals(0, store.importCalls)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `opening the page looks for names in entries again`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore()
            val viewModel = newViewModel(store)
            val collectJob = startCollecting(viewModel)

            viewModel.refreshPeople()
            advanceUntilIdle()

            assertEquals(1, store.refreshCalls)
            assertNull(viewModel.uiState.value.notice)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `a failed look for names says the list may be out of date until it is dismissed`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore().apply { refreshFailure = IllegalStateException("scan failed") }
            val viewModel = newViewModel(store)
            val collectJob = startCollecting(viewModel)

            viewModel.refreshPeople()
            advanceUntilIdle()
            assertIs<PeopleNotice.RefreshFailed>(viewModel.uiState.value.notice)

            viewModel.dismissMessage()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.notice)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `an import asked for twice while one is running runs once`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore().apply { importGate = CompletableDeferred() }
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importAllContacts()
            viewModel.importAllContacts()
            advanceUntilIdle()
            assertEquals(1, store.importCalls)
            assertTrue(viewModel.uiState.value.isImporting)

            store.importGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, store.importCalls)

            tearDownViewModel(viewModel, collectJob)
        }

    @Test
    fun `looking for names is skipped while an import runs and while another look is running`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore().apply { importGate = CompletableDeferred() }
            val viewModel = newViewModel(store, contacts = listOf(ava))
            val collectJob = startCollecting(viewModel)

            viewModel.importAllContacts()
            viewModel.refreshPeople()
            advanceUntilIdle()
            assertEquals(0, store.refreshCalls)

            store.importGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, store.refreshCalls)

            store.refreshGate = CompletableDeferred()
            viewModel.refreshPeople()
            viewModel.refreshPeople()
            advanceUntilIdle()
            assertEquals(2, store.refreshCalls)

            store.refreshGate?.complete(Unit)
            advanceUntilIdle()
            viewModel.refreshPeople()
            advanceUntilIdle()
            assertEquals(3, store.refreshCalls)

            tearDownViewModel(viewModel, collectJob)
        }

    private fun newViewModel(
        store: FakePeopleStore,
        contacts: List<DeviceContact> = emptyList(),
        contactImportAvailable: Boolean = true,
    ): PeopleSettingsViewModel =
        PeopleSettingsViewModel(
            preferencesDataSource = preferences,
            peopleRepository = store,
            peopleContactsRepository = store,
            deviceContactsReader = FakeDeviceContactsReader(fullContacts = contacts),
            inferredPeopleRepository = store,
            contactImportAvailable = contactImportAvailable,
        )

    private fun cluster(name: String): InferredPersonCluster =
        InferredPersonCluster(
            displayNameHint = name,
            createdAt = Instant.fromEpochSeconds(0),
            lastUpdated = Instant.fromEpochSeconds(0),
        )

    private fun TestScope.startCollecting(viewModel: PeopleSettingsViewModel): Job = viewModel.uiState.onEach { }.launchIn(this)

    private suspend fun tearDownViewModel(
        viewModel: PeopleSettingsViewModel,
        collectJob: Job,
    ) {
        collectJob.cancelAndJoin()
        val scopeJob = viewModel.viewModelScope.coroutineContext[Job]
        scopeJob?.children?.toList()?.forEach { child -> child.cancelAndJoin() }
    }
}
