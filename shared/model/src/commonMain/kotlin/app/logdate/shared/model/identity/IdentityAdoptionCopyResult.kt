package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionCopyTombstoneView(
    val rkey: String,
    val sequence: Long,
    val deletedAt: String,
) {
    override fun toString(): String = "IdentityAdoptionCopyTombstoneView()"
}

@Serializable
data class IdentityAdoptionCopiedRecordView(
    val generation: Long,
    val rkey: String,
    val sourceCollection: String,
    val sourceRkey: String,
    val sourceVersion: Long,
    val sourceDigest: String,
    val targetDigest: String,
    val cid: String?,
    val record: IdentityAdoptionSealedCopyView?,
    val blobs: List<String>,
    val tombstone: IdentityAdoptionCopyTombstoneView?,
    val targetSequence: Long,
) {
    override fun toString(): String = "IdentityAdoptionCopiedRecordView()"
}

@Serializable
data class IdentityAdoptionCopyOriginView(
    val source: IdentityAdoptionSourceReferenceView,
    val sourceDigest: String,
    val sourceRecord: IdentityAdoptionLogicalRecordView,
    val sourceVersion: Long,
    val sourceDeletedAt: String?,
    val targetRkey: String,
    val targetSequence: Long,
    val targetDigest: String,
    val bodyDigest: String,
    val storageBytes: Long,
) {
    override fun toString(): String = "IdentityAdoptionCopyOriginView()"
}

@Serializable
data class IdentityAdoptionCopiedData(
    val operation: IdentityAdoptionOperationView,
    val record: IdentityAdoptionCopiedRecordView,
    val origin: IdentityAdoptionCopyOriginView,
) {
    override fun toString(): String = "IdentityAdoptionCopiedData()"
}

@Serializable
data class IdentityAdoptionCopiedResponse(
    val success: Boolean,
    val data: IdentityAdoptionCopiedData,
) {
    override fun toString(): String = "IdentityAdoptionCopiedResponse()"
}
