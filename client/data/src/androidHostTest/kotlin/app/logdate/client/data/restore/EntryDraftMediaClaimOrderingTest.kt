package app.logdate.client.data.restore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.logdate.client.data.notes.drafts.AndroidLocalEntryDraftStore
import app.logdate.client.data.notes.drafts.OfflineFirstEntryDraftRepository
import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import app.logdate.client.media.storage.AndroidMediaClaimFence
import app.logdate.client.media.storage.MediaClaimFence
import app.logdate.client.repository.journals.EntryDraft
import app.logdate.client.repository.journals.PendingMediaRecord
import app.logdate.client.repository.journals.PendingMediaType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.uuid.Uuid

/** Deterministic lock-order regression, independent of any enrollment timeout attribution. */
class EntryDraftMediaClaimOrderingTest {
    @Test
    fun `retained worker reads the draft while an editor save waits for its real media claim`() =
        runBlocking {
            fixture { root, scope ->
                val actualFence = AndroidMediaClaimFence(File(root, "private"))
                val writerRequestedClaim = CompletableDeferred<Unit>()
                var observeWriter = false
                val writerFence =
                    object : MediaClaimFence by actualFence {
                        override suspend fun <T> withClaim(block: suspend () -> T): T {
                            if (observeWriter) writerRequestedClaim.complete(Unit)
                            return actualFence.withClaim(block)
                        }
                    }
                val store =
                    AndroidLocalEntryDraftStore(
                        PreferenceDataStoreFactory.create(scope = scope) { File(root, "entry.preferences_pb") },
                        claimFence = writerFence,
                    )
                val original = draft()
                store.saveDraft(original)
                val repository = OfflineFirstEntryDraftRepository(store, scope)
                assertEquals(listOf(original), repository.getDraftSnapshotsForRecovery())
                val held = CompletableDeferred<Unit>()
                val edited = original.copy(markId = "writer edit")
                observeWriter = true
                val completed =
                    withTimeoutOrNull(3_000) {
                        coroutineScope {
                            val worker =
                                launch(Dispatchers.IO) {
                                    RetainedJournalReconciliationLock.prepareThenWithDeliveryGate(gate(), prepare = {}) {
                                        RetainedJournalReconciliationLock.withMediaClaim(actualFence) {
                                            held.complete(Unit)
                                            writerRequestedClaim.await()
                                            assertEquals(listOf(original), repository.getDraftSnapshotsForRecovery())
                                        }
                                    }
                                }
                            held.await()
                            val writer = async(Dispatchers.IO) { repository.saveDraftSnapshot(edited) }
                            worker.join()
                            writer.await()
                        }
                    }
                assertNotNull(completed, "Worker claim and draft mutex must not wait on each other")
                val saved = repository.getDraftSnapshotsForRecovery().single()
                assertEquals(edited.id, saved.id)
                assertEquals("writer edit", saved.markId)
                assertEquals(saved, store.getDraft(saved.id), "The edit must reach real persistent DataStore")
                actualFence.withExclusiveReclamation { assertEquals(saved, store.getDraft(saved.id)) }
            }
        }

    @Test
    fun `first editor write admits its root before waiting on the worker claim or draft mutex`() =
        runBlocking {
            fixture { root, scope ->
                val nativeGate = gate()
                val actualFence = AndroidMediaClaimFence(File(root, "private"))
                val admissionRequested = CompletableDeferred<Unit>()
                val directory = File(root, "new-drafts")
                val store =
                    AndroidLocalEntryDraftStore(
                        PreferenceDataStoreFactory.create(scope = scope) { File(directory, "entry.preferences_pb") },
                        admitContent = {
                            if (!directory.isDirectory) {
                                admissionRequested.complete(Unit)
                                nativeGate.withLock { check(directory.mkdirs() || directory.isDirectory) }
                            }
                        },
                        claimFence = actualFence,
                    )
                val repository = OfflineFirstEntryDraftRepository(store, scope)
                assertTrue(repository.getDraftSnapshotsForRecovery().isEmpty())
                val held = CompletableDeferred<Unit>()
                val original = draft()
                val completed =
                    withTimeoutOrNull(3_000) {
                        coroutineScope {
                            val worker =
                                launch(Dispatchers.IO) {
                                    RetainedJournalReconciliationLock.prepareThenWithDeliveryGate(nativeGate, prepare = {}) {
                                        RetainedJournalReconciliationLock.withMediaClaim(actualFence) {
                                            held.complete(Unit)
                                            admissionRequested.await()
                                            assertTrue(repository.getDraftSnapshotsForRecovery().isEmpty())
                                        }
                                    }
                                }
                            held.await()
                            val writer = async(Dispatchers.IO) { repository.saveDraftSnapshot(original) }
                            worker.join()
                            writer.await()
                        }
                    }
                assertNotNull(completed, "Content-root admission must not hold the draft mutex while waiting for the native gate")
                assertEquals(original.id, store.getAllDrafts().single().id)
            }
        }

