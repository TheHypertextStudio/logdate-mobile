@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.PasskeyAuthenticationOptions
import kotlin.uuid.Uuid

internal const val IDENTITY_ADOPTION_CHALLENGE_TYPE = "identity_adoption"

/** A decision identifier must come from the server's immutable account/operation/revision binding. */
internal class IdentityAdoptionPasskeys(
    private val webAuthn: WebAuthnPasskeyService,
) {
    init {
        check(webAuthn.strictVerificationEnabled) { "Identity adoption requires cryptographic passkey verification" }
    }

    fun begin(
        accountId: Uuid,
        decisionId: Uuid,
    ): PasskeyAuthenticationOptions {
        val credentials = webAuthn.getUserCredentials(accountId)
        if (credentials.isEmpty()) throw IdentityAdoptionPasskeyUnavailable()
        return webAuthn
            .generateBoundAuthenticationOptions(IDENTITY_ADOPTION_CHALLENGE_TYPE, decisionId)
            .copy(allowCredentials = credentials)
    }

    fun verify(
        accountId: Uuid,
        decisionId: Uuid,
        proof: PasskeyStepUpProof,
    ): Boolean = verify(accountId, decisionId, proof.adoptionProof())

    fun verify(
        accountId: Uuid,
        decisionId: Uuid,
        proof: IdentityAdoptionPasskeyProof,
    ): Boolean {
        val result =
            webAuthn.verifyBoundAuthentication(
                challenge = proof.challenge,
                authenticationResponse = proof.credential,
                purpose = IDENTITY_ADOPTION_CHALLENGE_TYPE,
                binding = decisionId,
            )
        return result.success && result.userId == accountId
    }
}

internal class IdentityAdoptionPasskeyUnavailable : IllegalStateException("Identity adoption requires a registered passkey")
