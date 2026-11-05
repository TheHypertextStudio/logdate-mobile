package app.logdate.server.identity.adoption

import kotlinx.serialization.json.JsonObject

internal data class IdentityAdoptionStagingRequest(
    val rkey: String,
    val sourceVersion: Long,
    val sourceDigest: String,
    val record: JsonObject?,
    val blobs: List<String>,
)

internal data class IdentityAdoptionStagedRecord(
    val generation: Long,
    val rkey: String,
    val sourceCollection: String,
    val sourceRkey: String,
    val sourceVersion: Long,
    val sourceDigest: String,
    val targetDigest: String,
    val cid: String?,
    val record: JsonObject?,
    val blobs: List<String>,
    val tombstone: JsonObject? = null,
    val targetSequence: Long? = null,
)

internal enum class IdentityAdoptionStagingFailure { INVALID_REQUEST, SOURCE_CHANGED, UNSUPPORTED_SOURCE, CONFLICT, QUOTA }

internal class IdentityAdoptionStagingException(
    val reason: IdentityAdoptionStagingFailure,
) : RuntimeException("The private copy could not be prepared")
