package app.logdate.server.passkeys

import app.logdate.server.config.profileAwareBoolEnv
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyChallenge
import app.logdate.shared.model.PasskeyRegistrationOptions
import app.logdate.shared.model.PasskeyRegistrationResponse
import app.logdate.shared.model.PasskeyUser
import app.logdate.shared.model.PublicKeyCredentialParameter
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.exception.DataConversionException
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticationRequest
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.RegistrationRequest
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import io.github.aakira.napier.Napier
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val CHALLENGE_TYPE_REGISTRATION = "restore-registration"
private const val CHALLENGE_TYPE_AUTHENTICATION = "restore-authentication"

/**
 * Standalone WebAuthn service for the restore credential flow.
 *
 * Uses separately scoped challenges, WebAuthn4J instances, and a repository isolated
 * from [WebAuthnPasskeyService]. Restore credentials are not user-visible passkeys and have
 * a persistent lifecycle: each installation may register a key; authentication consumes only its challenge.
 */
@OptIn(ExperimentalUuidApi::class)
class RestoreCredentialService(
    private val restoreCredentialRepository: RestoreCredentialRepository,
    val relyingPartyId: String = "logdate.app",
    private val relyingPartyName: String = "LogDate",
    private val origins: Set<String> = setOf("https://app.logdate.com"),
    private val strictVerificationEnabled: Boolean =
        profileAwareBoolEnv(
            name = "WEBAUTHN_STRICT_VERIFICATION",
            productionDefault = true,
            devDefault = false,
        ),
    private val challengeRepository: PasskeyChallengeRepository? = null,
) {
    private val challenges = ConcurrentHashMap<String, PasskeyChallenge>()
    private val challengeStore = PasskeyChallenges("restore", challenges, challengeRepository)
    private val objectConverter = ObjectConverter()
    private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    private val attestedCredentialDataConverter = AttestedCredentialDataConverter(objectConverter)

    // All client origins accepted during verification: the https web origin plus any Android
    // apk-key-hash origins. Restore credentials are minted by the same Credential Manager ceremony.
    private val webAuthnOrigins: Set<Origin> = origins.map { Origin(it) }.toSet()

    data class RegistrationResult(
        val success: Boolean,
        val credentialId: String? = null,
        val error: String? = null,
    )

    data class AuthenticationResult(
        val success: Boolean,
        val userId: Uuid? = null,
        val credentialId: String? = null,
        val error: String? = null,
    )

    suspend fun generateRegistrationOptions(
        userId: Uuid,
        username: String,
        displayName: String,
    ): PasskeyRegistrationOptions {
        val challenge = challengeStore.issue(userId, CHALLENGE_TYPE_REGISTRATION)

        val excludeCredentials = restoreCredentialRepository.getCredentialIdsForUser(userId)

        return PasskeyRegistrationOptions(
            challenge = challenge,
            rpId = relyingPartyId,
            rpName = relyingPartyName,
            user =
                PasskeyUser(
                    id = Base64.getEncoder().encodeToString(userId.toString().toByteArray()),
                    name = username,
                    displayName = displayName,
                ),
            pubKeyCredParams =
                listOf(
                    PublicKeyCredentialParameter(
                        type = "public-key",
                        alg = -7,
                    ),
                ),
            excludeCredentials = excludeCredentials,
            timeout = 300_000L,
        )
    }

    suspend fun verifyRegistration(
        userId: Uuid,
        challenge: String,
        registrationResponse: PasskeyRegistrationResponse,
    ): RegistrationResult =
        if (strictVerificationEnabled) {
            verifyRegistrationStrict(userId, challenge, registrationResponse)
        } else {
            verifyRegistrationSimplified(userId, challenge, registrationResponse)
        }

    suspend fun generateAuthOptions(): PasskeyAuthenticationOptions {
        val challenge = challengeStore.issue(Uuid.random(), CHALLENGE_TYPE_AUTHENTICATION)

        return PasskeyAuthenticationOptions(
            challenge = challenge,
            rpId = relyingPartyId,
            allowCredentials = emptyList(),
            timeout = 300_000L,
        )
    }

    suspend fun verifyAuthentication(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
    ): AuthenticationResult =
        if (strictVerificationEnabled) {
            verifyAuthenticationStrict(challenge, authenticationResponse)
        } else {
            verifyAuthenticationSimplified(challenge, authenticationResponse)
        }

    private suspend fun verifyRegistrationSimplified(
        userId: Uuid,
        challenge: String,
        registrationResponse: PasskeyRegistrationResponse,
    ): RegistrationResult =
        try {
            challengeStore.consume(challenge, CHALLENGE_TYPE_REGISTRATION, userId)
                ?: return RegistrationResult(success = false, error = "Invalid or expired challenge")

            val credentialId = normalizeCredentialId(registrationResponse.id) ?: registrationResponse.id

            val stored =
                restoreCredentialRepository.store(
                    userId = userId,
                    credentialId = credentialId,
                    publicKey = registrationResponse.response.attestationObject.toByteArray(),
                    signCount = 0L,
                )

            if (!stored) return RegistrationResult(success = false, error = "Restore credential could not be stored")
            RegistrationResult(success = true, credentialId = credentialId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Restore registration verification failed", e)
            RegistrationResult(success = false, error = "Registration verification failed: ${e.message}")
        }

    private suspend fun verifyRegistrationStrict(
        userId: Uuid,
        challenge: String,
        registrationResponse: PasskeyRegistrationResponse,
    ): RegistrationResult =
        try {
            challengeStore.consume(challenge, CHALLENGE_TYPE_REGISTRATION, userId)
                ?: return RegistrationResult(success = false, error = "Invalid or expired challenge")

            val challengeBytes =
                decodeBase64Url(challenge)
                    ?: return RegistrationResult(success = false, error = "Challenge is not valid base64url")
            val attestationObjectBytes =
                decodeBase64Url(registrationResponse.response.attestationObject)
                    ?: return RegistrationResult(success = false, error = "Attestation object is not valid base64url")
            val clientDataJsonBytes =
                decodeBase64Url(registrationResponse.response.clientDataJSON)
                    ?: return RegistrationResult(success = false, error = "Client data is not valid base64url")

            val serverProperty =
                ServerProperty
                    .builder()
                    .origins(webAuthnOrigins)
                    .rpId(relyingPartyId)
                    .challenge(DefaultChallenge(challengeBytes))
                    .build()

            val registrationData =
                webAuthnManager.verify(
                    RegistrationRequest(attestationObjectBytes, clientDataJsonBytes),
                    RegistrationParameters(serverProperty, null, true, true),
                )
            val attestedCredentialData =
                registrationData.attestationObject
                    ?.authenticatorData
                    ?.attestedCredentialData
                    ?: return RegistrationResult(success = false, error = "Attested credential data is missing")

            val canonicalCredentialId = encodeBase64Url(attestedCredentialData.credentialId)
            val publicKeyBytes = attestedCredentialDataConverter.convert(attestedCredentialData)

            val stored =
                restoreCredentialRepository.store(
                    userId = userId,
                    credentialId = canonicalCredentialId,
                    publicKey = publicKeyBytes,
                    signCount = registrationData.attestationObject?.authenticatorData?.signCount ?: 0L,
                )

            if (!stored) return RegistrationResult(success = false, error = "Restore credential could not be stored")
            RegistrationResult(success = true, credentialId = canonicalCredentialId)
        } catch (e: DataConversionException) {
            RegistrationResult(success = false, error = "Registration data conversion failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Restore registration strict verification failed", e)
            RegistrationResult(success = false, error = "Registration verification failed: ${e.message}")
        }

    private suspend fun verifyAuthenticationSimplified(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
    ): AuthenticationResult =
        try {
            challengeStore.consume(challenge, CHALLENGE_TYPE_AUTHENTICATION, null)
                ?: return AuthenticationResult(success = false, error = "Invalid or expired challenge")

            val stored =
                restoreCredentialRepository.findByCredentialId(authenticationResponse.id)
                    ?: normalizeCredentialId(authenticationResponse.id)?.let {
                        restoreCredentialRepository.findByCredentialId(it)
                    }
                    ?: return AuthenticationResult(success = false, error = "Restore credential not found")

            if (!restoreCredentialRepository.recordAuthentication(stored, stored.signCount)) {
                return AuthenticationResult(success = false, error = "Restore credential is no longer available")
            }

            AuthenticationResult(success = true, userId = stored.userId, credentialId = stored.credentialId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Restore authentication simplified verification failed", e)
            AuthenticationResult(success = false, error = "Authentication verification failed: ${e.message}")
        }

    private suspend fun verifyAuthenticationStrict(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
    ): AuthenticationResult =
        try {
            challengeStore.consume(challenge, CHALLENGE_TYPE_AUTHENTICATION, null)
                ?: return AuthenticationResult(success = false, error = "Invalid or expired challenge")

            val credentialIdBytes =
                decodeBase64Url(authenticationResponse.id)
                    ?: return AuthenticationResult(success = false, error = "Credential ID is not valid base64url")
            val canonicalCredentialId = encodeBase64Url(credentialIdBytes)

            val stored =
                restoreCredentialRepository.findByCredentialId(canonicalCredentialId)
                    ?: restoreCredentialRepository.findByCredentialId(authenticationResponse.id)
                    ?: return AuthenticationResult(success = false, error = "Restore credential not found")

            val attestedCredentialData = attestedCredentialDataConverter.convert(stored.publicKey)
            val authenticator = credentialRecord(attestedCredentialData, stored.signCount)

            val authenticatorDataBytes =
                decodeBase64Url(authenticationResponse.response.authenticatorData)
                    ?: return AuthenticationResult(success = false, error = "Authenticator data is not valid base64url")
            val clientDataJsonBytes =
                decodeBase64Url(authenticationResponse.response.clientDataJSON)
                    ?: return AuthenticationResult(success = false, error = "Client data is not valid base64url")
            val signatureBytes =
                decodeBase64Url(authenticationResponse.response.signature)
                    ?: return AuthenticationResult(success = false, error = "Signature is not valid base64url")
            val userHandleBytes = authenticationResponse.response.userHandle?.let { decodeBase64Url(it) }

            val authenticationRequest =
                if (userHandleBytes != null) {
                    AuthenticationRequest(credentialIdBytes, userHandleBytes, authenticatorDataBytes, clientDataJsonBytes, signatureBytes)
                } else {
                    AuthenticationRequest(credentialIdBytes, authenticatorDataBytes, clientDataJsonBytes, signatureBytes)
                }

            val challengeBytes =
                decodeBase64Url(challenge)
                    ?: return AuthenticationResult(success = false, error = "Challenge is not valid base64url")
            val serverProperty =
                ServerProperty
                    .builder()
                    .origins(webAuthnOrigins)
                    .rpId(relyingPartyId)
                    .challenge(DefaultChallenge(challengeBytes))
                    .build()

            val authenticationData =
                webAuthnManager.verify(
                    authenticationRequest,
                    // Restore assertions are passive. Origin, challenge, RP, signature, and
                    // credential ownership remain verified without a fresh UP/UV ceremony.
                    AuthenticationParameters(serverProperty, authenticator, listOf(credentialIdBytes), false, false),
                )

            val newSignCount = authenticationData.authenticatorData?.signCount ?: stored.signCount
            if (!restoreCredentialRepository.recordAuthentication(stored, newSignCount)) {
                return AuthenticationResult(success = false, error = "Restore credential is no longer available")
            }

            AuthenticationResult(success = true, userId = stored.userId, credentialId = canonicalCredentialId)
        } catch (e: DataConversionException) {
            AuthenticationResult(success = false, error = "Authentication data conversion failed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Restore authentication strict verification failed", e)
            AuthenticationResult(success = false, error = "Authentication verification failed: ${e.message}")
        }

    private fun decodeBase64Url(value: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()

    private fun encodeBase64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun normalizeCredentialId(value: String): String? = decodeBase64Url(value)?.let { encodeBase64Url(it) }
}
