package app.logdate.feature.core.people.ui

import app.logdate.client.domain.entities.GetPeopleUseCase
import app.logdate.shared.model.Person
import app.logdate.shared.model.PersonOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The list of people shows where each person came from, and says so when looking for names in
 * entries failed instead of showing a list that silently went stale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleDirectoryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the list is unread until it is collected and then names each person with their origin`() =
        runTest(testDispatcher) {
            val ava = Person(name = "Ava", origin = PersonOrigin.CONTACT_FULL)
            val maya = Person(name = "Maya", origin = PersonOrigin.INFERRED)
            val store = FakePeopleStore()
            store.people.value = listOf(ava, maya)
            val viewModel = PeopleDirectoryViewModel(GetPeopleUseCase(store), store)
            assertNull(viewModel.people.value)

            val collectJob = viewModel.people.onEach { }.launchIn(this)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    PeopleDirectoryViewModel.PersonRow(id = ava.uid, name = "Ava", origin = PersonOrigin.CONTACT_FULL),
                    PeopleDirectoryViewModel.PersonRow(id = maya.uid, name = "Maya", origin = PersonOrigin.INFERRED),
                ),
                viewModel.people.value,
            )
            assertEquals(1, store.refreshCalls)
            assertNull(viewModel.notice.value)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `a failed look for names says the list may be out of date until it is dismissed`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore().apply { refreshFailure = IllegalStateException("scan failed") }
            val viewModel = PeopleDirectoryViewModel(GetPeopleUseCase(store), store)
            advanceUntilIdle()

            assertIs<PeopleNotice.RefreshFailed>(viewModel.notice.value)

            viewModel.dismissNotice()

            assertNull(viewModel.notice.value)
        }
}
