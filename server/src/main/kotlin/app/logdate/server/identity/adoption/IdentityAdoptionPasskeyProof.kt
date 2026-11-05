package app.logdate.server.identity.adoption

import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse

/** Preserve an absent WebAuthn user handle; credential ownership is still cryptographically verified. */
internal data class IdentityAdoptionPasskeyProof(
    val challenge: String,
    val credential: PasskeyAuthenticationResponse,
) {
    override fun toString(): String = "IdentityAdoptionPasskeyProof()"
}

internal fun PasskeyStepUpProof.adoptionProof(): IdentityAdoptionPasskeyProof =
    IdentityAdoptionPasskeyProof(
        challenge,
        PasskeyAuthenticationResponse(
            credential.id,
            credential.rawId,
            AuthenticatorAssertionResponse(
                credential.response.clientDataJSON,
                credential.response.authenticatorData,
                credential.response.signature,
                credential.response.userHandle,
            ),
            credential.type,
        ),
    )
