@file:OptIn(ExperimentalUuidApi::class)

package app.logdate.space.adoption

import app.logdate.space.SpaceRef
import app.logdate.space.SpaceTypes
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.hypertext.atproto.lexicon.data.Bytes
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public enum class IdentityAdoptionFailure {
    INVALID_BINDING,
    INVALID_EVIDENCE,
    UNSUPPORTED_RECORD,
    UNORDERED_EVIDENCE,
    DUPLICATE_TARGET,
    INCOMPLETE_INVENTORY,
}

public class IdentityAdoptionException(
    public val reason: IdentityAdoptionFailure,
) : Exception("Identity adoption could not be verified")

public data class IdentityAdoptionWireKey(
    public val collection: String,
    public val recordKey: String,
)

public data class IdentityAdoptionRecordEvidence(
    public val source: IdentityAdoptionWireKey,
    public val target: IdentityAdoptionWireKey,
    public val sourceVersion: Long,
    public val sourceDigest: Bytes,
    public val targetDigest: Bytes,
    public val deleted: Boolean,
)

public data class IdentityAdoptionRecordSummary(
    public val count: Long,
    public val live: Long,
    public val tombstones: Long,
    public val digest: Bytes,
)

public data class IdentityAdoptionBlobSummary(
    public val count: Long,
    public val bytes: Long,
    public val digest: Bytes,
)

public data class IdentityAdoptionBinding(
    public val operationId: Uuid,
    public val accountId: Uuid,
    public val backendOrigin: String,
    public val deviceId: String,
    public val source: SpaceRef,
    public val target: SpaceRef,
    public val sourceEpoch: Long,
    public val sourceSequence: Long,
)

/** Canonical operation evidence; it never contains decrypted journal records. */
public data class IdentityAdoptionManifest(
    public val binding: IdentityAdoptionBinding,
    public val records: IdentityAdoptionRecordSummary,
    public val blobs: IdentityAdoptionBlobSummary,
    public val pendingCommitment: Bytes,
    public val conflictCommitment: Bytes,
) {
    public fun canonicalBytes(): ByteArray {
        validateBinding()
        validateSummaries()
        val value =
            buildJsonObject {
                put("version", 1L)
                put("operationId", binding.operationId.toString())
                put("accountId", binding.accountId.toString())
                put("backendOrigin", identityAdoptionOrigin(binding.backendOrigin))
                put("deviceId", binding.deviceId)
                put("sourceSpace", binding.source.uri)
                put("targetSpace", binding.target.uri)
                put("sourceEpoch", binding.sourceEpoch)
                put("sourceSequence", binding.sourceSequence)
                put(
                    "records",
                    buildJsonObject {
                        put("count", records.count)
                        put("live", records.live)
                        put("tombstones", records.tombstones)
                        put("digest", IdentityAdoptionEncoding.bytes(records.digest))
                    },
                )
                put(
                    "blobs",
                    buildJsonObject {
                        put("count", blobs.count)
                        put("bytes", blobs.bytes)
                        put("digest", IdentityAdoptionEncoding.bytes(blobs.digest))
                    },
                )
                put("pendingCommitment", IdentityAdoptionEncoding.bytes(pendingCommitment))
                put("conflictCommitment", IdentityAdoptionEncoding.bytes(conflictCommitment))
            }
        return IdentityAdoptionEncoding.encode(value)
    }

    public suspend fun digest(): Bytes =
        Bytes(
            CryptographyProvider.Default
                .get(SHA256)
                .hasher()
                .hash(canonicalBytes()),
        )

    private fun validateBinding() {
        bindingCheck(binding.sourceEpoch in 0 until Long.MAX_VALUE && binding.sourceSequence >= 0)
        bindingCheck(binding.deviceId.isNotEmpty() && binding.deviceId.encodeToByteArray().size <= 128)
        bindingCheck(binding.deviceId.none { it.isISOControl() || it.category == CharCategory.FORMAT })
        bindingCheck(binding.source.authority != binding.target.authority)
        bindingCheck(
            listOf(binding.source, binding.target).all {
                SpaceRef.parse(it.uri) == it && it.type == SpaceTypes.PERSONAL && it.skey == "self"
            },
        )
    }

    private fun validateSummaries() {
        bindingCheck(records.count >= 0 && records.live >= 0 && records.tombstones >= 0)
        bindingCheck(records.live <= Long.MAX_VALUE - records.tombstones)
        bindingCheck(records.count == records.live + records.tombstones)
        bindingCheck(blobs.count >= 0 && blobs.bytes >= blobs.count && (blobs.count != 0L || blobs.bytes == 0L))
        bindingCheck(listOf(records.digest, blobs.digest, pendingCommitment, conflictCommitment).all { it.size == 32 })
    }

    private fun bindingCheck(valid: Boolean) {
        if (!valid) throw IdentityAdoptionException(IdentityAdoptionFailure.INVALID_BINDING)
    }
}
