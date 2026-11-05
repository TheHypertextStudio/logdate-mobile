package app.logdate.shared.model

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class PasskeyInfo(
    val id: @Contextual Uuid,
    val credentialId: String,
    val nickname: String?,
    val deviceType: String, // "platform", "cross-platform"
    val createdAt: kotlin.time.Instant,
    val lastUsedAt: kotlin.time.Instant?,
    val isActive: Boolean = true,
)

@Serializable
data class PublicKeyCredentialParameter(
    val type: String,
    val alg: Int,
) {
    companion object {
        /** COSE identifier for ECDSA with SHA-256, which every platform authenticator supports. */
        const val ALG_ES256 = -7

        /** COSE identifier for RSASSA-PKCS1-v1_5 with SHA-256, the usual fallback. */
        const val ALG_RS256 = -257

        /**
         * The algorithms to request when a server does not state its own.
         *
         * WebAuthn requires a non-empty `pubKeyCredParams`, so an absent list cannot mean "no
         * algorithms" - it means the server left the choice to the client.
         */
        val DEFAULT: List<PublicKeyCredentialParameter> =
            listOf(
                PublicKeyCredentialParameter(type = "public-key", alg = ALG_ES256),
                PublicKeyCredentialParameter(type = "public-key", alg = ALG_RS256),
            )
    }
}

@Serializable
data class PasskeyRegistrationOptions(
    val challenge: String,
    val rpId: String,
    val rpName: String,
    val user: PasskeyUser,
    /**
     * Algorithms the relying party will accept.
     *
     * Optional because the deployed server omits the field entirely, and a required field it
     * never sends made every account creation fail while deserializing the begin response -
     * surfacing to the user as a generic network error. Defaults to ES256 and RS256, which is
     * what a server that does state its preference asks for anyway.
     */
    val pubKeyCredParams: List<PublicKeyCredentialParameter> = PublicKeyCredentialParameter.DEFAULT,
    val excludeCredentials: List<String> = emptyList(),
    val timeout: Long = 300_000,
)

@Serializable
data class PasskeyAuthenticationOptions(
    val challenge: String,
    val rpId: String,
    val allowCredentials: List<String> = emptyList(),
    val timeout: Long = 300_000,
)

@Serializable
data class PasskeyUser(
    val id: String,
    val name: String,
    val displayName: String,
)

@Serializable
data class PasskeyCapabilities(
    val isSupported: Boolean,
    val isPlatformAuthenticatorAvailable: Boolean,
    val supportedAlgorithms: List<String> = emptyList(),
)

@Serializable
data class PasskeyRegistrationResponse(
    val id: String,
    val rawId: String,
    val response: AuthenticatorAttestationResponse,
    val type: String = "public-key",
)

@Serializable
data class PasskeyAuthenticationResponse(
    val id: String,
    val rawId: String,
    val response: AuthenticatorAssertionResponse,
    val type: String = "public-key",
)

@Serializable
data class AuthenticatorAttestationResponse(
    val clientDataJSON: String,
    val attestationObject: String,
)

@Serializable
data class AuthenticatorAssertionResponse(
    val clientDataJSON: String,
    val authenticatorData: String,
    val signature: String,
    val userHandle: String? = null,
)

@OptIn(ExperimentalUuidApi::class)
@Serializable
data class PasskeyChallenge(
    val challenge: String, // Base64URL encoded
    val userId: @Contextual Uuid,
    val type: String, // "registration", "authentication"
    val expiresAt: String,
    val isUsed: Boolean = false,
)
