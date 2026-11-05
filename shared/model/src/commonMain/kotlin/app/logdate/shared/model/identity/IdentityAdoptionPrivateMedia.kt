package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionMediaUploadView(
    val uploadId: String,
    val source: IdentityAdoptionSourceReferenceView,
    val sourceDigest: String,
    val cid: String,
    val size: Long,
) {
    override fun toString(): String = "IdentityAdoptionMediaUploadView()"
}

@Serializable
data class IdentityAdoptionMediaBeginRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val upload: IdentityAdoptionMediaUploadView,
) {
    override fun toString(): String = "IdentityAdoptionMediaBeginRequest()"
}

/** JSON for verification/readback; unpadded base64url JSON in the bounded upload proof header. */
@Serializable
data class IdentityAdoptionMediaUploadProof(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val uploadId: String,
) {
    override fun toString(): String = "IdentityAdoptionMediaUploadProof()"
}

@Serializable
data class IdentityAdoptionMediaUploadReceiptView(
    val uploadId: String,
    val generation: Long,
    val cid: String,
    val size: Long,
    val state: String,
)

@Serializable
data class IdentityAdoptionMediaUploadData(
    val operation: IdentityAdoptionOperationView,
    val upload: IdentityAdoptionMediaUploadReceiptView,
)

@Serializable
data class IdentityAdoptionMediaUploadResponse(
    val success: Boolean,
    val data: IdentityAdoptionMediaUploadData,
)

@Serializable
data class IdentityAdoptionMediaSourceRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val source: IdentityAdoptionSourceReferenceView,
    val sourceDigest: String,
) {
    override fun toString(): String = "IdentityAdoptionMediaSourceRequest()"
}
