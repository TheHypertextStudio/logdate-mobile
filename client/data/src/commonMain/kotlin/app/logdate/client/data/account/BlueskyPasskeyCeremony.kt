package app.logdate.client.data.account

import app.logdate.client.networking.bluesky.BlueskyApi
import app.logdate.client.networking.bluesky.BlueskyApiException
import app.logdate.client.networking.bluesky.BlueskyApiFailure
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.AuthenticatorAssertionResponse
import app.logdate.shared.model.AuthenticatorAttestationResponse
import app.logdate.shared.model.BeginAccountCreationData
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAuthenticationData
import app.logdate.shared.model.BeginAuthenticationRequest
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyAuthenticationBeginRequest
import app.logdate.shared.model.BlueskyAuthenticationCompleteRequest
import app.logdate.shared.model.BlueskyRegistrationBeginRequest
import app.logdate.shared.model.BlueskyRegistrationCompleteRequest
import app.logdate.shared.model.CompleteAccountCreationData
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAuthenticationData
import app.logdate.shared.model.CompleteAuthenticationRequest
import app.logdate.shared.model.PasskeyAllowCredential
import app.logdate.shared.model.PasskeyAuthenticationResponse
import app.logdate.shared.model.PasskeyRegistrationResponse
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** Adapts Bluesky's one-use grants to the existing guarded passkey/account workflow. */
internal class BlueskyPasskeyCeremony(
    initial: BlueskyEnrollment,
    private val store: BlueskyEnrollmentStore,
    private val api: BlueskyApi,
) : PasskeyCeremonyApi {
    private var state = initial
    var serverCompletionReceived: Boolean = false
        private set
    override val backendOrigin: String = initial.backendOrigin
    override val originalOwnerId: String = initial.ownerId

    init {
        if (api.backendOrigin != backendOrigin) expired()
    }

    override suspend fun beginRegistration(request: BeginAccountCreationRequest): Result<BeginAccountCreationData> =
        result {
            val current = checkedCurrent()
            val registration =
                when (val step = current.step) {
                    is BlueskyEnrollmentStep.Identity -> {
                        if (step.nextStep != BlueskyAccountNextStep.CREATE_ACCOUNT) expired()
                        val owner = request.requestedOwnerId ?: invalidRequest()
                        val response =
                            api
                                .beginRegistration(
                                    BlueskyRegistrationBeginRequest(
                                        step.token,
                                        Uuid.parse(owner).toString(),
                                        request.username,
                                        request.displayName,
                                        request.bio,
                                        request.originalBio,
                                    ),
                                ).getOrThrow()
                        val next =
                            BlueskyEnrollmentStep.Registration(
                                response.continuationToken,
                                BlueskyEnrollmentProfile(owner, request.username, request.displayName, request.bio, request.originalBio),
                                response.options,
                            )
                        state = store.advance(current, next) ?: expired()
                        next
                    }
                    is BlueskyEnrollmentStep.Registration -> {
                        val profile = step.profile
                        if (profile.ownerId != request.requestedOwnerId ||
                            profile.username != request.username ||
                            profile.displayName != request.displayName ||
                            profile.bio != request.bio ||
                            profile.originalBio != request.originalBio
                        ) {
                            invalidRequest()
                        }
                        step
                    }
                    else -> expired()
                }
            BeginAccountCreationData(registration.token, registration.options)
        }

    override suspend fun completeRegistration(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationData> =
        result {
            val current = checkedCurrent()
            val step = current.step as? BlueskyEnrollmentStep.Registration ?: expired()
            if (request.sessionToken != step.token) expired()
            val credential = request.credential
            val claimed = store.claimCompletion(current) ?: expired()
            state = claimed
            val response =
                api
                    .completeRegistration(
                        BlueskyRegistrationCompleteRequest(
                            step.token,
                            PasskeyRegistrationResponse(
                                credential.id,
                                credential.rawId,
                                AuthenticatorAttestationResponse(
                                    credential.response.clientDataJSON,
                                    credential.response.attestationObject,
                                ),
                                credential.type,
                            ),
                            request.nickname,
                        ),
                    ).getOrThrow()
            serverCompletionReceived = true
            validateSession(response)
            if (!store.isCurrent(claimed)) expired()
            CompleteAccountCreationData(response.account, response.tokens)
        }

    override suspend fun beginAuthentication(request: BeginAuthenticationRequest): Result<BeginAuthenticationData> =
        result {
            val current = checkedCurrent()
            val authentication =
                when (val step = current.step) {
                    is BlueskyEnrollmentStep.Identity -> {
                        if (step.nextStep != BlueskyAccountNextStep.CONFIRM_PASSKEY) expired()
                        val response = api.beginAuthentication(BlueskyAuthenticationBeginRequest(step.token)).getOrThrow()
                        val next = BlueskyEnrollmentStep.Authentication(response.continuationToken, response.options)
                        state = store.advance(current, next) ?: expired()
                        next
                    }
                    is BlueskyEnrollmentStep.Authentication -> step
                    else -> expired()
                }
            val options = authentication.options
            BeginAuthenticationData(
                options.challenge,
                options.rpId,
                options.allowCredentials.map {
                    PasskeyAllowCredential(id = it)
                },
                options.timeout,
                "required",
            )
        }

    override suspend fun completeAuthentication(request: CompleteAuthenticationRequest): Result<CompleteAuthenticationData> =
        result {
            val current = checkedCurrent()
            val step = current.step as? BlueskyEnrollmentStep.Authentication ?: expired()
            if (request.challenge != step.options.challenge) expired()
            val credential = request.credential
            val claimed = store.claimCompletion(current) ?: expired()
            state = claimed
            val response =
                api
                    .completeAuthentication(
                        BlueskyAuthenticationCompleteRequest(
                            step.token,
                            PasskeyAuthenticationResponse(
                                credential.id,
                                credential.rawId,
                                AuthenticatorAssertionResponse(
                                    credential.response.clientDataJSON,
                                    credential.response.authenticatorData,
                                    credential.response.signature,
                                    credential.response.userHandle.takeIf { it.isNotBlank() },
                                ),
                                credential.type,
                            ),
                        ),
                    ).getOrThrow()
            serverCompletionReceived = true
            validateSession(response)
            if (!store.isCurrent(claimed)) expired()
            response.copy(stepUpGrant = null)
        }

    private suspend fun checkedCurrent(): BlueskyEnrollment {
        val expected = state
        if (expected.completionStarted || api.backendOrigin != backendOrigin || !store.isCurrent(expected)) expired()
        return expected
    }

    private fun validateSession(response: CompleteAuthenticationData) {
        if (response.account.identityKind != AccountIdentityKind.EXTERNAL ||
            response.account.storageMode != AccountStorageMode.PERSONAL_SPACE_V1 ||
            response.account.did.isNullOrBlank()
        ) {
            throw BlueskyApiException(BlueskyApiFailure.INVALID_RESPONSE)
        }
    }

    private suspend fun <T> result(action: suspend () -> T): Result<T> =
        try {
            Result.success(action())
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (failure: BlueskyApiException) {
            Result.failure(failure)
        } catch (_: Exception) {
            Result.failure(BlueskyApiException(BlueskyApiFailure.UNAVAILABLE))
        }

    private fun expired(): Nothing = throw BlueskyApiException(BlueskyApiFailure.EXPIRED)

    private fun invalidRequest(): Nothing = throw BlueskyApiException(BlueskyApiFailure.INVALID_REQUEST)
}
