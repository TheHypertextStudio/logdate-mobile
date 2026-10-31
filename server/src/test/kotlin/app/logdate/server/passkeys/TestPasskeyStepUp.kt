package app.logdate.server.passkeys

import app.logdate.shared.model.PasskeyAssertionAuthenticatorResponse
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A [PasskeyStepUp] for route tests that are about something else: it accepts each challenge it
 * issued once, for the account it issued it to. The real verification is covered by
 * `RecoveryKeyRoutesTest`, which uses [WebAuthnPasskeyStepUp].
 */
@OptIn(ExperimentalUuidApi::class)
class TestPasskeyStepUp : PasskeyStepUp {
    private val issued = mutableMapOf<String, Uuid>()

    override suspend fun credentialsFor(accountId: Uuid): List<String> = listOf(CREDENTIAL_ID)

    override suspend fun begin(accountId: Uuid): PasskeyAuthenticationOptions {
        val challenge = "step-up-${issued.size + 1}"
        issued[challenge] = accountId
        return PasskeyAuthenticationOptions(challenge = challenge, rpId = "logdate.app", allowCredentials = listOf(CREDENTIAL_ID))
    }

    override suspend fun verify(
        accountId: Uuid,
        proof: PasskeyStepUpProof,
    ): Boolean = issued.remove(proof.challenge) == accountId

    private val grants = mutableMapOf<String, Uuid>()

    override suspend fun grant(accountId: Uuid): String = "grant-${grants.size + 1}".also { grants[it] = accountId }

    override suspend fun redeem(
        accountId: Uuid,
        grant: String,
    ): Boolean = grants.remove(grant) == accountId

    /** Begins a step-up for [accountId] and answers it, as a device with the account's passkey would. */
    suspend fun proofFor(accountId: Uuid): PasskeyStepUpProof =
        PasskeyStepUpProof(
            challenge = begin(accountId).challenge,
            credential =
                PasskeyAssertionResponse(
                    id = CREDENTIAL_ID,
                    rawId = CREDENTIAL_ID,
                    response =
                        PasskeyAssertionAuthenticatorResponse(
                            clientDataJSON = "e30",
                            authenticatorData = "AA",
                            signature = "AA",
                            userHandle = accountId.toString(),
                        ),
                ),
        )

    private companion object {
        private const val CREDENTIAL_ID = "test-passkey"
    }
}