    @Test
    fun `worker can await initialization and recover under its existing claim without new admission`() =
        runBlocking {
            fixture { root, scope ->
                val fence = AndroidMediaClaimFence(File(root, "private"))
                var rejectAdmission = false
                val store =
                    AndroidLocalEntryDraftStore(
                        PreferenceDataStoreFactory.create(scope = scope) { File(root, "entry.preferences_pb") },
                        admitContent = { check(!rejectAdmission) { "Recovery must not reacquire delivery admission" } },
                        claimFence = fence,
                    )
                val original = draft()
                store.saveDraft(original)
                rejectAdmission = true
                withTimeout(3_000) {
                    RetainedJournalReconciliationLock.prepareThenWithDeliveryGate(gate(), prepare = {}) {
                        RetainedJournalReconciliationLock.withMediaClaim(fence) {
                            val repository = OfflineFirstEntryDraftRepository(store, scope)
                            assertEquals(listOf(original), repository.getDraftSnapshotsForRecovery())
                            val replacement = original.copy(markId = "proof-bound recovery")
                            assertTrue(
                                repository.compareAndSetDraftForScopedRecovery(original, replacement, { true }) { commit -> commit() },
                            )
                            assertEquals(replacement, store.getDraft(original.id))
                            assertEquals(listOf(replacement), repository.getDraftSnapshotsForRecovery())
                        }
                    }
                }
            }
        }

    @Test
    fun `cancelled writer waiting for claim leaves snapshots and later edits available`() =
        runBlocking {
            fixture { root, scope ->
                val fence = AndroidMediaClaimFence(File(root, "private"))
                val requested = CompletableDeferred<Unit>()
                var observe = false
                val observedFence =
                    object : MediaClaimFence by fence {
                        override suspend fun <T> withClaim(block: suspend () -> T): T {
                            if (observe) requested.complete(Unit)
                            return fence.withClaim(block)
                        }
                    }
                val store =
                    AndroidLocalEntryDraftStore(
                        PreferenceDataStoreFactory.create(scope = scope) { File(root, "entry.preferences_pb") },
                        claimFence = observedFence,
                    )
                val original = draft()
                store.saveDraft(original)
                val repository = OfflineFirstEntryDraftRepository(store, scope)
                repository.getDraftSnapshotsForRecovery()
                observe = true
                withTimeout(3_000) {
                    fence.withClaim {
                        // Use the independent fixture scope so this writer cannot inherit HeldFence.
                        val writer = scope.launch { repository.saveDraftSnapshot(original.copy(markId = "cancelled")) }
                        requested.await()
                        writer.cancelAndJoin()
                        assertEquals(listOf(original), repository.getDraftSnapshotsForRecovery())
                        assertEquals(original, store.getDraft(original.id))
                    }
                    repository.saveDraftSnapshot(original.copy(markId = "later edit"))
                    assertEquals("later edit", store.getDraft(original.id)?.markId)
                }
            }
        }

