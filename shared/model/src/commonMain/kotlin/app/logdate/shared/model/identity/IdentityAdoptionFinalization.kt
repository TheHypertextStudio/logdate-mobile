package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionFinalizationStartRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val phase: IdentityAdoptionPhaseView,
    val action: IdentityAdoptionActionView,
    val manifestDigest: String,
    val coverageDigest: String,
) {
    override fun toString(): String = "IdentityAdoptionFinalizationStartRequest()"
}

@Serializable
data class IdentityAdoptionVerifyRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val manifest: String,
    val coverageDigest: String,
) {
    override fun toString(): String = "IdentityAdoptionVerifyRequest()"
}

@Serializable
data class IdentityAdoptionCommitRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val challenge: IdentityAdoptionActionChallengeView,
    val signature: String,
    val manifestDigest: String,
    val coverageDigest: String,
) {
    override fun toString(): String = "IdentityAdoptionCommitRequest()"
}
