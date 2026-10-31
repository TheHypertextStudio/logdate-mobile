package studio.hypertext.atproto.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.ByteString.Companion.toByteString
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import kotlin.concurrent.Volatile

/**
 * Deterministic sorted record index used as the repo root snapshot.
 */
public data class MerkleSearchTreeEntry(
    val collection: Nsid,
    val recordKey: RecordKey,
    val cid: Cid,
)

/**
 * Small deterministic MST-backed record index used by the standalone repo runtime.
 *
 * The entries are kept in storage-key order, so reads, pages and collection slices need no sort.
 * A tree also remembers the CIDs of the subtrees it was built from, together with the keys changed
 * since. Encoding a new version then only encodes the nodes on the changed paths: a subtree whose
 * keys are all unchanged has the same content, and so the same CID, as before.
 */
public class MerkleSearchTree private constructor(
    private val leaves: List<MstLeaf>,
    private val memo: Map<MstSubtreeId, Cid>,
    private val changedKeys: List<String>,
) {
    @Volatile
    private var cachedRoot: Cid? = null

    internal val size: Int get() = leaves.size

    /**
     * Returns the CID for [collection] and [recordKey], if present.
     */
    public fun get(
        collection: Nsid,
        recordKey: RecordKey,
    ): Cid? = leaves.getOrNull(indexOf(storageKey(collection, recordKey)))?.value

    /**
     * Returns a new tree with [collection] and [recordKey] mapped to [cid].
     */
    public fun put(
        collection: Nsid,
        recordKey: RecordKey,
        cid: Cid,
    ): MerkleSearchTree = putAll(listOf(MerkleSearchTreeEntry(collection, recordKey, cid)))

    /**
     * Returns a new tree with every entry of [entries] added or replaced. A later entry for the same
     * key wins over an earlier one.
     */
    internal fun putAll(entries: List<MerkleSearchTreeEntry>): MerkleSearchTree {
        val incoming =
            entries
                .associateBy { storageKey(it.collection, it.recordKey) }
                .filter { (key, entry) -> leaves.getOrNull(indexOf(key))?.value != entry.cid }
        if (incoming.isEmpty()) return this
        val added = incoming.entries.sortedBy { it.key }
        val merged = ArrayList<MstLeaf>(leaves.size + added.size)
        var existing = 0
        for ((key, entry) in added) {
            while (existing < leaves.size && leaves[existing].key < key) {
                merged += leaves[existing++]
            }
            if (existing < leaves.size && leaves[existing].key == key) {
                merged += leaves[existing++].copy(value = entry.cid)
            } else {
                merged += MstLeaf(entry.collection, entry.recordKey, key, entry.cid, leadingZerosOnHash(key))
            }
        }
        while (existing < leaves.size) {
            merged += leaves[existing++]
        }
        return MerkleSearchTree(merged, memo, mergeSorted(changedKeys, added.map { it.key }))
    }

    /**
     * Returns a new tree without [collection] and [recordKey].
     */
    public fun remove(
        collection: Nsid,
        recordKey: RecordKey,
    ): MerkleSearchTree {
        val key = storageKey(collection, recordKey)
        val index = indexOf(key)
        if (index < 0) return this
        val remaining = ArrayList<MstLeaf>(leaves.size - 1)
        remaining.addAll(leaves.subList(0, index))
        remaining.addAll(leaves.subList(index + 1, leaves.size))
        return MerkleSearchTree(remaining, memo, mergeSorted(changedKeys, listOf(key)))
    }

    /**
     * Returns the entries in the order the tree stores them: by the full `collection/recordKey`
     * key, compared byte by byte as the repository specification requires.
     *
     * This is not the same as sorting by collection and then by record key. "com.example.app.two/y"
     * sorts before "com.example.app/x", because "." comes before "/", although the collection
     * "com.example.app" sorts before "com.example.app.two".
     */
    public fun entries(collection: Nsid? = null): List<MerkleSearchTreeEntry> {
        val range = if (collection == null) leaves else leaves.subList(collectionStart(collection), collectionEnd(collection))
        return range.map(MstLeaf::toEntry)
    }

    /**
     * Returns up to [limit] entries of [collection] in record-key order, starting after [cursor]
     * (before it when [reverse] is set), and whether more entries follow the page.
     */
    internal fun page(
        collection: Nsid,
        cursor: String?,
        reverse: Boolean,
        limit: Int,
    ): Pair<List<MerkleSearchTreeEntry>, Boolean> {
        val start = collectionStart(collection)
        val end = collectionEnd(collection)
        val prefix = "$collection/"
        if (!reverse) {
            val from = cursor?.let { upperBound(prefix + it).coerceIn(start, end) } ?: start
            val to = minOf(end, from + limit)
            return leaves.subList(from, to).map(MstLeaf::toEntry) to (to < end)
        }
        val before = cursor?.let { lowerBound(prefix + it).coerceIn(start, end) } ?: end
        val from = maxOf(start, before - limit)
        return leaves.subList(from, before).asReversed().map(MstLeaf::toEntry) to (from > start)
    }

    /**
     * Returns the deterministic root CID for the current tree.
     */
    public fun rootCid(): Cid = cachedRoot ?: encodeChanges().root.also { cachedRoot = it }

    /**
     * Encodes the flattened record map to a deterministic JSON element.
     *
     * This is a compatibility view for tests and debugging. Repo persistence now
     * uses real MST node blocks instead of this flattened shape.
     */
    public fun toJsonElement(): JsonArray =
        buildJsonArray {
            entries().forEach { entry ->
                add(
                    buildJsonObject {
                        put("c", entry.collection.toString())
                        put("k", entry.recordKey.toString())
                        put("v", entry.cid.toString())
                    },
                )
            }
        }

    /** Every node block of the tree, encoded from scratch. */
    internal fun toBlockGraph(): MstBlockGraph {
        val encoder = MstEncoder(memo = emptyMap(), changedKeys = emptyList())
        val root = encoder.encode(leaves)
        return MstBlockGraph(root = root, blocks = encoder.blocks.values.toList())
    }

    /**
     * Encodes only the nodes that differ from the subtrees this tree remembers, and returns them with
     * the tree that remembers the result. Every remembered subtree came from stored blocks or from an
     * earlier call whose blocks the caller stored, so the returned blocks are the only ones missing.
     */
    internal fun encodeChanges(): MstChanges {
        val encoder = MstEncoder(memo = memo, changedKeys = changedKeys)
        val root = encoder.encode(leaves)
        val carried = memo.filterKeys { id -> !encoder.changedWithin(id.firstKey, id.lastKey) }
        val remembered = if (carried.size > leaves.size * MAX_MEMO_PER_LEAF) encoder.encoded else carried + encoder.encoded
        val next = MerkleSearchTree(leaves, remembered, emptyList()).also { it.cachedRoot = root }
        return MstChanges(root = root, blocks = encoder.blocks.values.toList(), tree = next)
    }

    private fun indexOf(key: String): Int {
        val index = lowerBound(key)
        return if (index < leaves.size && leaves[index].key == key) index else -1
    }

    private fun lowerBound(key: String): Int {
        var low = 0
        var high = leaves.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (leaves[mid].key < key) low = mid + 1 else high = mid
        }
        return low
    }

    private fun upperBound(key: String): Int {
        var low = 0
        var high = leaves.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (leaves[mid].key <= key) low = mid + 1 else high = mid
        }
        return low
    }

    // Every key of a collection starts with "collection/", and "0" is the character after "/".
    private fun collectionStart(collection: Nsid): Int = lowerBound("$collection/")

    private fun collectionEnd(collection: Nsid): Int = lowerBound("${collection}0")

    public companion object {
        private const val MAX_MEMO_PER_LEAF = 4

        /**
         * Returns an empty tree.
         */
        public fun empty(): MerkleSearchTree = MerkleSearchTree(emptyList(), emptyMap(), emptyList())

        /**
         * Decodes a flattened compatibility view from [element].
         */
        public fun fromJsonElement(element: JsonArray): MerkleSearchTree =
            empty().putAll(
                element.map { encodedEntry ->
                    val entry = encodedEntry.jsonObject
                    MerkleSearchTreeEntry(
                        collection = Nsid.require(entry.getValue("c").jsonPrimitive.content),
                        recordKey = RecordKey.require(entry.getValue("k").jsonPrimitive.content),
                        cid = Cid.require(entry.getValue("v").jsonPrimitive.content),
                    )
                },
            )

        /**
         * Reads the tree whose root is [root].
         *
         * A tree read from blocks someone else supplied is rebuilt from its entries alone, so its
         * root only matches [root] when the blocks were in the canonical shape. With [trusted] set,
         * for blocks this repository stored itself, the tree also remembers every subtree it read,
         * and later versions re-encode only what changes.
         */
        internal suspend fun fromBlocks(
            root: Cid,
            trusted: Boolean = false,
            readBlock: suspend (Cid) -> RepoBlock?,
        ): MerkleSearchTree {
            val reader = MstReader(readBlock, trusted)
            reader.read(root, layer = null)
            val leaves =
                reader.leaves
                    .associateBy(MstLeaf::key)
                    .values
                    .sortedBy(MstLeaf::key)
            val tree = MerkleSearchTree(leaves, reader.memo, emptyList())
            if (trusted) tree.cachedRoot = root
            return tree
        }
    }
}

