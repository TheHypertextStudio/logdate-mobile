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

/** The engine keeps the current tree of a repository between calls instead of reading it again. */
class RepoEngineReadCostTest {
    private val repo = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
    private val collection = Nsid.require("studio.hypertext.logdate.entry")

    /** Counts single-block and batch reads, and which history query the engine used. */
    private class CountingBlockStore(
        private val delegate: InMemoryRepoBlockStore = InMemoryRepoBlockStore(),
    ) : RepoBlockStore by delegate {
        var singleReads = 0
        var batchReads = 0
        var recentCommitLimit: Int? = null

        override suspend fun readBlock(cid: Cid): Result<RepoBlock?> {
            singleReads++
            return delegate.readBlock(cid)
        }

        override suspend fun readBlocks(cids: List<Cid>): Result<List<RepoBlock?>> {
            batchReads++
            return delegate.readBlocks(cids)
        }

        override suspend fun listRecentCommits(
            repo: AtprotoDid,
            limit: Int,
        ): Result<List<SignedRepoCommit>> {
            recentCommitLimit = limit
            return delegate.listRecentCommits(repo, limit)
        }
    }

    private fun record(key: String) = RepoRecordId(repo, collection, RecordKey.require(key))

    private fun value(text: String) =
        buildJsonObject {
            put("\$type", collection.toString())
            put("text", text)
        }

    private suspend fun populated(store: CountingBlockStore): DefaultRepoEngine {
        val engine = DefaultRepoEngine(store)
        engine.putRecords((1..300).map { BatchRecordWrite(record("entry-$it"), value("entry $it")) }).getOrThrow()
        return engine
    }

    @Test
    fun `reading a record after a write reads only that record's block`() =
        runSuspend {
            val store = CountingBlockStore()
            val engine = populated(store)
            engine.putRecord(record("entry-301"), value("entry 301")).getOrThrow()

            store.singleReads = 0
            engine.getRecord(record("entry-150")).getOrThrow()

            assertEquals(1, store.singleReads)
        }

    @Test
    fun `an engine that did not write the repository reads its tree once`() =
        runSuspend {
            val store = CountingBlockStore()
            populated(store)
            val engine = DefaultRepoEngine(store)

            engine.getRecord(record("entry-1")).getOrThrow()
            store.singleReads = 0
            engine.getRecord(record("entry-2")).getOrThrow()
            engine.putRecord(record("entry-2"), value("changed")).getOrThrow()

            assertEquals(1, store.singleReads)
        }

    @Test
    fun `a page of records is read in one batch`() =
        runSuspend {
            val store = CountingBlockStore()
            val engine = populated(store)

            store.singleReads = 0
            store.batchReads = 0
            val page = engine.listRecords(repo, collection, limit = 50, cursor = "entry-200").getOrThrow()

            assertEquals(50, page.records.size)
            assertEquals(0, store.singleReads)
            assertEquals(1, store.batchReads)
        }

    @Test
    fun `listing commits asks the store for only the newest ones`() =
        runSuspend {
            val store = CountingBlockStore()
            val engine = populated(store)
            engine.putRecord(record("entry-1"), value("changed")).getOrThrow()

            val commits = engine.listCommits(repo, limit = 1).getOrThrow()

            assertEquals(1, store.recentCommitLimit)
            assertEquals(listOf(engine.loadHead(repo).getOrThrow()?.commitCid), commits.map { it.cid })
        }
}

private fun runSuspend(block: suspend () -> Unit) {
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result -> result.getOrThrow() },
    )
}
