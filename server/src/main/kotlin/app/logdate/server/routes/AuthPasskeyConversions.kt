@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.auth.AuthMetricsRegistry
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyCredentialResponse
import app.logdate.shared.model.PasskeyRegistrationResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

internal fun PasskeyCredentialResponse.toPasskeyRegistrationResponse(): PasskeyRegistrationResponse =
    PasskeyRegistrationResponse(
        id = id,
        rawId = rawId,
        response =
            AuthenticatorAttestationResponse(
                clientDataJSON = response.clientDataJSON,
                attestationObject = response.attestationObject,
            ),
        type = type,
    )

internal fun PasskeyAssertionResponse.toPasskeyAuthenticationResponse(): PasskeyAuthenticationResponse =
    PasskeyAuthenticationResponse(
        id = id,
        rawId = rawId,
        response =
            AuthenticatorAssertionResponse(
                clientDataJSON = response.clientDataJSON,
                authenticatorData = response.authenticatorData,
                signature = response.signature,
                userHandle = response.userHandle,
            ),
        type = type,
    )

internal suspend fun verifyAuthPasskeyRegistration(
    call: ApplicationCall,
    accountId: Uuid,
    challenge: String,
    credential: PasskeyCredentialResponse,
    nickname: String?,
    webAuthnService: WebAuthnPasskeyService,
    metrics: AuthMetricsRegistry,
): WebAuthnPasskeyService.VerifiedRegistration? {
    // Verification precedes all account and credential writes.
    return when (
        val outcome =
            webAuthnService.verifyRegistrationOnly(
                userId = accountId,
                challenge = challenge,
                registrationResponse = credential.toPasskeyRegistrationResponse(),
            )
    ) {
        is WebAuthnPasskeyService.VerificationOutcome.Failure ->
            call.rejectAuthRequest(
                HttpStatusCode.BadRequest,
                "PASSKEY_VERIFICATION_FAILED",
                outcome.error,
                metrics,
            )
        is WebAuthnPasskeyService.VerificationOutcome.Success -> outcome.data.withNickname(nickname)
    }
}