internal data class MstBlockGraph(
    val root: Cid,
    val blocks: List<RepoBlock>,
)

/** The result of [MerkleSearchTree.encodeChanges]. */
internal class MstChanges(
    val root: Cid,
    val blocks: List<RepoBlock>,
    val tree: MerkleSearchTree,
)

/** A child subtree: the layer of the node that links to it and the first and last keys under it. */
internal data class MstSubtreeId(
    val parentLayer: Int,
    val firstKey: String,
    val lastKey: String,
)

private data class MstLeaf(
    val collection: Nsid,
    val recordKey: RecordKey,
    val key: String,
    val value: Cid,
    val layer: Int,
) {
    fun toEntry(): MerkleSearchTreeEntry = MerkleSearchTreeEntry(collection = collection, recordKey = recordKey, cid = value)
}

private data class MstNode(
    val layer: Int,
    val entries: List<MstNodeEntry>,
)

private sealed interface MstNodeEntry

private data class MstSubtree(
    val node: MstNode,
    val id: MstSubtreeId? = null,
) : MstNodeEntry

private data class MstEncodedSubtree(
    val cid: Cid,
) : MstNodeEntry

private data class MstLeafEntry(
    val key: String,
    val value: Cid,
) : MstNodeEntry

/** Builds and encodes the canonical node structure, reusing remembered subtrees with unchanged keys. */
private class MstEncoder(
    private val memo: Map<MstSubtreeId, Cid>,
    private val changedKeys: List<String>,
) {
    val blocks = linkedMapOf<Cid, RepoBlock>()
    val encoded = HashMap<MstSubtreeId, Cid>()

    fun encode(leaves: List<MstLeaf>): Cid {
        val root =
            if (leaves.isEmpty()) {
                MstNode(layer = 0, entries = emptyList())
            } else {
                buildNode(leaves, leaves.maxOf(MstLeaf::layer))
            }
        return serializeNode(root)
    }

    fun changedWithin(
        firstKey: String,
        lastKey: String,
    ): Boolean {
        var low = 0
        var high = changedKeys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (changedKeys[mid] < firstKey) low = mid + 1 else high = mid
        }
        return low < changedKeys.size && changedKeys[low] <= lastKey
    }

    private fun buildNode(
        leaves: List<MstLeaf>,
        layer: Int,
    ): MstNode {
        val entries = mutableListOf<MstNodeEntry>()
        var index = 0
        while (index < leaves.size) {
            val segmentStart = index
            while (index < leaves.size && leaves[index].layer < layer) {
                index += 1
            }
            if (segmentStart < index) {
                entries += childSubtree(leaves.subList(segmentStart, index), layer)
            }
            if (index < leaves.size) {
                val leaf = leaves[index]
                require(leaf.layer == layer) { "MST layer mismatch for key ${leaf.key}" }
                entries += MstLeafEntry(key = leaf.key, value = leaf.value)
                index += 1
            }
        }
        return MstNode(layer = layer, entries = entries)
    }

    private fun childSubtree(
        leaves: List<MstLeaf>,
        parentLayer: Int,
    ): MstNodeEntry {
        val id = MstSubtreeId(parentLayer, leaves.first().key, leaves.last().key)
        val remembered = memo[id]
        if (remembered != null && !changedWithin(id.firstKey, id.lastKey)) {
            encoded[id] = remembered
            return MstEncodedSubtree(remembered)
        }
        val childLayer = parentLayer - 1
        var node = buildNode(leaves, leaves.maxOf(MstLeaf::layer))
        while (node.layer < childLayer) {
            node = MstNode(layer = node.layer + 1, entries = listOf(MstSubtree(node)))
        }
        return MstSubtree(node, id)
    }

    private fun link(entry: MstNodeEntry?): Cid? =
        when (entry) {
            is MstEncodedSubtree -> entry.cid
            is MstSubtree -> serializeNode(entry.node).also { cid -> entry.id?.let { encoded[it] = cid } }
            else -> null
        }

    private fun serializeNode(node: MstNode): Cid {
        val entries = node.entries
        var index = 0
        val leftCid = link(entries.firstOrNull()).also { if (it != null) index = 1 }

        var lastKey = ""
        val encodedEntries =
            buildJsonArray {
                while (index < entries.size) {
                    val leaf = entries[index] as? MstLeafEntry ?: error("MST nodes cannot contain adjacent subtrees")
                    val rightCid = link(entries.getOrNull(index + 1))
                    val prefixLength = countPrefixLength(lastKey, leaf.key)
                    val suffix = leaf.key.drop(prefixLength).encodeToByteArray()
                    add(
                        buildJsonObject {
                            put("p", prefixLength)
                            put("k", DagCborCodec.bytes(suffix))
                            put("v", DagCborCodec.link(leaf.value))
                            put("t", rightCid?.let(DagCborCodec::link) ?: JsonNull)
                        },
                    )
                    lastKey = leaf.key
                    index += if (rightCid != null) 2 else 1
                }
            }

        val encodedNode =
            buildJsonObject {
                put("l", leftCid?.let(DagCborCodec::link) ?: JsonNull)
                put("e", encodedEntries)
            }
        val bytes = DagCborCodec.encode(encodedNode)
        val cid = Cid.sha256(DAG_CBOR_CODEC, bytes)
        blocks[cid] = RepoBlock(cid = cid, bytes = bytes)
        return cid
    }
}

