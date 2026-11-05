package app.logdate.shared.model.identity

import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionExpectedOperation(
    val deviceId: String,
    val sourceDid: String,
    val targetDid: String,
    val sourceEpoch: Long,
    val expectedRevision: Long,
) {
    override fun toString(): String = "IdentityAdoptionExpectedOperation()"
}

@Serializable
data class IdentityAdoptionDeviceKeyView(
    val origin: String,
    val publicKey: String,
) {
    override fun toString(): String = "IdentityAdoptionDeviceKeyView()"
}

@Serializable
data class IdentityAdoptionConfirmationStartRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val deviceKey: IdentityAdoptionDeviceKeyView,
) {
    override fun toString(): String = "IdentityAdoptionConfirmationStartRequest()"
}

@Serializable
data class IdentityAdoptionConfirmationRequest(
    val expected: IdentityAdoptionExpectedOperation,
    val decisionId: String,
    val challenge: String,
    val credential: PasskeyAuthenticationResponse,
    val deviceSignature: String,
) {
    override fun toString(): String = "IdentityAdoptionConfirmationRequest()"
}

@Serializable
data class IdentityAdoptionConfirmationData(
    val operation: IdentityAdoptionOperationView,
    val decisionId: String,
    val options: PasskeyAuthenticationOptions,
    val expiresAt: String,
    val deviceKey: IdentityAdoptionDeviceKeyView,
) {
    override fun toString(): String = "IdentityAdoptionConfirmationData()"
}

@Serializable
data class IdentityAdoptionConfirmationResponse(
    val success: Boolean,
    val data: IdentityAdoptionConfirmationData,
) {
    override fun toString(): String = "IdentityAdoptionConfirmationResponse()"
}
