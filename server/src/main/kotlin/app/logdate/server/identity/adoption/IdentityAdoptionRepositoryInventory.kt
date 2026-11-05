package app.logdate.server.identity.adoption

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import studio.hypertext.atproto.repo.CarCodec
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.DAG_CBOR_CODEC
import studio.hypertext.atproto.repo.DagCborCodec
import studio.hypertext.atproto.repo.RepoBlock
import studio.hypertext.atproto.repo.RepoHead
import studio.hypertext.atproto.repo.SignedRepoCommit
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import java.util.Base64

internal enum class IdentityAdoptionRepositoryRole {
    CURRENT_COMMIT,
    HISTORICAL_COMMIT,
    CURRENT_NODE,
    HISTORICAL_NODE,
    CURRENT_RECORD,
    HISTORICAL_RECORD,
    UNREFERENCED,
    RAW,
}

internal data class IdentityAdoptionRepositoryRecord(
    val collection: String,
    val rkey: String,
    val cid: String,
)

internal data class IdentityAdoptionRepositoryBlock(
    val cid: String,
    val roles: Set<IdentityAdoptionRepositoryRole>,
)

internal data class IdentityAdoptionRepositoryInventory(
    val records: List<IdentityAdoptionRepositoryRecord>,
    val blocks: List<IdentityAdoptionRepositoryBlock>,
)

/** Integrity/reachability census; adoption authentication and plaintext equivalence are separate proofs. */
internal object IdentityAdoptionRepositoryAuditor {
    const val MAX_BLOCK_BYTES = 4_008_192

    fun inventory(
        did: String,
        head: RepoHead?,
        commits: List<SignedRepoCommit>,
        cids: List<String>,
        read: (String) -> ByteArray?,
    ): IdentityAdoptionRepositoryInventory = RepositoryScan(did, head, commits, cids, read).inventory()
}

