package app.logdate.space.adoption

import app.logdate.space.LosslessSealedRecordCodec
import app.logdate.space.SealingKeys
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import studio.hypertext.atproto.lexicon.data.Bytes

public data class IdentityAdoptionExpectedRecord(
    public val key: IdentityAdoptionWireKey,
    public val version: Long,
    public val digest: Bytes,
    public val deleted: Boolean,
    public val blobs: Set<String>,
)

/** Record evidence only; full-operation completeness and media authentication are separate checks. */
public object IdentityAdoptionRecordVerifier {
    public suspend fun verify(
        keys: SealingKeys,
        sourceSpace: SpaceRef,
        targetSpace: SpaceRef,
        expected: IdentityAdoptionExpectedRecord,
        sourceVersion: Long,
        source: JsonObject,
        targetKey: IdentityAdoptionWireKey,
        target: JsonObject,
    ): IdentityAdoptionRecordEvidence =
        IdentityAdoptionWire.protect {
            IdentityAdoptionReferencePolicy.validateScope(sourceSpace, targetSpace)
            validate(expected, sourceVersion, source, targetKey, deleted = false)
            val original =
                LosslessSealedRecordCodec.open(
                    keys,
                    sourceSpace,
                    sourceSpace.authority,
                    expected.key.recordKey,
                    IdentityAdoptionWire.envelope(source),
                )
            val readback =
                LosslessSealedRecordCodec.open(
                    keys,
                    targetSpace,
                    targetSpace.authority,
                    targetKey.recordKey,
                    IdentityAdoptionWire.envelope(target),
                )
            val converted = IdentityAdoptionReferencePolicy.convert(original, sourceSpace, targetSpace)
            IdentityAdoptionWire.check(
                IdentityAdoptionEncoding.encode(converted.value).contentEquals(IdentityAdoptionEncoding.encode(readback.value)),
            )
            IdentityAdoptionWire.check(blobReferences(original.value) == expected.blobs)
            IdentityAdoptionRecordEvidence(
                expected.key,
                targetKey,
                sourceVersion,
                IdentityAdoptionWire.digest(source),
                IdentityAdoptionWire.digest(target),
                false,
            )
        }

    public suspend fun verifyTombstone(
        expected: IdentityAdoptionExpectedRecord,
        sourceVersion: Long,
        source: JsonObject,
        targetKey: IdentityAdoptionWireKey,
        target: JsonObject,
    ): IdentityAdoptionRecordEvidence =
        IdentityAdoptionWire.protect {
            validate(expected, sourceVersion, source, targetKey, deleted = true)
            IdentityAdoptionWire.check(expected.blobs.isEmpty() && source["deleted"] == JsonPrimitive(true))
            IdentityAdoptionWire.check(
                IdentityAdoptionEncoding.encode(source).contentEquals(IdentityAdoptionEncoding.encode(target)),
            )
            IdentityAdoptionRecordEvidence(
                expected.key,
                targetKey,
                sourceVersion,
                IdentityAdoptionWire.digest(source),
                IdentityAdoptionWire.digest(target),
                true,
            )
        }

    private suspend fun validate(
        expected: IdentityAdoptionExpectedRecord,
        version: Long,
        source: JsonObject,
        targetKey: IdentityAdoptionWireKey,
        deleted: Boolean,
    ) {
        IdentityAdoptionWire.check(expected.key.collection == "app.logdate.crypto.sealed" && targetKey == expected.key)
        val key = expected.key.recordKey
        IdentityAdoptionWire.check(
            key.length in 1..512 &&
                key != "." &&
                key != ".." &&
                key.all {
                    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._:~-"
                },
        )
        IdentityAdoptionWire.check(expected.version >= 0 && expected.version == version && expected.deleted == deleted)
        IdentityAdoptionWire.check(expected.digest.size == 32 && expected.digest == IdentityAdoptionWire.digest(source))
    }

    private fun blobReferences(value: JsonElement): Set<String> =
        when (value) {
            is JsonObject -> {
                val result = mutableSetOf<String>()
                if (value["\$type"] == JsonPrimitive("blob")) {
                    val ref = value["ref"] as? JsonObject
                    val cid = ref?.get("\$link") as? JsonPrimitive
                    IdentityAdoptionWire.check(ref?.size == 1 && cid?.isString == true)
                    result.add(requireNotNull(cid).content)
                }
                for (child in value.values) result.addAll(blobReferences(child))
                result
            }
            is JsonArray -> value.flatMap { blobReferences(it) }.toSet()
            else -> emptySet()
        }
}
