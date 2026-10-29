package app.logdate.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BlueskyExchangeRequest(
    val attemptId: String,
    val verifier: String,
)

@Serializable
enum class BlueskyAccountNextStep {
    @SerialName("create_account")
    CREATE_ACCOUNT,

    @SerialName("confirm_passkey")
    CONFIRM_PASSKEY,
}

@Serializable
data class BlueskyExchangeResponse(
    val continuationToken: String,
    val nextStep: BlueskyAccountNextStep,
)

@Serializable
data class BlueskyRegistrationBeginRequest(
    val continuationToken: String,
    val requestedOwnerId: String,
    val username: String,
    val displayName: String,
    val bio: String? = null,
    val originalBio: String? = null,
)

@Serializable
data class BlueskyRegistrationBeginResponse(
    val continuationToken: String,
    val options: PasskeyRegistrationOptions,
)

@Serializable
data class BlueskyRegistrationCompleteRequest(
    val continuationToken: String,
    val credential: PasskeyRegistrationResponse,
    val nickname: String? = null,
)

@Serializable
data class BlueskyAuthenticationBeginRequest(
    val continuationToken: String,
)

@Serializable
data class BlueskyAuthenticationBeginResponse(
    val continuationToken: String,
    val options: PasskeyAuthenticationOptions,
)

@Serializable
data class BlueskyAuthenticationCompleteRequest(
    val continuationToken: String,
    val credential: PasskeyAuthenticationResponse,
)