/** Walks stored node blocks, collecting the leaves and, for trusted blocks, every subtree's CID. */
private class MstReader(
    private val readBlock: suspend (Cid) -> RepoBlock?,
    private val trusted: Boolean,
) {
    val leaves = mutableListOf<MstLeaf>()
    val memo = HashMap<MstSubtreeId, Cid>()

    /**
     * Reads the node [nodeCid] at [layer] (null for the root) and returns the first and last keys
     * under it, or null when it holds none.
     */
    suspend fun read(
        nodeCid: Cid,
        layer: Int?,
    ): Pair<String, String>? {
        val nodeBlock = requireNotNull(readBlock(nodeCid)) { "Missing MST block $nodeCid" }
        val node = DagCborCodec.decode(nodeBlock.bytes).jsonObject
        val encodedEntries = node.getValue("e").jsonArray
        var lastKey = ""
        val keys =
            encodedEntries.map { encodedEntry ->
                val entry = encodedEntry.jsonObject
                val prefixLength = entry.getValue("p").jsonPrimitive.int
                val suffix =
                    requireNotNull(DagCborCodec.bytesOrNull(entry["k"])) { "MST key suffix must be bytes" }
                        .decodeToString()
                (lastKey.take(prefixLength) + suffix).also { lastKey = it }
            }
        // In a canonical tree every leaf sits in the node of its own layer, so the layer of stored
        // blocks follows from their position. Supplied blocks are hashed, as the rebuild must check.
        val nodeLayer = layer ?: keys.firstOrNull()?.let(::leadingZerosOnHash) ?: 0
        var first: String? = null
        var last: String? = null

        suspend fun child(link: Cid?) {
            val linked = link ?: return
            val range = read(linked, nodeLayer - 1) ?: return
            if (trusted) memo[MstSubtreeId(nodeLayer, range.first, range.second)] = linked
            if (first == null) first = range.first
            last = range.second
        }

        child(DagCborCodec.linkOrNull(node["l"]))
        encodedEntries.forEachIndexed { index, encodedEntry ->
            val entry = encodedEntry.jsonObject
            val key = keys[index]
            val value = requireNotNull(DagCborCodec.linkOrNull(entry["v"])) { "MST leaf value must be a CID link" }
            val (collection, recordKey) = parseStorageKey(key)
            leaves += MstLeaf(collection, recordKey, key, value, if (trusted) nodeLayer else leadingZerosOnHash(key))
            if (first == null) first = key
            last = key
            child(DagCborCodec.linkOrNull(entry["t"]))
        }
        val firstKey = first ?: return null
        return firstKey to requireNotNull(last)
    }
}

