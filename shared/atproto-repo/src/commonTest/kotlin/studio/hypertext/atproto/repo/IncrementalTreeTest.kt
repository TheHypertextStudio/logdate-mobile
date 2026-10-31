package studio.hypertext.atproto.repo

import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Encoding only the changed paths of the record tree gives the same tree as encoding all of it. */
class IncrementalTreeTest {
    private val collections =
        listOf(
            Nsid.require("studio.hypertext.logdate.entry"),
            Nsid.require("studio.hypertext.logdate.entry.media"),
            Nsid.require("app.bsky.feed.post"),
        )

    private fun cid(seed: Int): Cid = Cid.sha256(DAG_CBOR_CODEC, "record-$seed".encodeToByteArray())

    @Test
    fun `random writes encode to the same root and blocks as a full rebuild`() {
        val random = Random(20261030)
        val stored = mutableMapOf<Cid, RepoBlock>()
        var tree = MerkleSearchTree.empty()
        val keys = mutableListOf<Pair<Nsid, RecordKey>>()
        repeat(400) { step ->
            tree =
                if (keys.isNotEmpty() && random.nextInt(4) == 0) {
                    val (collection, recordKey) = keys.removeAt(random.nextInt(keys.size))
                    tree.remove(collection, recordKey)
                } else if (keys.isNotEmpty() && random.nextInt(3) == 0) {
                    val (collection, recordKey) = keys[random.nextInt(keys.size)]
                    tree.put(collection, recordKey, cid(step))
                } else {
                    val key = collections[random.nextInt(collections.size)] to RecordKey.require("k${random.nextInt(100_000)}")
                    if (key !in keys) keys += key
                    tree.put(key.first, key.second, cid(step))
                }
            val changes = tree.encodeChanges()
            changes.blocks.forEach { stored[it.cid] = it }
            tree = changes.tree

            val full = tree.toBlockGraph()
            assertEquals(full.root, changes.root, "root after step $step")
            assertTrue(full.blocks.all { it.cid in stored }, "every node of the tree is stored after step $step")
        }
    }

    @Test
    fun `a stored tree read back re-encodes only the path to a new record`() =
        runSuspend {
            val stored = mutableMapOf<Cid, RepoBlock>()
            val collection = collections.first()
            val built =
                MerkleSearchTree.empty().putAll(
                    (1..2_000).map { MerkleSearchTreeEntry(collection, RecordKey.require("entry-$it"), cid(it)) },
                )
            val graph = built.toBlockGraph()
            graph.blocks.forEach { stored[it.cid] = it }

            val loaded = MerkleSearchTree.fromBlocks(graph.root, trusted = true) { stored[it] }
            val changes = loaded.put(collection, RecordKey.require("entry-new"), cid(0)).encodeChanges()

            assertEquals(changes.tree.toBlockGraph().root, changes.root)
            assertTrue(changes.blocks.size <= MAX_PATH_NODES, "encoded ${changes.blocks.size} of ${graph.blocks.size} nodes")
        }

    @Test
    fun `a page slices one collection in record-key order without the neighbouring collections`() {
        val (entry, media, post) = collections
        val tree =
            MerkleSearchTree.empty().putAll(
                listOf(entry, media, post).flatMap { collection ->
                    (1..5).map { MerkleSearchTreeEntry(collection, RecordKey.require("r$it"), cid(it)) }
                },
            )

        val (first, more) = tree.page(entry, cursor = null, reverse = false, limit = 2)
        val (rest, noMore) = tree.page(entry, cursor = "r2", reverse = false, limit = 10)
        val (backwards, earlier) = tree.page(entry, cursor = "r4", reverse = true, limit = 2)

        assertEquals(listOf("r1", "r2"), first.map { it.recordKey.toString() })
        assertTrue(more)
        assertEquals(listOf("r3", "r4", "r5"), rest.map { it.recordKey.toString() })
        assertEquals(false, noMore)
        assertEquals(listOf("r3", "r2"), backwards.map { it.recordKey.toString() })
        assertTrue(earlier)
        assertTrue((first + rest + backwards).all { tree.get(entry, it.recordKey) == it.cid })
    }

    private companion object {
        /** The nodes from the root to a leaf in a 2,000-record tree, with room for a split. */
        const val MAX_PATH_NODES = 8
    }
}

private fun runSuspend(block: suspend () -> Unit) {
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result -> result.getOrThrow() },
    )
}
