package app.logdate.space.adoption

import app.logdate.space.Base32
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.lexicon.data.Bytes

/** A streaming inventory. Finalization is terminal and repeatable; persisted evidence can be replayed. */
public class IdentityAdoptionRecordInventory : AutoCloseable {
    private val hash = IdentityAdoptionEvidenceHash("records")
    private var last: IdentityAdoptionRecordEvidence? = null
    private val targets = mutableSetOf<IdentityAdoptionWireKey>()
    private var count = 0L
    private var live = 0L
    private var tombstones = 0L
    private var result: IdentityAdoptionRecordSummary? = null

    public fun append(evidence: IdentityAdoptionRecordEvidence) {
        hash.checkOpen()
        validate(evidence.source)
        validate(evidence.target)
        evidenceCheck(evidence.sourceVersion >= 0 && evidence.sourceDigest.size == 32 && evidence.targetDigest.size == 32)
        evidenceCheck(count < Long.MAX_VALUE)
        last?.let {
            val sourceOrder = compareValuesBy(it.source, evidence.source, { key -> key.collection }, { key -> key.recordKey })
            val targetOrder = compareValuesBy(it.target, evidence.target, { key -> key.collection }, { key -> key.recordKey })
            if (sourceOrder > 0 || (sourceOrder == 0 && targetOrder >= 0)) {
                throw IdentityAdoptionException(IdentityAdoptionFailure.UNORDERED_EVIDENCE)
            }
            if (sourceOrder == 0) {
                evidenceCheck(it.sourceVersion == evidence.sourceVersion && it.sourceDigest == evidence.sourceDigest)
            }
        }
        if (evidence.target in targets) throw IdentityAdoptionException(IdentityAdoptionFailure.DUPLICATE_TARGET)
        hash.append(
            buildJsonObject {
                put("sourceCollection", evidence.source.collection)
                put("sourceRkey", evidence.source.recordKey)
                put("targetCollection", evidence.target.collection)
                put("targetRkey", evidence.target.recordKey)
                put("sourceVersion", evidence.sourceVersion)
                put("sourceDigest", IdentityAdoptionEncoding.bytes(evidence.sourceDigest))
                put("targetDigest", IdentityAdoptionEncoding.bytes(evidence.targetDigest))
                put("deleted", evidence.deleted)
            },
        )
        targets.add(evidence.target)
        last = evidence
        count++
        if (evidence.deleted) tombstones++ else live++
    }

    public fun finish(): IdentityAdoptionRecordSummary =
        result
            ?: IdentityAdoptionRecordSummary(count, live, tombstones, hash.finish()).also { result = it }

    override fun close() {
        hash.close()
    }

    private fun validate(key: IdentityAdoptionWireKey) {
        evidenceCheck(key.collection.length <= 317 && NSID.matches(key.collection))
        evidenceCheck(key.recordKey.length <= 512 && key.recordKey != "." && key.recordKey != ".." && RKEY.matches(key.recordKey))
    }

    private companion object {
        val NSID = Regex("[A-Za-z][A-Za-z0-9-]*(?:\\.[A-Za-z0-9-]+){2,}")
        val RKEY = Regex("[A-Za-z0-9._:~-]+")
    }
}

/** Ciphertext evidence; media authentication and reference registration remain separate requirements. */
public class IdentityAdoptionBlobInventory : AutoCloseable {
    private val hash = IdentityAdoptionEvidenceHash("blobs")
    private var last: String? = null
    private var count = 0L
    private var bytes = 0L
    private var result: IdentityAdoptionBlobSummary? = null

    public fun append(
        cid: String,
        size: Long,
        ciphertextDigest: Bytes,
    ) {
        hash.checkOpen()
        evidenceCheck(ciphertextDigest.size == 32 && size > 0 && count < Long.MAX_VALUE)
        evidenceCheck(bytes <= Long.MAX_VALUE - size)
        val expected = "b" + Base32.encode(byteArrayOf(1, 0x55, 0x12, 0x20) + ciphertextDigest.toByteArray())
        evidenceCheck(cid == expected)
        if (last?.let { it >= cid } == true) throw IdentityAdoptionException(IdentityAdoptionFailure.UNORDERED_EVIDENCE)
        hash.append(
            buildJsonObject {
                put("cid", cid)
                put("size", size)
                put("digest", IdentityAdoptionEncoding.bytes(ciphertextDigest))
            },
        )
        count++
        bytes += size
        last = cid
    }

    public fun finish(): IdentityAdoptionBlobSummary =
        result
            ?: IdentityAdoptionBlobSummary(count, bytes, hash.finish()).also { result = it }

    override fun close() {
        hash.close()
    }
}

private fun evidenceCheck(valid: Boolean) {
    if (!valid) throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
}