private fun storageKey(
    collection: Nsid,
    recordKey: RecordKey,
): String = "$collection/$recordKey"

private fun parseStorageKey(key: String): Pair<Nsid, RecordKey> {
    val parts = key.split('/', limit = 2)
    require(parts.size == 2) { "Invalid MST storage key: $key" }
    return Nsid.require(parts[0]) to RecordKey.require(parts[1])
}

private fun mergeSorted(
    first: List<String>,
    second: List<String>,
): List<String> = (first + second).distinct().sorted()

private fun countPrefixLength(
    previous: String,
    current: String,
): Int {
    val maxPrefix = minOf(previous.length, current.length)
    for (index in 0 until maxPrefix) {
        if (previous[index] != current[index]) {
            return index
        }
    }
    return maxPrefix
}

private fun leadingZerosOnHash(key: String): Int {
    val hash =
        key
            .encodeToByteArray()
            .toByteString()
            .sha256()
            .toByteArray()
    var leadingZeros = 0
    hash.forEach { byte ->
        val unsignedByte = byte.toInt() and 0xff
        if (unsignedByte < 64) {
            leadingZeros += 1
        } else {
            return leadingZeros
        }
        if (unsignedByte < 16) {
            leadingZeros += 1
        } else {
            return leadingZeros
        }
        if (unsignedByte < 4) {
            leadingZeros += 1
        } else {
            return leadingZeros
        }
        if (unsignedByte == 0) {
            leadingZeros += 1
        } else {
            return leadingZeros
        }
    }
    return leadingZeros
}
