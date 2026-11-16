package app.logdate.feature.core.people.ui

import app.logdate.shared.model.InferredPersonCluster
import app.logdate.shared.model.InferredPersonEvidence
import app.logdate.shared.model.InferredPersonEvidenceSourceType
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val epoch = Instant.fromEpochSeconds(0)

/**
 * The review inbox is where every suggested name is accepted or rejected, so a decision that could
 * not be saved has to be said on the page instead of leaving the suggestion looking untouched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleInboxViewModelTest {
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
    fun `lists each suggested name with up to two different lines it was found in`() =
        runTest(testDispatcher) {
            val maya = cluster("Maya")
            val store = FakePeopleStore()
            store.clusters.value = listOf(maya)
            store.evidence.value =
                listOf(
                    evidence(maya.uid, "Lunch with Maya"),
                    evidence(maya.uid, "Lunch with Maya"),
                    evidence(maya.uid, "Maya called"),
                    evidence(maya.uid, "Walked with Maya"),
                    evidence(maya.uid, label = null),
                )
            val viewModel = PeopleInboxViewModel(store)
            val collectJob = viewModel.suggestions.onEach { }.launchIn(this)

            advanceUntilIdle()

            assertEquals(
                listOf(
                    PeopleInboxViewModel.SuggestionUiState(
                        id = maya.uid,
                        nameHint = "Maya",
                        evidencePreview = listOf("Lunch with Maya", "Maya called"),
                    ),
                ),
                viewModel.suggestions.value,
            )
            assertEquals(1, store.refreshCalls)

            collectJob.cancelAndJoin()
        }

    @Test
    fun `adding a suggested name confirms it`() =
        runTest(testDispatcher) {
            val maya = cluster("Maya")
            val store = FakePeopleStore()
            store.clusters.value = listOf(maya)
            val viewModel = PeopleInboxViewModel(store)

            viewModel.confirm(maya.uid)
            advanceUntilIdle()

            assertEquals(listOf(maya.uid), store.confirmedClusters)
            assertNull(viewModel.notice.value)
        }

    @Test
    fun `a name that could not be added says so`() =
        runTest(testDispatcher) {
            val maya = cluster("Maya")
            val store = FakePeopleStore().apply { confirmFailure = IllegalStateException("write failed") }
            store.clusters.value = listOf(maya)
            val viewModel = PeopleInboxViewModel(store)

            viewModel.confirm(maya.uid)
            advanceUntilIdle()

            assertIs<PeopleNotice.AddSuggestionFailed>(viewModel.notice.value)
            assertEquals(emptyList(), store.confirmedClusters)
        }

    @Test
    fun `rejecting a suggested name rejects it`() =
        runTest(testDispatcher) {
            val maya = cluster("Maya")
            val store = FakePeopleStore()
            store.clusters.value = listOf(maya)
            val viewModel = PeopleInboxViewModel(store)

            viewModel.dismiss(maya.uid)
            advanceUntilIdle()

            assertEquals(listOf(maya.uid), store.rejectedClusters)
            assertNull(viewModel.notice.value)
        }

    @Test
    fun `a name that could not be rejected says so`() =
        runTest(testDispatcher) {
            val maya = cluster("Maya")
            val store = FakePeopleStore().apply { rejectFailure = IllegalStateException("write failed") }
            store.clusters.value = listOf(maya)
            val viewModel = PeopleInboxViewModel(store)

            viewModel.dismiss(maya.uid)
            advanceUntilIdle()

            assertIs<PeopleNotice.DismissSuggestionFailed>(viewModel.notice.value)
            assertEquals(emptyList(), store.rejectedClusters)
        }

    @Test
    fun `a failed look for names says the list may be out of date until it is dismissed`() =
        runTest(testDispatcher) {
            val store = FakePeopleStore().apply { refreshFailure = IllegalStateException("scan failed") }
            val viewModel = PeopleInboxViewModel(store)
            advanceUntilIdle()

            assertIs<PeopleNotice.RefreshFailed>(viewModel.notice.value)

            viewModel.dismissNotice()

            assertNull(viewModel.notice.value)
        }

    @Test
    fun `a second tap while a name is being added does not add it twice`() =
        runTest(testDispatcher) {
            val maya = cluster("Maya")
            val gate = CompletableDeferred<Unit>()
            val store = FakePeopleStore().apply { confirmGate = gate }
            store.clusters.value = listOf(maya)
            val viewModel = PeopleInboxViewModel(store)

            viewModel.confirm(maya.uid)
            viewModel.confirm(maya.uid)
            viewModel.dismiss(maya.uid)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf(maya.uid), store.confirmedClusters)
            assertEquals(emptyList(), store.rejectedClusters)
        }

    @Test
    fun `a new decision clears what an earlier failure said`() =
        runTest(testDispatcher) {
            val maya = cluster("Maya")
            val store = FakePeopleStore().apply { confirmFailure = IllegalStateException("write failed") }
            store.clusters.value = listOf(maya)
            val viewModel = PeopleInboxViewModel(store)
            viewModel.confirm(maya.uid)
            advanceUntilIdle()

            store.confirmFailure = null
            viewModel.confirm(maya.uid)
            advanceUntilIdle()

            assertNull(viewModel.notice.value)
            assertEquals(listOf(maya.uid), store.confirmedClusters)
        }

    private fun cluster(name: String): InferredPersonCluster =
        InferredPersonCluster(
            displayNameHint = name,
            createdAt = epoch,
            lastUpdated = epoch,
        )

    private fun evidence(
        clusterId: Uuid,
        label: String?,
    ): InferredPersonEvidence =
        InferredPersonEvidence(
            clusterId = clusterId,
            sourceType = InferredPersonEvidenceSourceType.ENTRY_TEXT,
            sourceId = Uuid.random(),
            label = label,
            confidence = 1.0,
            createdAt = epoch,
        )
}