/** Keeps graph metadata, not every historical record body; shared subtrees are checked once. */
private class RepositoryScan(
    private val did: String,
    private val head: RepoHead?,
    private val commits: List<SignedRepoCommit>,
    cids: List<String>,
    private val read: (String) -> ByteArray?,
) {
    private val owned = cids.toSet()
    private val unique = owned.size == cids.size
    private val kinds = mutableMapOf<String, BlockKind>()
    private val roles = mutableMapOf<String, MutableSet<IdentityAdoptionRepositoryRole>>()
    private val nodes = mutableMapOf<String, Node>()
    private val visiting = mutableSetOf<String>()
    private val current = mutableListOf<IdentityAdoptionRepositoryRecord>()

    fun inventory(): IdentityAdoptionRepositoryInventory {
        censusCheck(unique)
        for (cid in owned) {
            val (_, value) = block(cid)
            kinds[cid] =
                when (value) {
                    null -> BlockKind.RAW
                    is JsonObject -> BlockKind.OBJECT
                    else -> BlockKind.OTHER
                }
            if (value == null) role(cid, IdentityAdoptionRepositoryRole.RAW)
        }
        val indexed = commits.associateBy { it.cid.value }
        censusCheck(indexed.size == commits.size && commits.map { it.commit.revision }.toSet().size == commits.size)
        if (head != null) {
            censusCheck(head.repo.toString() == did)
            val metadata = indexed[head.commitCid.value]
            censusCheck(metadata != null && metadata.commit.root == head.root && metadata.commit.revision == head.revision)
        }
        for (metadata in commits) {
            checkCommit(metadata)
            val tree = node(metadata.commit.root.value, 0)
            censusCheck(metadata.commit.recordCount >= 0 && tree.count == metadata.commit.recordCount.toLong())
            metadata.commit.prev?.let { previous ->
                censusCheck(previous.value in owned)
                indexed[previous.value]?.let { censusCheck(it.commit.revision < metadata.commit.revision) }
            }
            val isCurrent = metadata.cid == head?.commitCid
            role(
                metadata.cid.value,
                if (isCurrent) IdentityAdoptionRepositoryRole.CURRENT_COMMIT else IdentityAdoptionRepositoryRole.HISTORICAL_COMMIT,
            )
            mark(metadata.commit.root.value, isCurrent)
        }
        return IdentityAdoptionRepositoryInventory(
            current.sortedBy { it.collection + "/" + it.rkey },
            owned.sorted().map { cid ->
                val found = roles.getOrPut(cid) { mutableSetOf() }
                if (found.isEmpty() ||
                    found == setOf(IdentityAdoptionRepositoryRole.RAW)
                ) {
                    found += IdentityAdoptionRepositoryRole.UNREFERENCED
                }
                IdentityAdoptionRepositoryBlock(cid, found.toSet())
            },
        )
    }

    private fun block(cid: String): Pair<ByteArray, JsonElement?> {
        censusCheck(cid in owned && cid.length <= 255)
        val bytes = read(cid)
        censusCheck(bytes != null && bytes.size in 1..IdentityAdoptionRepositoryAuditor.MAX_BLOCK_BYTES)
        val body = requireNotNull(bytes)
        if (Cid.rawSha256(body).value == cid) return body to null
        censusCheck(Cid.sha256(DAG_CBOR_CODEC, body).value == cid)
        val value = runCatching { DagCborCodec.decode(body) }.getOrElse { censusFailure() }
        censusCheck(runCatching { DagCborCodec.encode(value).contentEquals(body) }.getOrDefault(false))
        return body to value
    }

    private fun objectBlock(cid: String): JsonObject {
        censusCheck(kinds[cid] == BlockKind.OBJECT)
        return block(cid).second as? JsonObject ?: censusFailure()
    }

    private fun checkCommit(metadata: SignedRepoCommit) {
        val commit = metadata.commit
        censusCheck(commit.repo.toString() == did && commit.revision >= 0 && metadata.signature.length in 1..4096)
        val (bytes, value) = block(metadata.cid.value)
        val fields = value as? JsonObject ?: censusFailure()
        censusCheck((fields["version"] as? JsonPrimitive)?.longOrNull == 3L)
        val decoded =
            runCatching {
                CarCodec.read(CarCodec.writeCar(listOf(metadata.cid), listOf(RepoBlock(metadata.cid, bytes)))).commits.single()
            }.getOrElse { censusFailure() }
        censusCheck(
            decoded.commit.repo == commit.repo &&
                decoded.commit.root == commit.root &&
                decoded.commit.prev == commit.prev &&
                decoded.commit.revision == commit.revision,
        )
        val signature = binary(fields["sig"])
        val normal = runCatching { Base64.getUrlDecoder().decode(metadata.signature).contentEquals(signature) }.getOrDefault(false)
        val legacy =
            metadata.cid != head?.commitCid &&
                validCid(metadata.signature) &&
                metadata.signature.encodeToByteArray().contentEquals(signature)
        censusCheck(normal || legacy)
    }

    private fun node(
        cid: String,
        depth: Int,
    ): Node {
        censusCheck(depth < 128)
        nodes[cid]?.let {
            censusCheck(it.height + depth <= 128)
            return it
        }
        censusCheck(visiting.add(cid))
        val value = objectBlock(cid)
        censusCheck(value.keys == setOf("l", "e"))
        val left = link(value["l"])
        val entries = value["e"] as? JsonArray ?: censusFailure()
        val leaves = leaves(entries)
        val leftNode = left?.let { node(it, depth + 1) }
        val rightNodes = leaves.map { leaf -> leaf.right?.let { node(it, depth + 1) } }
        if (leaves.isNotEmpty()) censusCheck(leftNode?.maximum?.let { it < leaves.first().key } != false)
        for ((index, child) in rightNodes.withIndex()) {
            censusCheck(child?.minimum?.let { it > leaves[index].key } != false)
            val next = leaves.getOrNull(index + 1)?.key
            if (next != null) censusCheck(child?.maximum?.let { it < next } != false)
        }
        val children = listOfNotNull(leftNode) + rightNodes.filterNotNull()
        val count =
            children.fold(leaves.size.toLong()) { total, child ->
                runCatching { Math.addExact(total, child.count) }.getOrElse { censusFailure() }
            }
        val minimum = leftNode?.minimum ?: leaves.firstOrNull()?.key
        val maximum = rightNodes.lastOrNull()?.maximum ?: leaves.lastOrNull()?.key ?: leftNode?.maximum
        val height = 1 + (children.maxOfOrNull { it.height } ?: 0)
        censusCheck(height + depth <= 128)
        return Node(left, leaves, minimum, maximum, count, height).also {
            nodes[cid] = it
            visiting.remove(cid)
        }
    }

    private fun leaves(entries: JsonArray): List<Leaf> {
        var last = ""
        return entries.map { item ->
            val fields = item as? JsonObject ?: censusFailure()
            censusCheck(fields.keys == setOf("p", "k", "v", "t"))
            val prefix = (fields["p"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: censusFailure()
            censusCheck(prefix in 0L..last.length.toLong())
            val suffix = binary(fields["k"])
            censusCheck(suffix.size in 1..830)
            val tail = runCatching { suffix.decodeToString(throwOnInvalidSequence = true) }.getOrElse { censusFailure() }
            val key = last.take(prefix.toInt()) + tail
            censusCheck(key.length <= 830 && key.all { it.code in 1..127 } && (last.isEmpty() || last < key))
            val common = last.zip(key).takeWhile { it.first == it.second }.size
            censusCheck(prefix == common.toLong())
            val parts = key.split('/', limit = 2)
            censusCheck(parts.size == 2 && Nsid.parse(parts[0]).isSuccess && RecordKey.parse(parts[1]).isSuccess)
            val recordCid = link(fields["v"]) ?: censusFailure()
            censusCheck(kinds[recordCid] == BlockKind.OBJECT)
            last = key
            Leaf(key, recordCid, link(fields["t"]))
        }
    }

    private fun mark(
        cid: String,
        active: Boolean,
    ) {
        val nodeRole = if (active) IdentityAdoptionRepositoryRole.CURRENT_NODE else IdentityAdoptionRepositoryRole.HISTORICAL_NODE
        if (!role(cid, nodeRole)) return
        val node = nodes.getValue(cid)
        node.left?.let { mark(it, active) }
        for (leaf in node.leaves) {
            role(leaf.cid, if (active) IdentityAdoptionRepositoryRole.CURRENT_RECORD else IdentityAdoptionRepositoryRole.HISTORICAL_RECORD)
            if (active) {
                val parts = leaf.key.split('/', limit = 2)
                current += IdentityAdoptionRepositoryRecord(parts[0], parts[1], leaf.cid)
            }
            leaf.right?.let { mark(it, active) }
        }
    }

    private fun role(
        cid: String,
        value: IdentityAdoptionRepositoryRole,
    ): Boolean = roles.getOrPut(cid) { mutableSetOf() }.add(value)

    private fun link(value: JsonElement?): String? {
        if (value == JsonNull) return null
        val fields = value as? JsonObject ?: censusFailure()
        censusCheck(fields.keys == setOf("\$link"))
        return (fields["\$link"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: censusFailure()
    }

    private fun binary(value: JsonElement?): ByteArray {
        val fields = value as? JsonObject ?: censusFailure()
        censusCheck(fields.keys == setOf("\$bytes"))
        val text = (fields["\$bytes"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: censusFailure()
        return runCatching { Base64.getDecoder().decode(text) }.getOrElse { censusFailure() }
    }

    private fun validCid(value: String): Boolean =
        runCatching {
            val raw = Cid.require(value).toBytes()
            raw.size == 36 &&
                raw[0] == 1.toByte() &&
                raw[1] in listOf(0x55.toByte(), 0x71.toByte()) &&
                raw[2] == 0x12.toByte() &&
                raw[3] == 0x20.toByte()
        }.getOrDefault(false)

    private enum class BlockKind { RAW, OBJECT, OTHER }

    private data class Leaf(
        val key: String,
        val cid: String,
        val right: String?,
    )

    private data class Node(
        val left: String?,
        val leaves: List<Leaf>,
        val minimum: String?,
        val maximum: String?,
        val count: Long,
        val height: Int,
    )
}

private fun censusCheck(valid: Boolean) {
    if (!valid) censusFailure()
}

private fun censusFailure(): Nothing = throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
