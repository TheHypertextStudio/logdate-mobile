package studio.hypertext.atproto.repo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The event stream describes each commit by the records it changed. These tests write commits with
 * the real engine and check what [RepoCommitChanges] reports about each one.
 */
class RepoCommitChangeTest {
    private val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    private val profile = Nsid.require("app.logdate.actor.profile")
    private val documents = Nsid.require("site.standard.document")

    private fun recordId(
        collection: Nsid,
        key: String,
    ) = RepoRecordId(repo, collection, RecordKey.require(key))

    private fun value(text: String) = buildJsonObject { put("text", text) }

    private suspend fun lastCommit(store: RepoBlockStore) = store.listCommits(repo).getOrThrow().last()

    @Test
    fun `the first commit reports its record as created with no previous commit`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine.putRecord(recordId(profile, "self"), value("hello")).getOrThrow()

            val change = RepoCommitChanges.read(store, lastCommit(store)).getOrThrow()

            assertNull(change.previous)
            val operation = change.operations.single()
            assertEquals(RepoOperationAction.CREATE, operation.action)
            assertEquals("app.logdate.actor.profile/self", operation.path)
            assertNotNull(operation.cid)
            assertNull(operation.prev)
            assertEquals(setOf(profile), change.collections)
        }

    @Test
    fun `a later commit reports an update with the record it replaced`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            val first = engine.putRecord(recordId(profile, "self"), value("one")).getOrThrow()
            engine.putRecord(recordId(documents, "3kabc"), value("doc")).getOrThrow()
            engine.putRecord(recordId(profile, "self"), value("two")).getOrThrow()

            val change = RepoCommitChanges.read(store, lastCommit(store)).getOrThrow()

            assertNotNull(change.previous)
            val operation = change.operations.single()
            assertEquals(RepoOperationAction.UPDATE, operation.action)
            assertEquals(first.cid, operation.prev.toString())
            assertEquals(setOf(profile, documents), change.collections)
        }

    @Test
    fun `a deletion reports the record that went away and carries no record block`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine.putRecord(recordId(documents, "3kabc"), value("doc")).getOrThrow()
            engine.putRecord(recordId(profile, "self"), value("me")).getOrThrow()
            engine.deleteRecord(recordId(documents, "3kabc")).getOrThrow()

            val change = RepoCommitChanges.read(store, lastCommit(store)).getOrThrow()

            val operation = change.operations.single()
            assertEquals(RepoOperationAction.DELETE, operation.action)
            assertNull(operation.cid)
            assertNotNull(operation.prev)
            assertTrue(change.recordBlocks.isEmpty())
            assertEquals(setOf(profile), change.collections)
        }

    @Test
    fun `a batch reports every record it wrote in path order`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine
                .putRecords(
                    listOf(
                        BatchRecordWrite(recordId(documents, "3kbbb"), value("b")),
                        BatchRecordWrite(recordId(documents, "3kaaa"), value("a")),
                        BatchRecordWrite(recordId(profile, "self"), value("me")),
                    ),
                ).getOrThrow()

            val change = RepoCommitChanges.read(store, lastCommit(store)).getOrThrow()

            assertEquals(
                listOf("app.logdate.actor.profile/self", "site.standard.document/3kaaa", "site.standard.document/3kbbb"),
                change.operations.map(RepoOperation::path),
            )
        }

    @Test
    fun `the commit car roots at the commit and holds the tree and the new records`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine.putRecord(recordId(profile, "self"), value("hello")).getOrThrow()

            val change = RepoCommitChanges.read(store, lastCommit(store)).getOrThrow()
            val car = CarCodec.readCar(change.toCommitCar())

            assertEquals(listOf(change.commit.cid), car.roots)
            assertEquals(change.commit.cid, car.blocks.first().cid)
            val cids = car.blocks.map { it.cid }.toSet()
            assertTrue(change.operations.single().cid in cids, "the new record must travel with its commit")
            assertTrue(change.commit.commit.root in cids, "the tree root must travel with its commit")
            assertTrue(car.blocks.all { it.cid.isCidFor(it.bytes) })
        }

    @Test
    fun `the sync car holds only the commit block`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine.putRecord(recordId(profile, "self"), value("hello")).getOrThrow()

            val change = RepoCommitChanges.read(store, lastCommit(store)).getOrThrow()
            val car = CarCodec.readCar(change.toSyncCar())

            assertEquals(listOf(change.commit.cid), car.roots)
            assertEquals(listOf(change.commit.cid), car.blocks.map { it.cid })
        }

    @Test
    fun `the collections of a tree are the collections that have a record in it`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine.putRecord(recordId(profile, "self"), value("me")).getOrThrow()
            engine.putRecord(recordId(documents, "3kabc"), value("doc")).getOrThrow()
            engine.deleteRecord(recordId(profile, "self")).getOrThrow()

            val collections = RepoCommitChanges.collectionsAt(store, lastCommit(store).commit.root).getOrThrow()

            assertEquals(setOf(documents), collections)
        }

    @Test
    fun `the entries of a tree name every record and its content identifier in key order`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            val document = engine.putRecord(recordId(documents, "3kbbb"), value("b")).getOrThrow()
            val self = engine.putRecord(recordId(profile, "self"), value("me")).getOrThrow()

            val entries = RepoCommitChanges.entriesAt(store, lastCommit(store).commit.root).getOrThrow()

            assertEquals(
                listOf(
                    MerkleSearchTreeEntry(profile, RecordKey.require("self"), Cid.require(self.cid)),
                    MerkleSearchTreeEntry(documents, RecordKey.require("3kbbb"), Cid.require(document.cid)),
                ),
                entries,
            )
        }

    @Test
    fun `a stored commit can be loaded by its CID and a missing one is refused`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine.putRecord(recordId(profile, "self"), value("me")).getOrThrow()
            val stored = lastCommit(store)

            val loaded = RepoCommitChanges.loadCommit(store, stored.cid)

            assertEquals(stored.cid, loaded.cid)
            assertEquals(stored.commit.repo, loaded.commit.repo)
            assertEquals(stored.commit.root, loaded.commit.root)
            assertEquals(stored.commit.prev, loaded.commit.prev)
            assertEquals(stored.commit.revision, loaded.commit.revision)
            assertTrue(runCatching { RepoCommitChanges.loadCommit(store, Cid.sha256(DAG_CBOR_CODEC, byteArrayOf(1))) }.isFailure)
        }

    @Test
    fun `a commit whose predecessor block is missing is reported as a failure`() =
        runSuspend {
            val store = InMemoryRepoBlockStore()
            val engine = DefaultRepoEngine(store)
            engine.putRecord(recordId(profile, "self"), value("one")).getOrThrow()
            engine.putRecord(recordId(profile, "self"), value("two")).getOrThrow()
            val commits = store.listCommits(repo).getOrThrow()
            val orphan = commits.last().let { it.copy(commit = it.commit.copy(prev = Cid.sha256(DAG_CBOR_CODEC, byteArrayOf(0)))) }

            val result = RepoCommitChanges.read(store, orphan)

            assertTrue(result.isFailure)
        }

    private fun runSuspend(block: suspend () -> Unit) {
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result -> result.getOrThrow() })
    }
}
