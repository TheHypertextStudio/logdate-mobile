package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
enum class IdentityAdoptionActionView { HOLD, RENEW, STAGE_RECORDS, BEGIN_BLOB, FINALIZE_BLOB, READ_STAGED, VERIFY, COMMIT, CANCEL }

@Serializable
data class IdentityAdoptionActionChallengeView(
    val id: String,
    val nonce: String,
    val action: IdentityAdoptionActionView,
    val bodyDigest: String,
) {
    override fun toString(): String = "IdentityAdoptionActionChallengeView()"
}

@Serializable
data class IdentityAdoptionActionStartRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val action: IdentityAdoptionActionView,
) {
    override fun toString(): String = "IdentityAdoptionActionStartRequest()"
}

@Serializable
data class IdentityAdoptionActionData(
    val operation: IdentityAdoptionOperationView,
    val challenge: IdentityAdoptionActionChallengeView,
) {
    override fun toString(): String = "IdentityAdoptionActionData()"
}

@Serializable
data class IdentityAdoptionActionResponse(
    val success: Boolean,
    val data: IdentityAdoptionActionData,
) {
    override fun toString(): String = "IdentityAdoptionActionResponse()"
}

@Serializable
data class IdentityAdoptionLifecycleRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
) {
    override fun toString(): String = "IdentityAdoptionLifecycleRequest()"
}

@Serializable
data class IdentityAdoptionAbandonRequest(
    val expected: IdentityAdoptionExpectedOperation,
) {
    override fun toString(): String = "IdentityAdoptionAbandonRequest()"
}
