package app.logdate.server.identity.adoption

import kotlinx.serialization.json.JsonObject

internal data class IdentityAdoptionSourceRecordKey(
    val collection: String,
    val rkey: String,
)

internal data class IdentityAdoptionGenericRequest(
    val source: IdentityAdoptionSourceReference,
    val sourceDigest: String,
    val rkey: String,
    val record: JsonObject?,
    val blobs: List<String>,
    val repositoryRecord: IdentityAdoptionSourceRecordKey? = null,
) {
    override fun toString(): String = "IdentityAdoptionGenericRequest()"
}

internal data class IdentityAdoptionStagedOrigin(
    val source: IdentityAdoptionSourceReference,
    val sourceDigest: String,
    val sourceRecord: IdentityAdoptionSourceRecordKey,
    val sourceVersion: Long,
    val sourceDeletedAt: String?,
    val targetRkey: String,
    val targetSequence: Long,
    val targetDigest: String,
    val bodyDigest: String,
    val storageBytes: Long,
) {
    override fun toString(): String = "IdentityAdoptionStagedOrigin()"
}

internal data class IdentityAdoptionGenericCopy(
    val record: IdentityAdoptionStagedRecord,
    val origin: IdentityAdoptionStagedOrigin,
) {
    override fun toString(): String = "IdentityAdoptionGenericCopy()"
}
