package app.logdate.shared.model.identity

import kotlinx.serialization.Serializable

@Serializable
data class IdentityAdoptionOAuthStartRequest(
    val handle: String,
    val challenge: String,
    val deviceId: String,
    val sourceDid: String,
    val sourceEpoch: Long,
) {
    override fun toString(): String = "IdentityAdoptionOAuthStartRequest()"
}

@Serializable
data class IdentityAdoptionOAuthStartData(
    val proposalId: String,
    val accountId: String,
    val deviceId: String,
    val sourceDid: String,
    val sourceEpoch: Long,
    val attemptId: String,
    val authorizationUrl: String,
    val expiresAt: String,
) {
    override fun toString(): String = "IdentityAdoptionOAuthStartData()"
}

@Serializable
data class IdentityAdoptionOAuthStartResponse(
    val success: Boolean,
    val data: IdentityAdoptionOAuthStartData,
) {
    override fun toString(): String = "IdentityAdoptionOAuthStartResponse()"
}

@Serializable
data class IdentityAdoptionOAuthExchangeRequest(
    val proposalId: String,
    val attemptId: String,
    val verifier: String,
    val deviceId: String,
    val sourceDid: String,
    val sourceEpoch: Long,
) {
    override fun toString(): String = "IdentityAdoptionOAuthExchangeRequest()"
}

@Serializable
data class IdentityAdoptionBrowserCancelData(
    val proposalId: String,
    val accountId: String,
    val deviceId: String,
    val sourceDid: String,
    val sourceEpoch: Long,
    val canceledAt: String,
) {
    override fun toString(): String = "IdentityAdoptionBrowserCancelData()"
}

@Serializable
data class IdentityAdoptionBrowserCancelResponse(
    val success: Boolean,
    val data: IdentityAdoptionBrowserCancelData,
) {
    override fun toString(): String = "IdentityAdoptionBrowserCancelResponse()"
}
