@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.space.adoption

import app.logdate.space.SealedPayloadCbor
import app.logdate.space.SpaceRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.longOrNull
import studio.hypertext.atproto.lexicon.data.Bytes
import studio.hypertext.atproto.lexicon.data.LexiconData
import studio.hypertext.atproto.repo.DagCborCodec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/** Decode only the bounded canonical format produced by [IdentityAdoptionManifest.canonicalBytes]. */
public fun identityAdoptionManifestFromCanonicalBytes(bytes: ByteArray): IdentityAdoptionManifest =
    try {
        check(bytes.size in 1..16384)
        val snapshot = bytes.copyOf()
        SealedPayloadCbor.validate(snapshot)
        val root = DagCborCodec.decode(snapshot) as JsonObject
        val records = root.getValue("records") as JsonObject
        val blobs = root.getValue("blobs") as JsonObject
        val binding =
            IdentityAdoptionBinding(
                Uuid.parse(root.text("operationId")),
                Uuid.parse(root.text("accountId")),
                root.text("backendOrigin"),
                root.text("deviceId"),
                requireNotNull(SpaceRef.parse(root.text("sourceSpace"))),
                requireNotNull(SpaceRef.parse(root.text("targetSpace"))),
                root.number("sourceEpoch"),
                root.number("sourceSequence"),
            )
        val manifest =
            IdentityAdoptionManifest(
                binding,
                IdentityAdoptionRecordSummary(
                    records.number("count"),
                    records.number("live"),
                    records.number("tombstones"),
                    records.bytes("digest"),
                ),
                IdentityAdoptionBlobSummary(blobs.number("count"), blobs.number("bytes"), blobs.bytes("digest")),
                root.bytes("pendingCommitment"),
                root.bytes("conflictCommitment"),
            )
        check(manifest.canonicalBytes().contentEquals(snapshot))
        manifest
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_EVIDENCE)
    }

private fun JsonObject.text(key: String): String {
    val primitive = getValue(key) as JsonPrimitive
    check(primitive.isString)
    return primitive.content
}

private fun JsonObject.number(key: String): Long {
    val primitive = getValue(key) as JsonPrimitive
    check(!primitive.isString)
    return requireNotNull(primitive.longOrNull)
}

private fun JsonObject.bytes(key: String): Bytes = LexiconData.json.decodeFromJsonElement(Bytes.serializer(), getValue(key))
