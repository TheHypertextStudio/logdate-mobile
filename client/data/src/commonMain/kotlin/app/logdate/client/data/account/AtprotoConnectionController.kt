package app.logdate.client.data.account

import app.logdate.client.device.auth.AtprotoConnectionBinding
import app.logdate.client.device.auth.AtprotoConnectionPendingAction
import app.logdate.client.device.auth.AtprotoConnectionPendingStore
import app.logdate.client.device.auth.BlueskyAppProof
import app.logdate.client.device.auth.BlueskyAppReturn
import app.logdate.client.device.auth.PendingAtprotoConnection
import app.logdate.client.networking.atproto.AtprotoConnectionApi
import app.logdate.client.networking.atproto.AtprotoConnectionApiException
import app.logdate.client.networking.atproto.AtprotoConnectionApiFailure
import app.logdate.client.repository.account.AccountConnectionProblem
import app.logdate.client.repository.account.AccountConnectionState
import app.logdate.client.repository.account.AtprotoConnectionRepository
import app.logdate.shared.model.AtprotoConnectionSnapshot
import app.logdate.shared.model.AtprotoConnectionState
import app.logdate.shared.model.AtprotoDisconnectRequest
import app.logdate.shared.model.PasskeyAssertionResponse
import app.logdate.shared.model.PasskeyAuthenticationOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/** Receives guarded credentials from its owning account host; never installs sessions or account keys. */
internal class AtprotoConnectionController(
    private val binding: suspend () -> AtprotoConnectionBinding?,
    private val enabled: suspend () -> Boolean,
    private val accessToken: suspend (binding: AtprotoConnectionBinding, rejectedToken: String?) -> String,
    private val api: (String) -> AtprotoConnectionApi,
    private val passkey: suspend (PasskeyAuthenticationOptions) -> PasskeyAssertionResponse,
    private val pending: AtprotoConnectionPendingStore,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AtprotoConnectionRepository {
    private val mutableState = MutableStateFlow(AccountConnectionState())
    override val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val active = MutableStateFlow<Job?>(null)
    private var bound: AtprotoConnectionBinding? = null
    private var confirmation: Pair<AtprotoConnectionBinding, Long?>? = null

    override suspend fun refresh() {
        operate { owner ->
            showPending(pending.current(owner))
            apply(call(owner) { client, token -> client.snapshot(token).getOrThrow() })
        }
    }

    override suspend fun requestDisconnect() =
        mutex.withLock {
            val owner = bound ?: return@withLock
            val shown = state.value
            if (!shown.available || shown.busy || binding() != owner || !enabled()) return@withLock
            if (!shown.hasPendingDisconnect &&
                !(shown.snapshot?.eligible == true && shown.snapshot?.canDisconnect == true && !shown.observationFailed)
            ) {
                return@withLock
            }
            confirmation = owner to shown.snapshot?.revision
            mutableState.value = shown.copy(confirmingDisconnect = true)
        }

    override suspend fun cancelDisconnectConfirmation() =
        mutex.withLock {
            confirmation = null
            mutableState.value = state.value.copy(confirmingDisconnect = false)
        }

    override suspend fun confirmDisconnect() {
        operate { owner ->
            val confirmed = confirmation ?: return@operate
            confirmation = null
            mutableState.value = state.value.copy(confirmingDisconnect = false)
            if (confirmed.first != owner) throw CancellationException("Account changed")
            val existing = pending.current(owner)
            val saved =
                if (existing != null) {
                    check(existing.action is AtprotoConnectionPendingAction.Disconnect)
                    existing
                } else {
                    val shown = state.value.snapshot
                    if (shown?.eligible != true || !shown.canDisconnect) throw Problem(AccountConnectionProblem.INELIGIBLE)
                    if (shown.revision != confirmed.second) throw Problem(AccountConnectionProblem.CHANGED)
                    val challenge = call(owner) { client, token -> client.beginDisconnect(token, shown.revision).getOrThrow() }
                    val remaining = Instant.parse(challenge.expiresAt).toEpochMilliseconds() - nowMillis()
                    if (challenge.expectedRevision != shown.revision ||
                        !IDENTIFIER.matches(challenge.decisionId) ||
                        !IDENTIFIER.matches(challenge.authenticationOptions.challenge) ||
                        challenge.authenticationOptions.rpId != "logdate.app" ||
                        challenge.authenticationOptions.allowCredentials.isEmpty() ||
                        remaining !in 1L..300000L
                    ) {
                        throw Problem(AccountConnectionProblem.PROOF_EXPIRED)
                    }
                    val credential = passkey(challenge.authenticationOptions)
                    checkCurrent(owner)
                    val proof =
                        Json.encodeToString(
                            AtprotoDisconnectRequest(challenge.decisionId, challenge.authenticationOptions.challenge, credential),
                        )
                    pending.remember(owner, AtprotoConnectionPendingAction.Disconnect(challenge.decisionId, proof))
                }
            showPending(saved)
            checkCurrent(owner)
            val proof = saved.action as AtprotoConnectionPendingAction.Disconnect
            val response = completePending(owner, saved) { client, token -> client.disconnect(token, proof.encodedProof).getOrThrow() }
            apply(response.current)
            check(pending.forget(saved))
            showPending(null)
            val newer =
                response.current.state == AtprotoConnectionState.CONNECTED &&
                    response.current.revision > response.receipt.disconnectedRevision
            mutableState.value =
                state.value.copy(
                    newerConnectionPreserved = newer,
                    remoteRevocationUnconfirmed = !newer && !response.receipt.remoteConfirmed,
                )
        }
    }

    override suspend fun reconnect(): Result<String> =
        operate { owner ->
            if (state.value.snapshot?.eligible != true) throw Problem(AccountConnectionProblem.INELIGIBLE)
            check(pending.current(owner) == null)
            val proof = BlueskyAppProof.create()
            val response = call(owner) { client, token -> client.startReconnect(token, proof.challenge).getOrThrow() }
            val saved = pending.remember(owner, AtprotoConnectionPendingAction.Reconnect(response.attemptId, proof.verifier))
            showPending(saved)
            checkCurrent(owner)
            response.authorizationUrl
        }

    override suspend fun resume(returnUrl: String): Boolean {
        val attempt = BlueskyAppReturn.attemptId(returnUrl) ?: return false
        val owner = binding() ?: return false
        val saved = pending.current(owner) ?: return false
        val proof = saved.action as? AtprotoConnectionPendingAction.Reconnect ?: return false
        if (proof.attemptId != attempt) return false
        operate { current ->
            if (current != owner || pending.current(owner)?.id != saved.id) throw CancellationException("Account changed")
            showPending(saved)
            val value =
                completePending(
                    owner,
                    saved,
                ) { client, token -> client.completeReconnect(token, proof.attemptId, proof.verifier).getOrThrow() }
            apply(value)
            check(pending.forget(saved))
            showPending(null)
        }
        return true
    }

    override suspend fun cancelPending() {
        active.value?.cancel()
        mutex.withLock {
            val owner = bound ?: binding()
            if (owner != null) pending.current(owner)?.let { pending.forget(it) }
            confirmation = null
            showPending(null)
            mutableState.value = state.value.copy(confirmingDisconnect = false)
        }
    }

    override suspend fun invalidate() {
        active.value?.cancel()
        mutex.withLock { clearVisible() }
    }

    private fun clearVisible() {
        bound = null
        confirmation = null
        mutableState.value = AccountConnectionState()
    }

    private fun showPending(saved: PendingAtprotoConnection?) {
        mutableState.value =
            state.value.copy(
                waitingForBrowser = saved?.action is AtprotoConnectionPendingAction.Reconnect,
                hasPendingDisconnect = saved?.action is AtprotoConnectionPendingAction.Disconnect,
            )
    }

    private fun apply(value: AtprotoConnectionSnapshot) {
        if (value.revision < (state.value.snapshot?.revision ?: -1)) return
        mutableState.value = state.value.copy(snapshot = value, observationFailed = false)
    }

    private suspend fun checkCurrent(owner: AtprotoConnectionBinding) {
        currentCoroutineContext().ensureActive()
        if (binding() != owner || !enabled()) throw CancellationException("Account changed")
    }

    private suspend fun <T> completePending(
        owner: AtprotoConnectionBinding,
        saved: PendingAtprotoConnection,
        request: suspend (AtprotoConnectionApi, String) -> T,
    ): T {
        try {
            return call(owner, request)
        } catch (failure: AtprotoConnectionApiException) {
            if (failure.kind in TERMINAL_PROOF_REFUSALS) {
                check(pending.forget(saved))
                showPending(null)
            }
            throw failure
        }
    }

    private suspend fun <T> call(
        owner: AtprotoConnectionBinding,
        request: suspend (AtprotoConnectionApi, String) -> T,
    ): T {
        val client = api(owner.backendOrigin)
        try {
            check(client.backendOrigin == owner.backendOrigin)
            val token = accessToken(owner, null)
            checkCurrent(owner)
            return try {
                request(client, token).also { checkCurrent(owner) }
            } catch (failure: AtprotoConnectionApiException) {
                if (failure.kind != AtprotoConnectionApiFailure.UNAUTHORIZED) throw failure
                val renewed = accessToken(owner, token)
                checkCurrent(owner)
                request(client, renewed).also { checkCurrent(owner) }
            }
        } finally {
            client.close()
        }
    }

    private suspend fun <T> operate(work: suspend (AtprotoConnectionBinding) -> T): Result<T> =
        mutex.withLock {
            val owner = binding()
            if (owner == null || !enabled()) {
                clearVisible()
                return@withLock Result.failure(Problem(AccountConnectionProblem.UNAVAILABLE))
            }
            if (bound != owner) clearVisible()
            bound = owner
            val job = currentCoroutineContext().job
            active.value = job
            mutableState.value =
                state.value.copy(
                    available = true,
                    busy = true,
                    problem = null,
                    remoteRevocationUnconfirmed = false,
                    newerConnectionPreserved = false,
                )
            try {
                checkCurrent(owner)
                Result.success(work(owner))
            } catch (canceled: CancellationException) {
                if (runCatching { binding() }.getOrNull() != owner) clearVisible()
                throw canceled
            } catch (error: Exception) {
                if (runCatching { binding() }.getOrNull() != owner) {
                    clearVisible()
                } else {
                    val problem = problem(error)
                    mutableState.value =
                        state.value.copy(
                            problem = problem,
                            observationFailed = state.value.snapshot != null && problem == AccountConnectionProblem.UNAVAILABLE,
                        )
                }
                Result.failure(Problem(problem(error)))
            } finally {
                active.compareAndSet(job, null)
                mutableState.value = state.value.copy(busy = false)
            }
        }

    private fun problem(error: Exception): AccountConnectionProblem =
        when (error) {
            is Problem -> error.kind
            is AtprotoConnectionApiException ->
                when (error.kind) {
                    AtprotoConnectionApiFailure.CHANGED -> AccountConnectionProblem.CHANGED
                    AtprotoConnectionApiFailure.PROOF_INVALID -> AccountConnectionProblem.PROOF_INVALID
                    AtprotoConnectionApiFailure.PROOF_EXPIRED -> AccountConnectionProblem.PROOF_EXPIRED
                    AtprotoConnectionApiFailure.LAST_SIGN_IN_FACTOR -> AccountConnectionProblem.LAST_SIGN_IN_FACTOR
                    AtprotoConnectionApiFailure.INELIGIBLE -> AccountConnectionProblem.INELIGIBLE
                    AtprotoConnectionApiFailure.RATE_LIMITED -> AccountConnectionProblem.RATE_LIMITED
                    else -> AccountConnectionProblem.UNAVAILABLE
                }
            else -> AccountConnectionProblem.UNAVAILABLE
        }

    private class Problem(
        val kind: AccountConnectionProblem,
    ) : IllegalStateException("Account connection unavailable")

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z0-9_-]{43}")
        val TERMINAL_PROOF_REFUSALS =
            setOf(
                AtprotoConnectionApiFailure.PROOF_EXPIRED,
                AtprotoConnectionApiFailure.PROOF_INVALID,
                AtprotoConnectionApiFailure.CHANGED,
                AtprotoConnectionApiFailure.INELIGIBLE,
                AtprotoConnectionApiFailure.LAST_SIGN_IN_FACTOR,
            )
    }
}
