package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
enum class IdentityAdoptionPhaseView { PREPARING, FENCED, VERIFIED, COMMITTED, CANCELED }

@Serializable
data class IdentityAdoptionBindingView(
    val accountId: String,
    val sourceDid: String,
    val targetDid: String,
    val deviceId: String,
    val sourceEpoch: Long,
) {
    override fun toString(): String = "IdentityAdoptionBindingView()"
}

@Serializable
data class IdentityAdoptionOperationView(
    val id: String,
    val binding: IdentityAdoptionBindingView,
    val revision: Long,
    val phase: IdentityAdoptionPhaseView,
    val leaseExpiresAt: String,
) {
    override fun toString(): String = "IdentityAdoptionOperationView()"
}

@Serializable
data class IdentityAdoptionReceiptView(
    val operationId: String,
    val accountId: String,
    val sourceDid: String,
    val targetDid: String,
    val identityEpoch: Long,
    val committedAt: String,
) {
    override fun toString(): String = "IdentityAdoptionReceiptView()"
}

@Serializable
data class IdentityAdoptionAuthorizationView(
    val revision: Long,
    val deviceKey: IdentityAdoptionDeviceKeyView,
    val authorizedAt: String,
) {
    override fun toString(): String = "IdentityAdoptionAuthorizationView()"
}

@Serializable
data class IdentityAdoptionStatusData(
    val operation: IdentityAdoptionOperationView,
    val receipt: IdentityAdoptionReceiptView? = null,
    val authorization: IdentityAdoptionAuthorizationView? = null,
) {
    override fun toString(): String = "IdentityAdoptionStatusData()"
}

@Serializable
data class IdentityAdoptionStatusResponse(
    val success: Boolean,
    val data: IdentityAdoptionStatusData,
) {
    override fun toString(): String = "IdentityAdoptionStatusResponse()"
}
