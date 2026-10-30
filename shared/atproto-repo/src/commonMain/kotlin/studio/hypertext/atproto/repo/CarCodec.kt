package studio.hypertext.atproto.repo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Tid
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Serializes and deserializes repo exports into CAR v1 archives.
 */
@OptIn(ExperimentalEncodingApi::class)
public object CarCodec {
    /**
     * Writes [export] into CAR bytes.
     */
    public fun write(export: RepoExport): ByteArray {
        val blockMap = LinkedHashMap<Cid, RepoBlock>()
        export.blocks.forEach { block ->
            blockMap[block.cid] = block
        }
        export.commits.forEach { commit ->
            if (commit.cid in blockMap) return@forEach
            val bytes = encodeSignedCommitPayload(commit.commit, commit.signature)
            require(Cid.sha256(DAG_CBOR_CODEC, bytes) == commit.cid) {
                "Commit ${commit.cid} has no block in the export, and encoding it again gives bytes with a different CID. " +
                    "Include the stored commit block in the export."
            }
            blockMap[commit.cid] = RepoBlock(cid = commit.cid, bytes = bytes)
        }
        return writeCar(roots = listOf(export.head.commitCid), blocks = blockMap.values.toList())
    }

    /**
     * Reads [bytes] into a repo export.
     */
    public fun read(bytes: ByteArray): RepoExport {
        val archive = readCar(bytes)
        val commits = archive.blocks.mapNotNull(::decodeSignedCommitBlock).sortedBy { it.commit.revision }
        val headCommit = requireNotNull(commits.lastOrNull()) { "CAR archive did not contain a repo commit block" }
        val head =
            RepoHead(
                repo = headCommit.commit.repo,
                root = headCommit.commit.root,
                commitCid = archive.roots.singleOrNull() ?: headCommit.cid,
                revision = headCommit.commit.revision,
            )
        return RepoExport(
            repo = headCommit.commit.repo,
            head = head,
            commits = commits,
            blocks = archive.blocks,
        )
    }

    /**
     * Writes a raw CAR v1 archive for [roots] and [blocks].
     */
    public fun writeCar(
        roots: List<Cid>,
        blocks: List<RepoBlock>,
    ): ByteArray {
        val headerBytes =
            DagCborCodec.encode(
                buildJsonObject {
                    put("version", 1)
                    put(
                        "roots",
                        buildJsonArray {
                            roots.forEach { add(DagCborCodec.link(it)) }
                        },
                    )
                },
            )
        return joinByteArrays(
            buildList {
                add(writeSection(headerBytes))
                blocks.forEach { block ->
                    add(writeSection(block.cid.toBytes() + block.bytes))
                }
            },
        )
    }

    /**
     * Reads a CAR v1 archive.
     *
     * The archive may come from anyone, so every section, CID and varint length is checked against
     * the bytes that remain before it is used. Malformed input fails with an
     * [IllegalArgumentException] or [IllegalStateException].
     */
    public fun readCar(bytes: ByteArray): CarArchive {
        var cursor = 0
        val headerSection = readSection(bytes, cursor)
        cursor = headerSection.nextOffset
        val header =
            DagCborCodec.decode(headerSection.sectionBytes) as? JsonObject
                ?: throw IllegalArgumentException("CAR header must be a map")
        require((header["version"] as? JsonPrimitive)?.intOrNull == 1) { "Unsupported CAR version" }
        val encodedRoots = header["roots"] as? JsonArray ?: throw IllegalArgumentException("CAR header must list its roots")
        val roots =
            encodedRoots.map { encodedRoot ->
                requireNotNull(DagCborCodec.linkOrNull(encodedRoot)) { "CAR roots must be CID links" }
            }

        val blocks = mutableListOf<RepoBlock>()
        while (cursor < bytes.size) {
            val section = readSection(bytes, cursor)
            cursor = section.nextOffset
            val decoded = decodeCid(section.sectionBytes)
            blocks += RepoBlock(cid = decoded.cid, bytes = decoded.blockBytes)
        }
        return CarArchive(roots = roots, blocks = blocks)
    }