    @Test
    fun `repository writes admit once and direct store writes retain admission`() =
        runBlocking {
            fixture { root, scope ->
                var admissions = 0
                val store =
                    AndroidLocalEntryDraftStore(
                        PreferenceDataStoreFactory.create(scope = scope) { File(root, "entry.preferences_pb") },
                        admitContent = { admissions++ },
                        claimFence = AndroidMediaClaimFence(File(root, "private")),
                    )
                val original = draft()
                store.saveDraft(original)
                assertEquals(1, admissions)
                val repository = OfflineFirstEntryDraftRepository(store, scope)
                repository.saveDraftSnapshot(original.copy(markId = "edit"))
                assertEquals(2, admissions, "Nested save must not reacquire delivery admission under claim and mutation mutex")
                store.saveDraft(original)
                assertEquals(3, admissions, "Direct store callers still admit durable content")
            }
        }

    @Test
    fun `recording updates selections and deletion acquire claim before mutation mutex`() =
        runBlocking {
            val mutations: List<suspend (OfflineFirstEntryDraftRepository, EntryDraft, Uuid) -> Unit> =
                listOf(
                    { repo, original, _ -> repo.createDraft(original.id, emptyList()) },
                    { repo, original, _ -> repo.updateDraft(original.id, emptyList()) },
                    { repo, original, _ -> repo.setPendingMedia(original.id, original.pendingMedia) },
                    { repo, original, _ -> repo.setSelectedJournalIds(original.id, emptyList()) },
                    { repo, _, block -> repo.checkpointRecording(block, "/audio.m4a", "owner") },
                    { repo, _, block -> repo.completeRecording(block, "/audio.m4a", 1000, "owner", true) },
                    { repo, original, _ -> repo.deleteDraft(original.id) },
                    { repo, _, _ -> repo.deleteAllDrafts() },
                    { repo, _, _ -> repo.deleteExpiredDrafts(Duration.ZERO) },
                )
            mutations.forEachIndexed { index, mutate ->
                fixture { root, scope ->
                    val fence = AndroidMediaClaimFence(File(root, "private"))
                    val requested = CompletableDeferred<Unit>()
                    var observe = false
                    val observed =
                        object : MediaClaimFence by fence {
                            override suspend fun <T> withClaim(block: suspend () -> T): T {
                                if (observe) requested.complete(Unit)
                                return fence.withClaim(block)
                            }
                        }
                    val store =
                        AndroidLocalEntryDraftStore(
                            PreferenceDataStoreFactory.create(scope = scope) { File(root, "entry.preferences_pb") },
                            claimFence = observed,
                        )
                    val block = Uuid.random()
                    val original =
                        draft().let { draft ->
                            draft.copy(
                                pendingMedia =
                                    listOf(
                                        PendingMediaRecord(block, PendingMediaType.AUDIO, draft.createdAt, filePath = "/audio.m4a"),
                                    ),
                                recordingOwners = mapOf(block.toString() to "owner"),
                            )
                        }
                    store.saveDraft(original)
                    val repository = OfflineFirstEntryDraftRepository(store, scope)
                    repository.getDraftSnapshotsForRecovery()
                    observe = true
                    withTimeout(3_000) {
                        fence
                            .withClaim {
                                val writer = scope.async { mutate(repository, original, block) }
                                requested.await()
                                assertEquals(
                                    listOf(original),
                                    repository.getDraftSnapshotsForRecovery(),
                                    "Mutation $index must wait before taking draft mutex",
                                )
                                writer
                            }.await()
                        assertEquals(store.getAllDrafts().toSet(), repository.getDraftSnapshotsForRecovery().toSet())
                    }
                }
            }
        }

    private suspend fun fixture(block: suspend (File, CoroutineScope) -> Unit) {
        val directory = Files.createTempDirectory("draft-media-order").toFile()
        val job = SupervisorJob()
        try {
            block(directory, CoroutineScope(job + Dispatchers.IO))
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private fun gate(): NativeRestoreDeliveryGate {
        val mutex = Mutex()
        return object : NativeRestoreDeliveryGate {
            override suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
        }
    }

    private fun draft(): EntryDraft {
        val now = Clock.System.now()
        return EntryDraft(id = Uuid.random(), notes = emptyList(), createdAt = now, updatedAt = now)
    }
}
