package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionMediaReuseRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val cid: String,
) {
    override fun toString(): String = "IdentityAdoptionMediaReuseRequest()"
}

@Serializable
data class IdentityAdoptionMediaVerificationView(
    val operationId: String,
    val admissionId: String,
    val generation: Long,
    val cid: String,
    val size: Long,
    val mimeType: String,
) {
    override fun toString(): String = "IdentityAdoptionMediaVerificationView()"
}

@Serializable
data class IdentityAdoptionMediaData(
    val operation: IdentityAdoptionOperationView,
    val media: IdentityAdoptionMediaVerificationView,
) {
    override fun toString(): String = "IdentityAdoptionMediaData()"
}

@Serializable
data class IdentityAdoptionMediaResponse(
    val success: Boolean,
    val data: IdentityAdoptionMediaData,
) {
    override fun toString(): String = "IdentityAdoptionMediaResponse()"
}
