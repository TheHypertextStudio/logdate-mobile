package app.logdate.server.passkeys

import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A fresh passkey confirmation for an action a signed-in session alone must not be able to take,
 * such as registering or replacing a recovery key. Someone who has only a stolen session token
 * cannot answer it, because it needs one of the account's passkeys and the person's unlock.
 */
@OptIn(ExperimentalUuidApi::class)
interface PasskeyStepUp {
    /** The account's passkey credential ids; a step-up needs at least one. */
    fun credentialsFor(accountId: Uuid): List<String>

    /** Issues a single-use challenge that only [accountId]'s passkeys can answer, valid for five minutes. */
    fun begin(accountId: Uuid): PasskeyAuthenticationOptions

    /** Whether [proof] answers a challenge from [begin] for [accountId]. The challenge is spent either way. */
    fun verify(
        accountId: Uuid,
        proof: PasskeyStepUpProof,
    ): Boolean

    /**
     * Issues a single-use grant, valid for five minutes, that [accountId] just confirmed with a
     * passkey. A sign-in or sign-up hands it to the app so the account's first recovery key can be
     * registered without asking for the passkey again.
     */
    fun grant(accountId: Uuid): String

    /** Whether [grant] came from [grant] for [accountId] and is unused and unexpired. It is spent either way. */
    fun redeem(
        accountId: Uuid,
        grant: String,
    ): Boolean
}

/** The passkey assertion a client sends with a request that needs a step-up. */
@Serializable
data class PasskeyStepUpProof(
    val challenge: String,
    val credential: PasskeyAuthenticationResponse,
) {
    constructor(challenge: String, credential: PasskeyAssertionResponse) : this(
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

    fun copy(
        challenge: String = this.challenge,
        credential: PasskeyAssertionResponse,
    ): PasskeyStepUpProof = PasskeyStepUpProof(challenge, credential)
}

/** [PasskeyStepUp] backed by the account's registered passkeys. */
@OptIn(ExperimentalUuidApi::class)
class WebAuthnPasskeyStepUp(
    private val webAuthnService: WebAuthnPasskeyService,
) : PasskeyStepUp {
    override fun credentialsFor(accountId: Uuid): List<String> = webAuthnService.getUserCredentials(accountId)

    override fun begin(accountId: Uuid): PasskeyAuthenticationOptions = webAuthnService.generateStepUpOptions(accountId)

    override fun verify(
        accountId: Uuid,
        proof: PasskeyStepUpProof,
    ): Boolean =
        webAuthnService
            .verifyStepUp(
                userId = accountId,
                challenge = proof.challenge,
                authenticationResponse =
                    PasskeyAuthenticationResponse(
                        id = proof.credential.id,
                        rawId = proof.credential.rawId,
                        response =
                            AuthenticatorAssertionResponse(
                                clientDataJSON = proof.credential.response.clientDataJSON,
                                authenticatorData = proof.credential.response.authenticatorData,
                                signature = proof.credential.response.signature,
                                userHandle = proof.credential.response.userHandle,
                            ),
                        type = proof.credential.type,
                    ),
            ).success

    override fun grant(accountId: Uuid): String = webAuthnService.issueStepUpGrant(accountId)

    override fun redeem(
        accountId: Uuid,
        grant: String,
    ): Boolean = webAuthnService.redeemStepUpGrant(accountId, grant)
}
