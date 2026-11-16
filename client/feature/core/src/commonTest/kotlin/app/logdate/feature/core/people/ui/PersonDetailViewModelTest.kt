package app.logdate.feature.core.people.ui

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private val writtenAt = Instant.fromEpochSeconds(0)

/**
 * A person's page lists every linked entry and is the only place a person can be removed from the
 * list, so removal has to reach the repository, close the page when it worked, and keep the person
 * on screen with a message when it did not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonDetailViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val maya = Person(name = "Maya")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading shows the person with every linked entry`() =
        runTest(testDispatcher) {
            val entries = List(7) { index -> entry("Entry $index about Maya") }
            val store = storeWithMaya().apply { linkedEntries.value = entries }
            val viewModel = PersonDetailViewModel(store, store, eventsAvailable = false)

            viewModel.load(maya.uid)
            advanceUntilIdle()

            val state = viewModel.uiState().value
            assertEquals(maya, state.person)
            assertEquals(entries, state.linkedEntries)
            assertFalse(state.isLoading)
            assertNull(state.notice)
            assertEquals(1, store.refreshCalls)
        }

    @Test
    fun `linked events are shown only while Events is part of the release`() =
        runTest(testDispatcher) {
            val store = storeWithMaya()

            val withoutEvents = PersonDetailViewModel(store, store, eventsAvailable = false)
            val withEvents = PersonDetailViewModel(store, store, eventsAvailable = true)
            withoutEvents.load(maya.uid)
            withEvents.load(maya.uid)
            advanceUntilIdle()

            assertFalse(withoutEvents.uiState().value.showEvents)
            assertTrue(withEvents.uiState().value.showEvents)
        }

    @Test
    fun `without an override linked events follow whether Events is available for launch`() {
        val store = storeWithMaya()

        assertEquals(FeatureFlag.EVENTS.availableForLaunch, PersonDetailViewModel(store, store).uiState().value.showEvents)
    }

    @Test
    fun `removing the person removes them and marks the page as finished`() =
        runTest(testDispatcher) {
            val store = storeWithMaya()
            val viewModel = PersonDetailViewModel(store, store, eventsAvailable = false)
            viewModel.load(maya.uid)
            advanceUntilIdle()

            viewModel.remove()
            advanceUntilIdle()

            val state = viewModel.uiState().value
            assertEquals(listOf(maya.uid), store.removedPeople)
            assertTrue(state.isRemoved)
            assertFalse(state.isRemoving)
            assertEquals(maya, state.person)
            assertNull(state.notice)
        }

    @Test
    fun `a removal that fails keeps the person and says so and keeps following them`() =
        runTest(testDispatcher) {
            val store = storeWithMaya().apply { removeFailure = IllegalStateException("write failed") }
            val viewModel = PersonDetailViewModel(store, store, eventsAvailable = false)
            viewModel.load(maya.uid)
            advanceUntilIdle()

            viewModel.remove()
            advanceUntilIdle()

            val failed = viewModel.uiState().value
            assertIs<PeopleNotice.RemovePersonFailed>(failed.notice)
            assertFalse(failed.isRemoved)
            assertFalse(failed.isRemoving)
            assertEquals(maya, failed.person)

            val renamed = maya.copy(name = "Maya Hassan")
            store.people.value = listOf(renamed)
            advanceUntilIdle()

            assertEquals(renamed, viewModel.uiState().value.person)
        }

    @Test
    fun `removing does nothing before a person is loaded`() =
        runTest(testDispatcher) {
            val store = storeWithMaya()
            val viewModel = PersonDetailViewModel(store, store, eventsAvailable = false)

            viewModel.remove()
            advanceUntilIdle()

            assertEquals(emptyList(), store.removedPeople)
            assertFalse(viewModel.uiState().value.isRemoved)
        }

    @Test
    fun `a failed look for names still shows the person and says the entries may be out of date`() =
        runTest(testDispatcher) {
            val store = storeWithMaya().apply { refreshFailure = IllegalStateException("scan failed") }
            val viewModel = PersonDetailViewModel(store, store, eventsAvailable = false)

            viewModel.load(maya.uid)
            advanceUntilIdle()

            val state = viewModel.uiState().value
            assertEquals(maya, state.person)
            assertIs<PeopleNotice.RefreshFailed>(state.notice)

            viewModel.dismissNotice()

            assertNull(viewModel.uiState().value.notice)
        }

    @Test
    fun `a person who cannot be read is reported as not loaded`() =
        runTest(testDispatcher) {
            val store = storeWithMaya().apply { profileFailure = IllegalStateException("read failed") }
            val viewModel = PersonDetailViewModel(store, store, eventsAvailable = false)

            viewModel.load(maya.uid)
            advanceUntilIdle()

            val state = viewModel.uiState().value
            assertNull(state.person)
            assertFalse(state.isLoading)
        }

    private fun storeWithMaya(): FakePeopleStore = FakePeopleStore().apply { people.value = listOf(maya) }

    private fun entry(text: String): JournalNote =
        JournalNote.Text(
            creationTimestamp = writtenAt,
            lastUpdated = writtenAt,
            content = text,
        )
}
