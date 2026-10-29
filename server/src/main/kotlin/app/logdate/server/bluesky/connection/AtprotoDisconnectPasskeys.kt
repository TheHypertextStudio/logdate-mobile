@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.bluesky.connection

import app.logdate.server.passkeys.PasskeyStepUpProof
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import kotlin.uuid.Uuid

internal const val ATPROTO_DISCONNECT_CHALLENGE_TYPE = "atproto_disconnect"

internal class AtprotoDisconnectPasskeys(
    private val webAuthn: WebAuthnPasskeyService,
) {
    fun credentialsFor(accountId: Uuid): List<String> = webAuthn.getUserCredentials(accountId)

    fun begin(accountId: Uuid): PasskeyAuthenticationOptions =
        webAuthn
            .generateBoundAuthenticationOptions(ATPROTO_DISCONNECT_CHALLENGE_TYPE, accountId)
            .copy(allowCredentials = credentialsFor(accountId))

    fun verify(
        accountId: Uuid,
        proof: PasskeyStepUpProof,
    ): Boolean {
        val credential = proof.credential
        val result =
            webAuthn.verifyBoundAuthentication(
                challenge = proof.challenge,
                authenticationResponse =
                    PasskeyAuthenticationResponse(
                        id = credential.id,
                        rawId = credential.rawId,
                        response =
                            AuthenticatorAssertionResponse(
                                clientDataJSON = credential.response.clientDataJSON,
                                authenticatorData = credential.response.authenticatorData,
                                signature = credential.response.signature,
                                userHandle = credential.response.userHandle,
                            ),
                        type = credential.type,
                    ),
                purpose = ATPROTO_DISCONNECT_CHALLENGE_TYPE,
                binding = accountId,
            )
        return result.success && result.userId == accountId
    }
}
