package app.logdate.feature.core.settings.account.plan.cleanup

import app.logdate.client.domain.storage.StoredItem
import app.logdate.client.domain.storage.StoredItemsDeletion
import app.logdate.client.domain.storage.StoredKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class StorageCleanupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val large = item(900)
    private val middle = item(400)
    private val small = item(10)
    private val stored = MutableStateFlow(listOf(large, middle, small))

    /** Deletes like the real thing: what goes leaves the list, and [failing] stays. */
    private val deleted = mutableListOf<List<StoredItem>>()
    private var failing = emptySet<Uuid>()

    private fun viewModel() =
        StorageCleanupViewModel(
            kind = StoredKind.Photos,
            observeItems = { stored },
            deleteItems = { items ->
                deleted += items
                val (failed, gone) = items.partition { it.id in failing }
                stored.value = stored.value - gone.toSet()
                StoredItemsDeletion(deleted = gone, failed = failed)
            },
        )

    private fun ready(viewModel: StorageCleanupViewModel) = assertIs<StorageCleanupUiState.Ready>(viewModel.state.value)

    @Test
    fun `the page opens on everything of its kind with nothing chosen`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            assertEquals(StorageCleanupUiState.Loading, viewModel.state.value)
            advanceUntilIdle()

            val state = ready(viewModel)
            assertEquals(listOf(large, middle, small), state.items)
            assertEquals(1_310, state.totalBytes)
            assertTrue(state.selected.isEmpty())
            assertNull(state.outcome)
        }

    @Test
    fun `choosing items adds up what deleting them frees`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.toggle(large.id)
            viewModel.toggle(small.id)

            assertEquals(setOf(large.id, small.id), ready(viewModel).selected)
            assertEquals(910, ready(viewModel).selectedBytes)

            viewModel.toggle(small.id)

            assertEquals(setOf(large.id), ready(viewModel).selected)
            assertEquals(900, ready(viewModel).selectedBytes)
        }

    @Test
    fun `letting go of the selection chooses nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggle(large.id)

            viewModel.clearSelection()

            assertTrue(ready(viewModel).selected.isEmpty())
        }

    @Test
    fun `deleting removes what was chosen and says what was freed`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggle(large.id)
            viewModel.toggle(middle.id)

            viewModel.deleteSelected()
            assertTrue(ready(viewModel).deleting)
            advanceUntilIdle()

            assertEquals(listOf(listOf(large, middle)), deleted)
            val state = ready(viewModel)
            assertFalse(state.deleting)
            assertEquals(listOf(small), state.items)
            assertTrue(state.selected.isEmpty())
            assertEquals(CleanupOutcome(freedBytes = 1_300, deletedCount = 2, failedCount = 0), state.outcome)
        }

    @Test
    fun `what could not be deleted stays chosen and is counted`() =
        runTest(dispatcher) {
            failing = setOf(middle.id)
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggle(large.id)
            viewModel.toggle(middle.id)

            viewModel.deleteSelected()
            advanceUntilIdle()

            val state = ready(viewModel)
            assertEquals(listOf(middle, small), state.items)
            assertEquals(setOf(middle.id), state.selected)
            assertEquals(CleanupOutcome(freedBytes = 900, deletedCount = 1, failedCount = 1), state.outcome)
        }

    @Test
    fun `choosing again clears the last result`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggle(large.id)
            viewModel.deleteSelected()
            advanceUntilIdle()

            viewModel.toggle(small.id)

            assertNull(ready(viewModel).outcome)
        }

    @Test
    fun `the result goes once it has been shown`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggle(large.id)
            viewModel.deleteSelected()
            advanceUntilIdle()

            viewModel.dismissOutcome()

            assertNull(ready(viewModel).outcome)
            assertEquals(listOf(middle, small), ready(viewModel).items)
        }

    @Test
    fun `an item that goes away elsewhere is no longer chosen`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.toggle(large.id)
            viewModel.toggle(small.id)

            stored.value = listOf(middle, small)
            advanceUntilIdle()

            assertEquals(setOf(small.id), ready(viewModel).selected)
        }

    @Test
    fun `deleting with nothing chosen does nothing`() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.deleteSelected()
            advanceUntilIdle()

            assertTrue(deleted.isEmpty())
            assertFalse(ready(viewModel).deleting)
        }

    private fun item(sizeBytes: Long) =
        StoredItem(
            id = Uuid.random(),
            kind = StoredKind.Photos,
            sizeBytes = sizeBytes,
            createdAt = Instant.parse("2026-10-03T09:00:00Z"),
        )
}
