package app.logdate.server.atproto.firehose

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DefaultRepoEngine
import studio.hypertext.atproto.repo.InMemoryRepoBlockStore
import studio.hypertext.atproto.repo.RAW_CODEC
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoBlockStore
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `getBlob` asks whether a public record refers to a blob. After the repository changes, only the
 * records the change added are read again, and the remembered answers cover a bounded number of
 * repositories.
 */
class PublicRepoBlobIndexTest {
    private val did = AtprotoDid.require("did:web:alice.logdate.app")
    private val collection = PublicRepoPolicy.DOCUMENT

    private class CountingBlocks(
        private val delegate: RepoBlockStore = InMemoryRepoBlockStore(),
    ) : RepoBlockStore by delegate {
        val read = mutableListOf<Cid>()

        override suspend fun readBlock(cid: Cid): Result<RepoBlock?> {
            read += cid
            return delegate.readBlock(cid)
        }

        override suspend fun readBlocks(cids: List<Cid>): Result<List<RepoBlock?>> {
            read += cids
            return delegate.readBlocks(cids)
        }
    }

    private val shown =
        object : PublicRepoGate {
            override suspend fun mayShow(did: AtprotoDid) = true

            override suspend fun standing(did: AtprotoDid): RepoStanding? = RepoStanding.Active
        }

    private fun blob(seed: Int): Cid = Cid.sha256(RAW_CODEC, byteArrayOf(seed.toByte()))

    private fun document(
        title: String,
        image: Cid? = null,
    ): JsonObject =
        buildJsonObject {
            put("\$type", collection.value)
            put("title", title)
            if (image != null) {
                putJsonObject("coverImage") {
                    put("\$type", "blob")
                    putJsonObject("ref") { put("\$link", image.toString()) }
                    put("mimeType", "image/png")
                    put("size", 1)
                }
            }
        }

    private suspend fun DefaultRepoEngine.write(
        rkey: String,
        value: JsonObject,
    ): Cid = Cid.require(putRecord(RepoRecordId(did, collection, RecordKey.require(rkey)), value).getOrThrow().cid)

    @Test
    fun `a new record is the only record read after the repository changes`() =
        runBlocking {
            val blocks = CountingBlocks()
            val engine = DefaultRepoEngine(blocks)
            val access = PublicRepoAccess(shown, PublicRepoPolicy(), blocks, NoDirectory)
            val existing = (1..10).map { engine.write("doc-$it", document("Story $it", image = blob(it))) }
            assertTrue(access.referencesBlob(did, blob(3)))

            val added = engine.write("doc-11", document("Story 11", image = blob(11)))
            blocks.read.clear()
            assertTrue(access.referencesBlob(did, blob(11)))

            assertEquals(listOf(added), blocks.read.filter { it in existing + added })
        }

    @Test
    fun `a deleted record stops vouching for its blob without reading any record`() =
        runBlocking {
            val blocks = CountingBlocks()
            val engine = DefaultRepoEngine(blocks)
            val access = PublicRepoAccess(shown, PublicRepoPolicy(), blocks, NoDirectory)
            val kept = engine.write("doc-1", document("Kept", image = blob(1)))
            val removed = engine.write("doc-2", document("Removed", image = blob(2)))
            assertTrue(access.referencesBlob(did, blob(2)))

            engine.deleteRecord(RepoRecordId(did, collection, RecordKey.require("doc-2"))).getOrThrow()
            blocks.read.clear()

            assertFalse(access.referencesBlob(did, blob(2)))
            assertTrue(access.referencesBlob(did, blob(1)))
            assertTrue(blocks.read.none { it == kept || it == removed })
        }

    @Test
    fun `a blob two records share stays referenced while either remains`() =
        runBlocking {
            val blocks = CountingBlocks()
            val engine = DefaultRepoEngine(blocks)
            val access = PublicRepoAccess(shown, PublicRepoPolicy(), blocks, NoDirectory)
            engine.write("doc-1", document("First", image = blob(7)))
            engine.write("doc-2", document("Second", image = blob(7)))
            assertTrue(access.referencesBlob(did, blob(7)))

            engine.deleteRecord(RepoRecordId(did, collection, RecordKey.require("doc-1"))).getOrThrow()

            assertTrue(access.referencesBlob(did, blob(7)))
        }

    @Test
    fun `the most recently used entries are kept and the oldest are forgotten`() {
        val cache = RecentlyUsedCache<String, Int>(maxEntries = 2)
        cache["a"] = 1
        cache["b"] = 2
        cache["a"]
        cache["c"] = 3

        assertEquals(2, cache.size)
        assertEquals(1, cache["a"])
        assertEquals(null, cache["b"])
        assertEquals(3, cache["c"])
    }

    private object NoDirectory : PublicRepoDirectory {
        override suspend fun after(
            afterDid: String?,
            limit: Int,
        ): List<PublicRepoCandidate> = emptyList()
    }
}
