package app.logdate.client.data.account

import app.logdate.client.device.auth.BlueskyAppProof
import app.logdate.client.device.auth.BlueskyAppReturn
import app.logdate.client.device.auth.BlueskyAttemptStore
import app.logdate.client.device.identity.sameOwnerId
import app.logdate.client.networking.bluesky.BlueskyApi
import app.logdate.client.networking.bluesky.BlueskyApiException
import app.logdate.client.networking.bluesky.BlueskyApiFailure
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInFailure
import app.logdate.client.repository.account.BlueskySignInRepository
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.client.repository.account.LocalDataAdoptionRequiredException
import app.logdate.shared.model.BlueskyAccountNextStep
import app.logdate.shared.model.BlueskyExchangeRequest
import app.logdate.shared.model.BlueskyStartRequest
import app.logdate.shared.model.LogDateAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes browser returns and explicit passkey actions without persisting session guards. */
internal class BlueskySignInController(
    private val origin: () -> String,
    private val owner: suspend () -> String,
    private val enabled: suspend () -> Boolean,
    private val attempts: BlueskyAttemptStore,
    private val enrollments: BlueskyEnrollmentStore,
    private val api: (String) -> BlueskyApi,
    private val register: suspend (AccountCreationRequest, PasskeyCeremonyApi) -> Result<LogDateAccount>,
    private val authenticate: suspend (Boolean, PasskeyCeremonyApi) -> Result<LogDateAccount>,
    private val prepareAvailability: suspend () -> Boolean = enabled,
) : BlueskySignInRepository {
    private val mutex = Mutex()
    private val activeOperation = MutableStateFlow<Job?>(null)
    private val mutableState = MutableStateFlow<BlueskySignInState>(BlueskySignInState.Idle)
    override val state: StateFlow<BlueskySignInState> = mutableState.asStateFlow()

    override suspend fun isAvailable(): Boolean = enabled()

    override suspend fun prepare(): Boolean = prepareAvailability() && enabled()

    override suspend fun start(handle: String): Result<String> =
        mutex.withLock {
            action {
                val backend = origin()
                val originalOwner = owner()
                checkContext(backend, originalOwner)
                val proof = BlueskyAppProof.create()
                val response = withApi(backend) { it.start(BlueskyStartRequest(handle, proof.challenge)).getOrThrow() }
                checkContext(backend, originalOwner)
                attempts.remember(response.attemptId, proof.verifier, backend, originalOwner)
                enrollments.read()?.let { enrollments.clear(it.attemptId) }
                checkContext(backend, originalOwner)
                mutableState.value = BlueskySignInState.AwaitingBrowser
                response.authorizationUrl
            }
        }

    override suspend fun resume(returnUrl: String): Boolean =
        mutex.withLock {
            val attemptId = BlueskyAppReturn.attemptId(returnUrl) ?: return@withLock false
            action {
                enrollments.read()?.takeIf { it.attemptId == attemptId }?.let {
                    show(it)
                    return@action
                }
                val pending = attempts.forReturn(returnUrl) ?: expired()
                checkContext(pending.backendOrigin, pending.ownerId)
                val next =
                    withApi(pending.backendOrigin) {
                        try {
                            it.exchange(BlueskyExchangeRequest(pending.attemptId, pending.verifier)).getOrThrow()
                        } catch (failure: BlueskyApiException) {
                            if (failure.kind == BlueskyApiFailure.CANCELED) attempts.forget(pending.attemptId)
                            throw failure
                        }
                    }
                checkContext(pending.backendOrigin, pending.ownerId)
                val current = attempts.forReturn(returnUrl) ?: expired()
                if (current.verifier != pending.verifier ||
                    current.ownerId != pending.ownerId ||
                    current.backendOrigin != pending.backendOrigin
                ) {
                    expired()
                }
                val enrollment = enrollments.begin(pending.attemptId, pending.backendOrigin, next, pending.ownerId)
                // A failed removal leaves the durable enrollment available for the next return.
                attempts.forget(pending.attemptId)
                show(enrollment)
            }
            true
        }

    override suspend fun restore() =
        mutex.withLock {
            action {
                val enrollment = enrollments.read()
                if (enrollment != null) {
                    show(enrollment)
                } else {
                    val pending = attempts.current()
                    if (pending == null) {
                        mutableState.value = BlueskySignInState.Idle
                    } else {
                        checkContext(pending.backendOrigin, pending.ownerId)
                        mutableState.value = BlueskySignInState.AwaitingBrowser
                    }
                }
            }
            Unit
        }

    override suspend fun createAccount(request: AccountCreationRequest) =
        finish { enrollment, ceremony ->
            when (val step = enrollment.step) {
                is BlueskyEnrollmentStep.Identity -> if (step.nextStep != BlueskyAccountNextStep.CREATE_ACCOUNT) expired()
                is BlueskyEnrollmentStep.Registration -> Unit
                else -> expired()
            }
            register(request, ceremony).getOrThrow()
        }

    override suspend fun confirmPasskey(adoptLocalData: Boolean) =
        finish { enrollment, ceremony ->
            when (val step = enrollment.step) {
                is BlueskyEnrollmentStep.Identity -> if (step.nextStep != BlueskyAccountNextStep.CONFIRM_PASSKEY) expired()
                is BlueskyEnrollmentStep.Authentication -> Unit
                else -> expired()
            }
            authenticate(adoptLocalData, ceremony).getOrThrow()
        }

    override suspend fun cancel() {
        activeOperation.value?.cancel()
        mutex.withLock {
            action {
                enrollments.read()?.let { enrollments.clear(it.attemptId) }
                attempts.current()?.let { attempts.forget(it.attemptId) }
                mutableState.value = BlueskySignInState.Idle
            }
        }
    }

    private suspend fun finish(perform: suspend (BlueskyEnrollment, PasskeyCeremonyApi) -> LogDateAccount) =
        mutex.withLock {
            action {
                val enrollment = enrollments.read() ?: expired()
                checkContext(enrollment.backendOrigin, enrollment.ownerId)
                requireUnspent(enrollment)
                val account =
                    withApi(enrollment.backendOrigin) { client ->
                        completeWithPasskey(enrollment, client, perform)
                    }
                // Adoption can legitimately change the offline owner. The guarded account workflow
                // must finish with the returned account now owning this installation.
                checkContext(enrollment.backendOrigin, account.id.toString())
                enrollments.clear(enrollment.attemptId)
                attempts.forget(enrollment.attemptId)
                checkContext(enrollment.backendOrigin, account.id.toString())
                mutableState.value = BlueskySignInState.Complete(account, enrollment.backendOrigin)
            }
            Unit
        }

    private suspend fun completeWithPasskey(
        enrollment: BlueskyEnrollment,
        client: BlueskyApi,
        perform: suspend (BlueskyEnrollment, PasskeyCeremonyApi) -> LogDateAccount,
    ): LogDateAccount {
        val ceremony = BlueskyPasskeyCeremony(enrollment, enrollments, client)
        try {
            return perform(enrollment, ceremony)
        } catch (failure: Exception) {
            if (!ceremony.serverCompletionReceived ||
                failure !is LocalDataAdoptionRequiredException &&
                failure !is AccountKeysNotReadyException
            ) {
                throw failure
            }
            enrollments.clear(enrollment.attemptId)
            attempts.forget(enrollment.attemptId)
            val next =
                if (failure is LocalDataAdoptionRequiredException) {
                    BlueskySignInState.LocalDataConsent(requiresNewSignIn = true)
                } else {
                    BlueskySignInState.DeviceApproval(requiresNewSignIn = true)
                }
            throw FlowFailure(BlueskySignInFailure.UNAVAILABLE, next)
        }
    }

    private suspend fun show(enrollment: BlueskyEnrollment) {
        checkContext(enrollment.backendOrigin, enrollment.ownerId)
        requireUnspent(enrollment)
        mutableState.value =
            when (val step = enrollment.step) {
                is BlueskyEnrollmentStep.Registration ->
                    BlueskySignInState.AccountDetails(
                        AccountCreationRequest(step.profile.username, step.profile.displayName, step.profile.bio, step.profile.originalBio),
                    )
                is BlueskyEnrollmentStep.Authentication -> BlueskySignInState.ConfirmPasskey
                is BlueskyEnrollmentStep.Identity ->
                    when (step.nextStep) {
                        BlueskyAccountNextStep.CREATE_ACCOUNT -> BlueskySignInState.AccountDetails()
                        BlueskyAccountNextStep.CONFIRM_PASSKEY -> BlueskySignInState.ConfirmPasskey
                    }
            }
    }

    private suspend fun requireUnspent(enrollment: BlueskyEnrollment) {
        if (enrollment.completionStarted) {
            enrollments.clear(enrollment.attemptId)
            expired()
        }
    }

    private suspend fun checkContext(
        backend: String,
        originalOwner: String,
    ) {
        currentCoroutineContext().ensureActive()
        if (!enabled()) throw FlowFailure(BlueskySignInFailure.UNAVAILABLE)
        if (origin() != backend || !sameOwnerId(owner(), originalOwner)) throw FlowFailure(BlueskySignInFailure.CONTEXT_CHANGED)
    }

    private suspend fun <T> withApi(
        backend: String,
        block: suspend (BlueskyApi) -> T,
    ): T {
        val client = api(backend)
        try {
            if (client.backendOrigin != backend) throw FlowFailure(BlueskySignInFailure.CONTEXT_CHANGED)
            return block(client)
        } finally {
            client.close()
        }
    }

    private suspend fun <T> action(block: suspend () -> T): Result<T> =
        coroutineScope {
            val operation = currentCoroutineContext().job
            activeOperation.value = operation
            mutableState.value = BlueskySignInState.Working
            try {
                Result.success(block())
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                val problem =
                    when (failure) {
                        is FlowFailure -> failure.reason
                        is BlueskyApiException ->
                            when (failure.kind) {
                                BlueskyApiFailure.CANCELED -> BlueskySignInFailure.CANCELED
                                BlueskyApiFailure.EXPIRED -> BlueskySignInFailure.EXPIRED
                                BlueskyApiFailure.INVALID_REQUEST -> BlueskySignInFailure.INVALID_REQUEST
                                else -> BlueskySignInFailure.UNAVAILABLE
                            }
                        else -> BlueskySignInFailure.UNAVAILABLE
                    }
                mutableState.value =
                    when (failure) {
                        is FlowFailure -> failure.nextState ?: BlueskySignInState.Failed(problem)
                        is LocalDataAdoptionRequiredException -> BlueskySignInState.LocalDataConsent()
                        is AccountKeysNotReadyException -> BlueskySignInState.DeviceApproval()
                        else -> BlueskySignInState.Failed(problem)
                    }
                Result.failure(FlowFailure(problem))
            } finally {
                activeOperation.compareAndSet(operation, null)
            }
        }

    private fun expired(): Nothing = throw FlowFailure(BlueskySignInFailure.EXPIRED)

    private class FlowFailure(
        val reason: BlueskySignInFailure,
        val nextState: BlueskySignInState? = null,
    ) : IllegalStateException("Bluesky sign-in could not continue")
}
