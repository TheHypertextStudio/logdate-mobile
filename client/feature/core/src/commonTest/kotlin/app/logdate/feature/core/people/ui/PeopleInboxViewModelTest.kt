package app.logdate.feature.core.people.ui

import app.logdate.client.repository.knowledge.InferredPeopleRepository
import app.logdate.shared.model.InferredPersonCluster
import app.logdate.shared.model.InferredPersonEvidence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class PeopleInboxViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `looks up every suggestion's evidence at the same time`() =
        runTest(dispatcher) {
            val at = Instant.parse("2026-10-01T00:00:00Z")
            val clusters = listOf("Sam", "Ada", "Lee").map { InferredPersonCluster(displayNameHint = it, createdAt = at, lastUpdated = at) }
            val repository = AllAtOnceEvidence(clusters)
            val inbox = PeopleInboxViewModel(repository)
            backgroundScope.launch { inbox.suggestions.collect {} }

            advanceUntilIdle()

            assertEquals(listOf("Sam", "Ada", "Lee"), inbox.suggestions.value.map { it.nameHint })
            assertEquals(
                listOf("Seen with Sam"),
                inbox.suggestions.value
                    .first()
                    .evidencePreview,
            )
        }

    /** Answers no evidence request until every cluster has asked, so one-at-a-time lookups never finish. */
    private class AllAtOnceEvidence(
        private val clusters: List<InferredPersonCluster>,
    ) : InferredPeopleRepository {
        private val requested = mutableSetOf<Uuid>()
        private val everyoneAsked = CompletableDeferred<Unit>()

        override fun observeOpenClusters(): Flow<List<InferredPersonCluster>> = flowOf(clusters)

        override fun observeEvidence(clusterId: Uuid): Flow<List<InferredPersonEvidence>> =
            flow {
                requested += clusterId
                if (requested.size == clusters.size) everyoneAsked.complete(Unit)
                everyoneAsked.await()
                val cluster = clusters.first { it.uid == clusterId }
                emit(
                    listOf(
                        InferredPersonEvidence(
                            clusterId = clusterId,
                            sourceType =
                                app.logdate.shared.model.InferredPersonEvidenceSourceType.entries
                                    .first(),
                            sourceId = Uuid.random(),
                            label = "Seen with ${cluster.displayNameHint}",
                            confidence = 0.9,
                            createdAt = cluster.createdAt,
                        ),
                    ),
                )
            }

        override suspend fun refresh() = Unit

        override suspend fun confirmClusterAsPerson(clusterId: Uuid) = Unit

        override suspend fun rejectCluster(clusterId: Uuid) = Unit
    }
}
