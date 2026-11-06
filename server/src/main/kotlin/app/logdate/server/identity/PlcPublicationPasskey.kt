package app.logdate.server.identity

import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Binds a fresh passkey assertion to the immutable publication review stored under [reviewId]. */
@OptIn(ExperimentalUuidApi::class)
internal class PlcPublicationPasskey(
    private val webAuthn: WebAuthnPasskeyService,
    private val purpose: String = PLC_PUBLICATION_PURPOSE,
) {
    init {
        require(webAuthn.strictVerificationEnabled) { "Publication requires strict passkey verification" }
    }

    fun begin(
        accountId: Uuid,
        reviewId: Uuid,
    ): PasskeyAuthenticationOptions =
        webAuthn
            .generateBoundAuthenticationOptions(purpose, reviewId)
            .copy(allowCredentials = webAuthn.getUserCredentials(accountId))

    fun verify(
        accountId: Uuid,
        reviewId: Uuid,
        challenge: String,
        credential: PasskeyAuthenticationResponse,
    ): Boolean {
        val result = webAuthn.verifyBoundAuthentication(challenge, credential, purpose, reviewId)
        return result.success && result.userId == accountId
    }
}

private const val PLC_PUBLICATION_PURPOSE = "plc_publish"
