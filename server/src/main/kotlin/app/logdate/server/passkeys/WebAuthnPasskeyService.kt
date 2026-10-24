package app.logdate.server.passkeys

import app.logdate.server.config.profileAwareBoolEnv
import app.logdate.shared.model.PasskeyAuthenticationOptions
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyChallenge
import app.logdate.shared.model.PasskeyInfo
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
import kotlinx.coroutines.runBlocking
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val AUTHENTICATION_TYPE = "authentication"
private const val REGISTRATION_TYPE = "registration"

/** Stored as its own challenge type; see the `challenge_type_valid` constraint. */
private const val STEP_UP_PURPOSE = "step_up"

/** Stored as its own challenge type; see the `challenge_type_valid` constraint. */
private const val STEP_UP_GRANT_PURPOSE = "step_up_grant"

/** Matches the `passkeys.nickname` column width. */
private const val MAX_NICKNAME_LENGTH = 100

/**
 * WebAuthn passkey service with optional strict cryptographic verification.
 *
 * Strict mode uses WebAuthn4J verification. Production profiles default to strict; development and
 * test profiles default to the lightweight fallback so fixture challenges don't require real
 * attestations. Set `WEBAUTHN_STRICT_VERIFICATION` explicitly to override the profile default.
 */
@OptIn(ExperimentalUuidApi::class)
class WebAuthnPasskeyService(
    private val passkeyRepository: PasskeyRepository = InMemoryPasskeyRepository(),
    val relyingPartyId: String = "logdate.app",
    private val relyingPartyName: String = "LogDate",
    private val origins: Set<String> = setOf("https://app.logdate.com"),
    /** Whether assertions and attestations are checked cryptographically with WebAuthn4J. */
    val strictVerificationEnabled: Boolean =
        profileAwareBoolEnv(
            name = "WEBAUTHN_STRICT_VERIFICATION",
            productionDefault = true,
            devDefault = false,
        ),
    private val challengeRepository: PasskeyChallengeRepository? = null,
) {
    private val challenges = ConcurrentHashMap<String, PasskeyChallenge>()
    private val challengeStore = PasskeyChallenges("passkey", challenges, challengeRepository)

    init {
        Napier.i(
            "WebAuthnPasskeyService strict=$strictVerificationEnabled rpId=$relyingPartyId origins=$origins",
        )
    }

    private val objectConverter = ObjectConverter()
    private val webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    private val attestedCredentialDataConverter = AttestedCredentialDataConverter(objectConverter)

    // All client origins accepted during verification: the https web origin plus any Android
    // apk-key-hash origins. webauthn4j matches the ceremony's clientDataJSON.origin against this set.
    private val webAuthnOrigins: Set<Origin> = origins.map { Origin(it) }.toSet()

    /** Returns `true` when a ceremony whose client data names [origin] can pass verification. */
    fun acceptsOrigin(origin: String): Boolean = origin in origins

    data class RegistrationResult(
        val success: Boolean,
        val credentialId: String? = null,
        val passkey: PasskeyInfo? = null,
        val error: String? = null,
    )

    /**
     * Output of a successful crypto-only registration verification. The caller (typically the
     * signup route handler) decides when to actually persist this; that lets the account row be
     * created first so the passkeys → accounts foreign key holds.
     */
    data class VerifiedRegistration(
        val credentialId: String,
        val publicKey: ByteArray,
        val signCount: Long,
        val passkey: PasskeyInfo,
    ) {
        /**
         * Labels the passkey with the device name the client registered it from, so a person
         * looking at their list can tell which credential is which. A blank name keeps the default.
         */
        fun withNickname(nickname: String?): VerifiedRegistration {
            val cleaned =
                nickname
                    ?.filterNot { it.isISOControl() }
                    ?.trim()
                    ?.take(MAX_NICKNAME_LENGTH)
                    ?.trim()
            if (cleaned.isNullOrEmpty()) return this
            return copy(passkey = passkey.copy(nickname = cleaned))
        }
    }

    data class AuthenticationResult(
        val success: Boolean,
        val userId: Uuid? = null,
        val credentialId: String? = null,
        val error: String? = null,
    )

    fun generateRegistrationOptions(
        userId: Uuid,
        username: String,
        displayName: String,
        excludeCredentials: List<String> = emptyList(),
    ): PasskeyRegistrationOptions {
        val challenge = challengeStore.issue(userId, REGISTRATION_TYPE)

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
            excludeCredentials = excludeCredentials.ifEmpty { getUserCredentials(userId) },
            timeout = 300_000L,
        )
    }

    fun generateAuthenticationOptions(
        userId: Uuid? = null,
        allowedCredentials: List<String> = emptyList(),
    ): PasskeyAuthenticationOptions {
        val challenge = challengeStore.issue(userId ?: Uuid.random(), AUTHENTICATION_TYPE)

        val allowCredentials =
            when {
                allowedCredentials.isNotEmpty() -> allowedCredentials
                userId != null -> getUserCredentials(userId)
                else -> emptyList()
            }

        return PasskeyAuthenticationOptions(
            challenge = challenge,
            rpId = relyingPartyId,
            allowCredentials = allowCredentials,
            timeout = 300_000L,
        )
    }

    /**
     * Two-step convenience wrapper that verifies the registration and immediately stores it.
     * Suitable for callers that already have an `accounts` row for [userId]. New signup flows
     * should prefer [verifyRegistrationOnly] + [storeVerifiedPasskey] so they can create the
     * account row before inserting the passkey (which carries an FK to `accounts`).
     */
    fun verifyRegistration(
        userId: Uuid,
        challenge: String,
        registrationResponse: PasskeyRegistrationResponse,
    ): RegistrationResult {
        val verified = verifyRegistrationOnly(userId, challenge, registrationResponse)
        return when (verified) {
            is VerificationOutcome.Failure -> {
                RegistrationResult(success = false, error = verified.error)
            }

            is VerificationOutcome.Success -> {
                val data = verified.data
                val storeResult = runCatching { storeVerifiedPasskey(userId, data) }
                when {
                    storeResult.isFailure -> {
                        val cause = storeResult.exceptionOrNull()
                        RegistrationResult(
                            success = false,
                            error = "Registration verification failed: ${cause?.message}",
                        )
                    }

                    storeResult.getOrDefault(false) -> {
                        RegistrationResult(success = true, credentialId = data.credentialId, passkey = data.passkey)
                    }

                    else -> {
                        RegistrationResult(success = false, error = "Failed to store passkey")
                    }
                }
            }
        }
    }

    /**
     * Verifies a registration response cryptographically without writing to the database. Returns
     * a [VerifiedRegistration] the caller can hand back to [storeVerifiedPasskey] once the
     * account row exists. Marking the challenge consumed is part of verification, so a failed
     * verify will not leave the challenge re-usable.
     */
    fun verifyRegistrationOnly(
        userId: Uuid,
        challenge: String,
        registrationResponse: PasskeyRegistrationResponse,
    ): VerificationOutcome =
        if (strictVerificationEnabled) {
            verifyRegistrationStrictOnly(userId, challenge, registrationResponse)
        } else {
            verifyRegistrationSimplifiedOnly(userId, challenge, registrationResponse)
        }

    /** Persists a previously-verified passkey. Returns true on success. */
    fun storeVerifiedPasskey(
        userId: Uuid,
        verified: VerifiedRegistration,
    ): Boolean =
        runBlocking {
            passkeyRepository.storePasskey(
                userId = userId,
                credentialId = verified.credentialId,
                publicKey = verified.publicKey,
                signCount = verified.signCount,
                info = verified.passkey,
            )
        }

    /** Result of [verifyRegistrationOnly] — either verified data or a structured error. */
    sealed interface VerificationOutcome {
        data class Success(
            val data: VerifiedRegistration,
        ) : VerificationOutcome

        data class Failure(
            val error: String,
        ) : VerificationOutcome
    }

    fun verifyAuthentication(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
    ): AuthenticationResult = verifyAuthentication(challenge, authenticationResponse, AUTHENTICATION_TYPE, expectedBinding = null)

    /**
     * Issues a challenge that only [verifyBoundAuthentication] with the same [purpose] and
     * [binding] accepts, so it cannot be spent on any other sign-in. It lists no credentials, so
     * the browser offers any discoverable passkey and nothing about an account is revealed.
     */
    fun generateBoundAuthenticationOptions(
        purpose: String,
        binding: Uuid,
    ): PasskeyAuthenticationOptions {
        require(purpose != AUTHENTICATION_TYPE && purpose != REGISTRATION_TYPE) { "A bound challenge needs its own purpose" }
        return PasskeyAuthenticationOptions(
            challenge = challengeStore.issue(binding, purpose),
            rpId = relyingPartyId,
            allowCredentials = emptyList(),
            timeout = 300_000L,
        )
    }

    /** Verifies an assertion over a challenge from [generateBoundAuthenticationOptions] with the same [purpose] and [binding]. */
    fun verifyBoundAuthentication(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
        purpose: String,
        binding: Uuid,
    ): AuthenticationResult = verifyAuthentication(challenge, authenticationResponse, purpose, binding)

    /**
     * Issues a step-up challenge: a fresh passkey confirmation that only [userId]'s passkeys can
     * answer, for an action that a signed-in session alone must not be able to take. It is bound to
     * the account and its own purpose, so it cannot be spent as a sign-in challenge or the reverse.
     */
    fun generateStepUpOptions(userId: Uuid): PasskeyAuthenticationOptions =
        generateBoundAuthenticationOptions(STEP_UP_PURPOSE, userId)
            .copy(allowCredentials = getUserCredentials(userId))

    /**
     * Issues a step-up grant for [userId] right after they confirmed with a passkey, such as at
     * sign-in: single use, valid for five minutes, and bound to the account. It stands in for a
     * step-up only where a route accepts one, so a passkey sign-in can register the account's first
     * recovery key without a second prompt.
     */
    fun issueStepUpGrant(userId: Uuid): String = challengeStore.issue(userId, STEP_UP_GRANT_PURPOSE)

    /** Spends [grant] if it was issued to [userId] by [issueStepUpGrant] and is unused and unexpired. */
    fun redeemStepUpGrant(
        userId: Uuid,
        grant: String,
    ): Boolean = challengeStore.consume(challenge = grant, expectedType = STEP_UP_GRANT_PURPOSE, expectedUserId = userId) != null

    /**
     * Verifies a step-up from [generateStepUpOptions]: the challenge must have been issued to
     * [userId], be unused and unexpired, and the assertion must come from one of [userId]'s passkeys.
     * The challenge is spent whether or not the assertion verifies.
     */
    fun verifyStepUp(
        userId: Uuid,
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
    ): AuthenticationResult {
        val result = verifyBoundAuthentication(challenge, authenticationResponse, STEP_UP_PURPOSE, userId)
        if (result.success && result.userId != userId) {
            return AuthenticationResult(success = false, error = "The passkey belongs to a different account")
        }
        return result
    }

    private fun verifyAuthentication(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
        expectedType: String,
        expectedBinding: Uuid?,
    ): AuthenticationResult =
        if (strictVerificationEnabled) {
            verifyAuthenticationStrict(challenge, authenticationResponse, expectedType, expectedBinding)
        } else {
            verifyAuthenticationSimplified(challenge, authenticationResponse, expectedType, expectedBinding)
        }

    fun getPasskeysForUser(userId: Uuid): List<PasskeyInfo> = runBlocking { passkeyRepository.getPasskeysForUser(userId) }

    fun deletePasskey(
        credentialId: String,
        userId: Uuid,
    ): Boolean = runBlocking { passkeyRepository.deactivatePasskey(credentialId, userId) }

    fun credentialBelongsToUser(
        credentialId: String,
        userId: Uuid,
    ): Boolean = runBlocking { passkeyRepository.credentialBelongsToUser(credentialId, userId) }

    fun getUserCredentials(userId: Uuid): List<String> = runBlocking { passkeyRepository.getCredentialIdsForUser(userId) }

    private fun verifyRegistrationSimplifiedOnly(
        userId: Uuid,
        challenge: String,
        registrationResponse: PasskeyRegistrationResponse,
    ): VerificationOutcome {
        return try {
            challengeStore.consume(
                challenge = challenge,
                expectedType = REGISTRATION_TYPE,
                expectedUserId = userId,
            ) ?: return VerificationOutcome.Failure("Invalid challenge")

            val credentialId = normalizeCredentialId(registrationResponse.id) ?: registrationResponse.id
            VerificationOutcome.Success(
                VerifiedRegistration(
                    credentialId = credentialId,
                    publicKey = registrationResponse.response.attestationObject.toByteArray(),
                    signCount = 0L,
                    passkey =
                        PasskeyInfo(
                            id = Uuid.random(),
                            credentialId = credentialId,
                            nickname = relyingPartyName,
                            deviceType = "platform",
                            createdAt = Clock.System.now(),
                            lastUsedAt = null,
                            isActive = true,
                        ),
                ),
            )
        } catch (e: Exception) {
            VerificationOutcome.Failure("Registration verification failed: ${e.message}")
        }
    }

    private fun verifyRegistrationStrictOnly(
        userId: Uuid,
        challenge: String,
        registrationResponse: PasskeyRegistrationResponse,
    ): VerificationOutcome {
        return try {
            challengeStore.consume(
                challenge = challenge,
                expectedType = REGISTRATION_TYPE,
                expectedUserId = userId,
            ) ?: return VerificationOutcome.Failure("Invalid challenge")

            val challengeBytes =
                decodeBase64Url(challenge)
                    ?: return VerificationOutcome.Failure("Challenge is not valid base64url")
            val attestationObjectBytes =
                decodeBase64Url(registrationResponse.response.attestationObject)
                    ?: return VerificationOutcome.Failure("Attestation object is not valid base64url")
            val clientDataJsonBytes =
                decodeBase64Url(registrationResponse.response.clientDataJSON)
                    ?: return VerificationOutcome.Failure("Client data is not valid base64url")

            val serverProperty =
                ServerProperty
                    .builder()
                    .origins(webAuthnOrigins)
                    .rpId(relyingPartyId)
                    .challenge(DefaultChallenge(challengeBytes))
                    .build()

            val registrationRequest = RegistrationRequest(attestationObjectBytes, clientDataJsonBytes)
            // null pubKeyCredParams keeps the previous "all COSE algorithms allowed" behaviour.
            val registrationParameters = RegistrationParameters(serverProperty, null, true, true)
            val registrationData = webAuthnManager.verify(registrationRequest, registrationParameters)
            val attestationObject =
                registrationData.attestationObject
                    ?: return VerificationOutcome.Failure("Attestation object is missing")
            val authenticatorData = attestationObject.authenticatorData

            val attestedCredentialData =
                authenticatorData.attestedCredentialData
                    ?: return VerificationOutcome.Failure("Attested credential data is missing")

            val canonicalCredentialId = encodeBase64Url(attestedCredentialData.credentialId)
            val normalizedCredentialId = normalizeCredentialId(registrationResponse.id)
            if (normalizedCredentialId != null && normalizedCredentialId != canonicalCredentialId) {
                return VerificationOutcome.Failure("Credential ID mismatch")
            }

            VerificationOutcome.Success(
                VerifiedRegistration(
                    credentialId = canonicalCredentialId,
                    publicKey = attestedCredentialDataConverter.convert(attestedCredentialData),
                    signCount = authenticatorData.signCount,
                    passkey =
                        PasskeyInfo(
                            id = Uuid.random(),
                            credentialId = canonicalCredentialId,
                            nickname = relyingPartyName,
                            deviceType = "platform",
                            createdAt = Clock.System.now(),
                            lastUsedAt = null,
                            isActive = true,
                        ),
                ),
            )
        } catch (e: DataConversionException) {
            VerificationOutcome.Failure("Registration data conversion failed")
        } catch (e: Exception) {
            // The origin failure webauthn4j reports names the origin the client presented but not
            // the set it was checked against, which is the half an operator actually needs.
            Napier.w("Registration verification failed against origins=$webAuthnOrigins rpId=$relyingPartyId", e)
            VerificationOutcome.Failure("Registration verification failed: ${e.message}")
        }
    }

    private fun verifyAuthenticationSimplified(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
        expectedType: String,
        expectedBinding: Uuid?,
    ): AuthenticationResult {
        return try {
            challengeStore.consume(
                challenge = challenge,
                expectedType = expectedType,
                expectedUserId = expectedBinding,
            ) ?: return AuthenticationResult(success = false, error = "Invalid challenge")

            val (userId, storedData) =
                findStoredPasskey(authenticationResponse.id)
                    ?: return AuthenticationResult(success = false, error = "Credential not found")

            runBlocking {
                passkeyRepository.updateSignCount(storedData.credentialId, storedData.signCount + 1L)
            }

            AuthenticationResult(success = true, userId = userId, credentialId = storedData.credentialId)
        } catch (e: Exception) {
            AuthenticationResult(success = false, error = "Authentication verification failed: ${e.message}")
        }
    }

    private fun verifyAuthenticationStrict(
        challenge: String,
        authenticationResponse: PasskeyAuthenticationResponse,
        expectedType: String,
        expectedBinding: Uuid?,
    ): AuthenticationResult {
        return try {
            challengeStore.consume(
                challenge = challenge,
                expectedType = expectedType,
                expectedUserId = expectedBinding,
            ) ?: return AuthenticationResult(success = false, error = "Invalid challenge")

            val credentialIdBytes =
                decodeBase64Url(authenticationResponse.id)
                    ?: return AuthenticationResult(success = false, error = "Credential ID is not valid base64url")
            val canonicalCredentialId = encodeBase64Url(credentialIdBytes)
            val (userId, storedData) =
                findStoredPasskey(canonicalCredentialId)
                    ?: return AuthenticationResult(success = false, error = "Credential not found")

            val attestedCredentialData = attestedCredentialDataConverter.convert(storedData.publicKey)
            val authenticator = credentialRecord(attestedCredentialData, storedData.signCount)

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
                    AuthenticationRequest(
                        credentialIdBytes,
                        userHandleBytes,
                        authenticatorDataBytes,
                        clientDataJsonBytes,
                        signatureBytes,
                    )
                } else {
                    AuthenticationRequest(
                        credentialIdBytes,
                        authenticatorDataBytes,
                        clientDataJsonBytes,
                        signatureBytes,
                    )
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

            val authenticationParameters =
                AuthenticationParameters(
                    serverProperty,
                    authenticator,
                    listOf(credentialIdBytes),
                    true,
                    true,
                )

            val authenticationData = webAuthnManager.verify(authenticationRequest, authenticationParameters)
            val newSignCount = authenticationData.authenticatorData?.signCount ?: storedData.signCount
            runBlocking {
                passkeyRepository.updateSignCount(canonicalCredentialId, newSignCount)
            }

            AuthenticationResult(success = true, userId = userId, credentialId = canonicalCredentialId)
        } catch (e: DataConversionException) {
            AuthenticationResult(success = false, error = "Authentication data conversion failed")
        } catch (e: Exception) {
            AuthenticationResult(success = false, error = "Authentication verification failed: ${e.message}")
        }
    }

    private fun findStoredPasskey(credentialId: String): Pair<Uuid, StoredPasskeyData>? {
        val normalized = normalizeCredentialId(credentialId)
        return runBlocking {
            passkeyRepository.getPasskeyByCredentialId(credentialId)
                ?: (normalized?.takeIf { it != credentialId }?.let { passkeyRepository.getPasskeyByCredentialId(it) })
        }
    }

    private fun decodeBase64Url(value: String): ByteArray? = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()

    private fun encodeBase64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun normalizeCredentialId(value: String): String? = decodeBase64Url(value)?.let { encodeBase64Url(it) }
}
