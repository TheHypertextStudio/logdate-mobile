@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.logdate.server.logdate

import app.logdate.server.sync.InMemorySyncRepository
import app.logdate.server.sync.StaleWriteException
import app.logdate.shared.model.sync.DeviceId
import app.logdate.shared.model.sync.JournalMergeRequest
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class JournalMergeRecoveryTest {
    @Test
    fun `interrupted started merge can replace a deleted destination without losing saved remote memberships`() =
        runTest {
            val backend = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            var interrupt = true
            val faulty =
                object : LogDateCollectionsRepository by backend {
                    override suspend fun deleteJournal(
                        userId: UUID,
                        id: String,
                        deletedAt: Long,
                    ) {
                        if (interrupt) error("Interrupted after destination associations")
                        backend.deleteJournal(userId, id, deletedAt)
                    }
                }
            val store = InMemoryJournalMergeStore()
            val repository = MergeAwareLogDateCollectionsRepository(faulty, store)
            val user = UUID.randomUUID()
            val source = journal()
            val deleted = journal()
            val replacement = journal()
            listOf(source, deleted, replacement).forEach { repository.upsertJournal(user, it) }
            val remoteOnly = Uuid.random().toString()
            val localOnly = Uuid.random().toString()
            repository.upsertAssociations(user, listOf(association(source.id, remoteOnly)))
            val first = JournalMergeRequest(Uuid.random().toString(), deleted.id, listOf(localOnly))
            assertFailsWith<IllegalStateException> { repository.merge(user, source.id, first) }
            interrupt = false
            repository.deleteJournal(user, deleted.id, 100L)
            assertFailsWith<JournalMergeDestinationMissingException> { repository.merge(user, source.id, first) }
            val next = JournalMergeRequest(Uuid.random().toString(), replacement.id, listOf(localOnly))
            assertEquals(replacement.id, repository.merge(user, source.id, next).destinationId)
            assertEquals(
                setOf(remoteOnly, localOnly),
                repository
                    .listAssociations(user)
                    .filter {
                        it.journalId == replacement.id
                    }.map { it.entryId }
                    .toSet(),
            )
            assertEquals(next.operationId, store.operations(user).single().operationId)
            assertEquals(replacement.id, assertFailsWith<JournalMergedException> { repository.upsertJournal(user, source) }.destinationId)
            assertEquals(replacement.id, repository.merge(user, source.id, next).destinationId)
            assertFailsWith<JournalMergeConflictException> { repository.merge(user, source.id, first) }
        }

    @Test
    fun `late source writes survive interrupted merge destination replacement`() =
        runTest {
            val backend = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            var interrupt = true
            val faulty =
                object : LogDateCollectionsRepository by backend {
                    override suspend fun deleteJournal(
                        userId: UUID,
                        id: String,
                        deletedAt: Long,
                    ) {
                        if (interrupt) error("Interrupted after destination associations")
                        backend.deleteJournal(userId, id, deletedAt)
                    }
                }
            val store = InMemoryJournalMergeStore()
            val repository = MergeAwareLogDateCollectionsRepository(faulty, store)
            val user = UUID.randomUUID()
            val source = journal()
            val deleted = journal()
            val replacement = journal()
            listOf(source, deleted, replacement).forEach { repository.upsertJournal(user, it) }
            val original = Uuid.random().toString()
            val late = Uuid.random().toString()
            val first = JournalMergeRequest(Uuid.random().toString(), deleted.id, listOf(original))
            assertFailsWith<IllegalStateException> { repository.merge(user, source.id, first) }
            repository.upsertAssociations(user, listOf(association(source.id, late)))
            assertEquals(first.contentIds, store.operations(user).single().submittedContentIds)
            assertEquals(
                setOf(original, late),
                store
                    .operations(user)
                    .single()
                    .contentIds
                    .toSet(),
            )
            interrupt = false
            repository.deleteJournal(user, deleted.id, 100L)
            val restarted = MergeAwareLogDateCollectionsRepository(faulty, store)
            val next = JournalMergeRequest(Uuid.random().toString(), replacement.id, listOf(original))
            restarted.merge(user, source.id, next)
            assertEquals(
                setOf(original, late),
                restarted
                    .listAssociations(user)
                    .filter { it.journalId == replacement.id }
                    .map { it.entryId }
                    .toSet(),
            )
            assertEquals(next.contentIds, store.operations(user).single().submittedContentIds)
            assertEquals(replacement.id, restarted.merge(user, source.id, next).destinationId)
        }

    @Test
    fun `late ancestor writes survive an incomplete downstream merge recovery`() =
        runTest {
            val backend = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            var interruptedSource: String? = null
            val faulty =
                object : LogDateCollectionsRepository by backend {
                    override suspend fun deleteJournal(
                        userId: UUID,
                        id: String,
                        deletedAt: Long,
                    ) {
                        if (id == interruptedSource) error("Interrupted after destination associations")
                        backend.deleteJournal(userId, id, deletedAt)
                    }
                }
            val store = InMemoryJournalMergeStore()
            val repository = MergeAwareLogDateCollectionsRepository(faulty, store)
            val user = UUID.randomUUID()
            val ancestor = journal()
            val source = journal()
            val deleted = journal()
            val replacement = journal()
            listOf(ancestor, source, deleted, replacement).forEach { repository.upsertJournal(user, it) }
            val completed = JournalMergeRequest(Uuid.random().toString(), source.id, emptyList())
            repository.merge(user, ancestor.id, completed)
            val completedSnapshot = store.operations(user).single()
            interruptedSource = source.id
            val first = JournalMergeRequest(Uuid.random().toString(), deleted.id, emptyList())
            assertFailsWith<IllegalStateException> { repository.merge(user, source.id, first) }
            val late = Uuid.random().toString()
            repository.upsertAssociations(user, listOf(association(ancestor.id, late)))
            val pending = store.operations(user).single { it.sourceId == source.id }
            assertEquals(first.contentIds, pending.submittedContentIds)
            assertEquals(setOf(late), pending.contentIds.toSet())
            assertEquals(completedSnapshot, store.operations(user).single { it.sourceId == ancestor.id })
            interruptedSource = null
            repository.deleteJournal(user, deleted.id, 100L)
            val next = JournalMergeRequest(Uuid.random().toString(), replacement.id, emptyList())
            repository.merge(user, source.id, next)
            assertEquals(
                setOf(late),
                repository
                    .listAssociations(user)
                    .filter { it.journalId == replacement.id }
                    .map { it.entryId }
                    .toSet(),
            )
            assertEquals(replacement.id, repository.merge(user, ancestor.id, completed).destinationId)
        }

    @Test
    fun `superseded operation cannot take over again when its replacement destination disappears`() =
        runTest {
            val backend = SyncBackedLogDateCollectionsRepository(InMemorySyncRepository())
            val faulty =
                object : LogDateCollectionsRepository by backend {
                    override suspend fun deleteJournal(
                        userId: UUID,
                        id: String,
                        deletedAt: Long,
                    ) {
                        error("Interrupted before completion")
                    }
                }
            val store = InMemoryJournalMergeStore()
            val repository = MergeAwareLogDateCollectionsRepository(faulty, store)
            val user = UUID.randomUUID()
            val source = journal()
            val firstDestination = journal()
            val secondDestination = journal()
            listOf(source, firstDestination, secondDestination).forEach { backend.upsertJournal(user, it) }
            val first = JournalMergeRequest(Uuid.random().toString(), firstDestination.id, emptyList())
            assertFailsWith<IllegalStateException> { repository.merge(user, source.id, first) }
            backend.deleteJournal(user, firstDestination.id, 100L)
            val replacement = JournalMergeRequest(Uuid.random().toString(), secondDestination.id, emptyList())
            assertFailsWith<IllegalStateException> { repository.merge(user, source.id, replacement) }
            assertFailsWith<IllegalStateException> { repository.merge(user, source.id, replacement) }
            backend.deleteJournal(user, secondDestination.id, 200L)
            assertFailsWith<StaleWriteException> { backend.upsertJournal(user, firstDestination) }
            assertFailsWith<JournalMergeConflictException> { repository.merge(user, source.id, first) }
            assertEquals(replacement.operationId, store.operations(user).single().operationId)
        }

    @Test
    fun `rejected destination replacement preserves operation ID history before any canonical writes`() =
        runTest {
            val repository =
                MergeAwareLogDateCollectionsRepository(
                    SyncBackedLogDateCollectionsRepository(InMemorySyncRepository()),
                    InMemoryJournalMergeStore(),
                )
            val user = UUID.randomUUID()
            val source = journal()
            val survivor = journal()
            repository.upsertJournal(user, source)
            repository.upsertJournal(user, survivor)
            val first = JournalMergeRequest(Uuid.random().toString(), Uuid.random().toString(), emptyList())
            assertFailsWith<JournalMergeDestinationMissingException> { repository.merge(user, source.id, first) }
            val second = first.copy(operationId = Uuid.random().toString(), destinationId = Uuid.random().toString())
            assertFailsWith<JournalMergeDestinationMissingException> { repository.merge(user, source.id, second) }
            assertFailsWith<JournalMergeConflictException> { repository.merge(user, source.id, first.copy(destinationId = survivor.id)) }
            assertEquals(
                survivor.id,
                repository
                    .merge(
                        user,
                        source.id,
                        second.copy(operationId = Uuid.random().toString(), destinationId = survivor.id),
                    ).destinationId,
            )
        }

    private fun journal() = LogDateJournal(Uuid.random().toString(), "Journal", "Description", 1, 1, 0, DeviceId("device"))

    private fun association(
        journal: String,
        content: String,
    ) = LogDateAssociation(journal, content, 1, 0, DeviceId("device"))
}