    internal fun decodeSignedCommitBlock(block: RepoBlock): SignedRepoCommit? =
        runCatching {
            val encoded = DagCborCodec.decode(block.bytes).jsonObject
            val signature =
                encoded["sig"]?.let(::decodeCommitSignature)
                    ?: encoded["sig"]?.jsonPrimitive?.contentOrNull
                    ?: return null
            val did = encoded["did"]?.jsonPrimitive?.content ?: encoded["repo"]?.jsonPrimitive?.content
            val data =
                DagCborCodec.linkOrNull(encoded["data"])?.toString()
                    ?: encoded["data"]?.jsonPrimitive?.contentOrNull
                    ?: DagCborCodec.linkOrNull(encoded["root"])?.toString()
                    ?: encoded["root"]?.jsonPrimitive?.contentOrNull
            val rev =
                encoded["rev"]
                    ?.jsonPrimitive
                    ?.content
                    ?.let(Tid::require)
                    ?.toLong()
                    ?: encoded["revision"]?.jsonPrimitive?.long
            val commit =
                RepoCommit(
                    repo = AtprotoDid.require(requireNotNull(did) { "Signed repo commit is missing did/repo" }),
                    root = Cid.require(requireNotNull(data) { "Signed repo commit is missing data/root" }),
                    prev =
                        DagCborCodec.linkOrNull(encoded["prev"])
                            ?: encoded["prev"]?.jsonPrimitive?.contentOrNull?.let(Cid::require),
                    revision = requireNotNull(rev) { "Signed repo commit is missing rev/revision" },
                    createdAtEpochMillis = encoded["createdAtEpochMillis"]?.jsonPrimitive?.long ?: 0L,
                    recordCount = encoded["recordCount"]?.jsonPrimitive?.int ?: 0,
                )
            SignedRepoCommit(cid = block.cid, commit = commit, signature = signature)
        }.getOrNull()

    private fun writeSection(sectionBytes: ByteArray): ByteArray = encodeVarintLong(sectionBytes.size.toLong()) + sectionBytes

    private fun readSection(
        bytes: ByteArray,
        offset: Int,
    ): CarSection {
        val (sectionLength, lengthBytes) = decodeVarintLong(bytes, offset)
        val start = offset + lengthBytes
        require(sectionLength <= bytes.size - start) { "Unexpected end of CAR input" }
        val end = start + sectionLength.toInt()
        return CarSection(
            sectionBytes = bytes.copyOfRange(start, end),
            nextOffset = end,
        )
    }

    private fun decodeCid(sectionBytes: ByteArray): DecodedCid {
        val (_, versionLength) = decodeVarintLong(sectionBytes, 0)
        val (_, codecLength) = decodeVarintLong(sectionBytes, versionLength)
        val digestCodeOffset = versionLength + codecLength
        val (_, digestCodeLength) = decodeVarintLong(sectionBytes, digestCodeOffset)
        val (digestSize, digestSizeLength) = decodeVarintLong(sectionBytes, digestCodeOffset + digestCodeLength)
        val digestOffset = digestCodeOffset + digestCodeLength + digestSizeLength
        require(digestSize <= sectionBytes.size - digestOffset) { "Invalid CID length inside CAR block" }
        val cidLength = digestOffset + digestSize.toInt()
        return DecodedCid(
            cid = Cid.fromBytes(sectionBytes.copyOfRange(0, cidLength)),
            blockBytes = sectionBytes.copyOfRange(cidLength, sectionBytes.size),
        )
    }
}

/**
 * Parsed CAR archive payload.
 */
public data class CarArchive(
    val roots: List<Cid>,
    val blocks: List<RepoBlock>,
)

private data class CarSection(
    val sectionBytes: ByteArray,
    val nextOffset: Int,
)

private data class DecodedCid(
    val cid: Cid,
    val blockBytes: ByteArray,
)

private fun joinByteArrays(chunks: List<ByteArray>): ByteArray {
    val totalSize = chunks.sumOf(ByteArray::size)
    val output = ByteArray(totalSize)
    var cursor = 0
    chunks.forEach { chunk ->
        chunk.copyInto(output, destinationOffset = cursor)
        cursor += chunk.size
    }
    return output
}

private fun encodeVarintLong(value: Long): ByteArray {
    require(value >= 0L) { "Varints must be non-negative" }
    var remaining = value
    val bytes = mutableListOf<Byte>()
    do {
        var next = (remaining and 0x7f).toInt()
        remaining = remaining ushr 7
        if (remaining != 0L) {
            next = next or 0x80
        }
        bytes += next.toByte()
    } while (remaining != 0L)
    return bytes.toByteArray()
}

private fun decodeVarintLong(
    bytes: ByteArray,
    offset: Int,
): Pair<Long, Int> {
    var value = 0L
    var shift = 0
    var index = offset
    while (index < bytes.size) {
        // Nine 7-bit groups hold any length a CAR can describe and keep the value non-negative.
        require(shift <= MAX_VARINT_SHIFT) { "CAR varint is longer than nine bytes" }
        val next = bytes[index].toInt() and 0xff
        value = value or ((next and 0x7f).toLong() shl shift)
        index += 1
        if ((next and 0x80) == 0) {
            return value to (index - offset)
        }
        shift += 7
    }
    error("Unexpected end of varint input")
}

private const val MAX_VARINT_SHIFT = 56
