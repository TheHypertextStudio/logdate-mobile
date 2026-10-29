package app.logdate.feature.core.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.account.BlueskySignInFailure
import app.logdate.client.repository.account.BlueskySignInRepository
import app.logdate.client.repository.account.BlueskySignInState
import app.logdate.shared.config.LogDateConfigRepository
import app.logdate.shared.model.BlueskyBrowserReturn
import app.logdate.shared.model.LogDateAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BlueskySignInViewModel(
    private val repository: BlueskySignInRepository,
    private val config: LogDateConfigRepository,
    private val flags: FeatureFlagStore,
    private val passkeys: PasskeyManager,
    private val initialProfile: suspend () -> AccountCreationRequest,
    private val finishAccount: suspend (LogDateAccount) -> InitialSyncStatus,
) : ViewModel() {
    private val mutableUi = MutableStateFlow(BlueskySignInUiState())
    val uiState = mutableUi.asStateFlow()
    val stage = repository.state
    private var action: Job? = null
    private var completion: Job? = null
    private var editedDetails = false
    private var completedAccount: String? = null
    private var previousOrigin: String? = null
    private var leavingCompletedFlow = false

    init {
        observeAvailability()
        viewModelScope.launch {
            repository.state.collect { next ->
                val saved = (next as? BlueskySignInState.AccountDetails)?.savedRequest
                if (saved != null) mutableUi.update { it.copy(details = saved, detailsLocked = true) }
                if (next is BlueskySignInState.Complete) beginInitialSync(next)
            }
        }
        viewModelScope.launch {
            try {
                val profile = initialProfile()
                if (!editedDetails && !mutableUi.value.detailsLocked) mutableUi.update { it.copy(details = profile) }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (_: Exception) {
                // Profile prefill is optional; the person can enter the same fields directly.
            }
        }
    }

    private fun observeAvailability() {
        viewModelScope.launch {
            combine(
                config.backendUrl,
                config.serverDescriptor,
                flags.observe(FeatureFlag.BLUESKY_SIGN_IN),
                passkeys.getAvailabilityStatus(),
            ) { origin, _, enabled, capabilities ->
                origin to (enabled && capabilities.isSupported)
            }.collectLatest { (origin, locallyAllowed) ->
                val changedServer = previousOrigin != null && previousOrigin != origin
                previousOrigin = origin
                val available = locallyAllowed && availableSafely()
                val wasAvailable = mutableUi.value.available
                if (changedServer || wasAvailable && !available && repository.state.value !is BlueskySignInState.Complete) {
                    action?.cancelAndJoin()
                    completion?.cancelAndJoin()
                    repository.cancel()
                    completedAccount = null
                    mutableUi.update {
                        it.copy(
                            available = available,
                            availabilityResolved = true,
                            browserUrl = null,
                            finished = false,
                            initialSync = InitialSyncStatus.NotStarted,
                            failure = if (changedServer) BlueskySignInFailure.CONTEXT_CHANGED else BlueskySignInFailure.UNAVAILABLE,
                        )
                    }
                } else {
                    mutableUi.update { it.copy(available = available, availabilityResolved = true) }
                    if (available &&
                        (repository.state.value is BlueskySignInState.Idle || repository.state.value is BlueskySignInState.Failed)
                    ) {
                        repository.restore()
                    }
                }
            }
        }
    }

    private suspend fun availableSafely(): Boolean =
        try {
            repository.prepare()
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: Exception) {
            false
        }

    fun updateHandle(value: String) {
        mutableUi.update { it.copy(handle = value, failure = null) }
    }

    fun updateDetails(
        username: String,
        displayName: String,
    ) {
        if (mutableUi.value.detailsLocked) return
        editedDetails = true
        mutableUi.update { it.copy(details = it.details.copy(username = username, displayName = displayName), failure = null) }
    }

    fun start() {
        if (!mutableUi.value.available || mutableUi.value.handle.isBlank()) return
        runAction {
            mutableUi.update {
                it.copy(
                    detailsLocked = false,
                    finished = false,
                    initialSync = InitialSyncStatus.NotStarted,
                    signupGeneration = it.signupGeneration + 1,
                )
            }
            val result =
                repository.start(
                    mutableUi.value.handle
                        .trim()
                        .removePrefix("@"),
                )
            result.onSuccess { url -> mutableUi.update { it.copy(browserUrl = url) } }.onFailure {
                val reason = (repository.state.value as? BlueskySignInState.Failed)?.reason ?: BlueskySignInFailure.UNAVAILABLE
                mutableUi.update { it.copy(failure = reason) }
            }
        }
    }

    fun resume(attemptId: String) {
        if (!Regex("[A-Za-z0-9_-]{43}").matches(attemptId)) {
            mutableUi.update { it.copy(failure = BlueskySignInFailure.EXPIRED) }
            return
        }
        runAction {
            if (!availableSafely()) {
                mutableUi.update { it.copy(failure = BlueskySignInFailure.UNAVAILABLE) }
            } else {
                repository.resume("${BlueskyBrowserReturn.URL}?attempt=$attemptId")
            }
        }
    }

    fun createAccount(request: AccountCreationRequest? = null) {
        val current = mutableUi.value
        if (!current.available || repository.state.value !is BlueskySignInState.AccountDetails) return
        val entered = request ?: current.details
        val details =
            if (current.detailsLocked) {
                current.details
            } else {
                entered.copy(
                    originalBio = if (entered.bio == current.details.bio) current.details.originalBio else entered.bio,
                )
            }
        if (!details.isValidBlueskyProfile()) {
            mutableUi.update { it.copy(failure = BlueskySignInFailure.INVALID_REQUEST) }
            return
        }
        runAction {
            mutableUi.update { it.copy(creatingAccount = true) }
            try {
                repository.createAccount(details)
                restoreAfterFailure()
            } finally {
                mutableUi.update { it.copy(creatingAccount = false) }
            }
        }
    }

    fun confirmPasskey(adoptLocalData: Boolean = false) {
        val current = repository.state.value
        val canConfirm =
            current is BlueskySignInState.ConfirmPasskey ||
                current is BlueskySignInState.LocalDataConsent &&
                !current.requiresNewSignIn &&
                adoptLocalData
        if (!mutableUi.value.available || !canConfirm) return
        runAction {
            repository.confirmPasskey(adoptLocalData)
            restoreAfterFailure()
        }
    }

    private suspend fun restoreAfterFailure() {
        val failed = repository.state.value as? BlueskySignInState.Failed ?: return
        mutableUi.update { it.copy(failure = failed.reason) }
        repository.restore()
    }

    fun restart() =
        cancel {
            completedAccount = null
            mutableUi.update {
                it.copy(
                    detailsLocked = false,
                    failure = null,
                    finished = false,
                    initialSync = InitialSyncStatus.NotStarted,
                )
            }
        }

    fun leaveCompletedFlow(onComplete: () -> Unit) {
        if (!mutableUi.value.finished || repository.state.value !is BlueskySignInState.Complete || leavingCompletedFlow) return
        leavingCompletedFlow = true
        cancel(onComplete)
    }

    fun browserLaunchFailed() {
        mutableUi.update { it.copy(browserUrl = null, failure = BlueskySignInFailure.UNAVAILABLE) }
    }

    fun clearFailure() {
        mutableUi.update { it.copy(failure = null) }
    }

    fun browserOpened() {
        mutableUi.update { it.copy(browserUrl = null) }
    }

    fun cancel(onCanceled: () -> Unit = {}) {
        viewModelScope.launch {
            action?.cancelAndJoin()
            completion?.cancelAndJoin()
            repository.cancel()
            mutableUi.update { it.copy(browserUrl = null, busy = false) }
            onCanceled()
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        if (action?.isActive == true) return
        action =
            viewModelScope.launch {
                mutableUi.update { it.copy(busy = true, failure = null) }
                try {
                    block()
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (_: Exception) {
                    mutableUi.update { it.copy(failure = BlueskySignInFailure.UNAVAILABLE) }
                } finally {
                    mutableUi.update { it.copy(busy = false) }
                }
            }
    }

    private fun beginInitialSync(accepted: BlueskySignInState.Complete) {
        val key = "${accepted.backendOrigin}|${accepted.account.id}"
        if (completedAccount == key) return
        if (accepted.backendOrigin != config.getCurrentBackendUrl()) {
            mutableUi.update { it.copy(failure = BlueskySignInFailure.CONTEXT_CHANGED) }
            return
        }
        completedAccount = key
        completion =
            viewModelScope.launch {
                mutableUi.update { it.copy(initialSync = InitialSyncStatus.Running) }
                val result =
                    try {
                        finishAccount(accepted.account)
                    } catch (canceled: CancellationException) {
                        throw canceled
                    } catch (_: Exception) {
                        InitialSyncStatus.Failed
                    }
                currentCoroutineContext().ensureActive()
                if (accepted.backendOrigin == config.getCurrentBackendUrl()) {
                    mutableUi.update { it.copy(initialSync = result, finished = true) }
                }
            }
    }
}

data class BlueskySignInUiState(
    val available: Boolean = false,
    val availabilityResolved: Boolean = available,
    val handle: String = "",
    val details: AccountCreationRequest = AccountCreationRequest("", ""),
    val detailsLocked: Boolean = false,
    val browserUrl: String? = null,
    val initialSync: InitialSyncStatus = InitialSyncStatus.NotStarted,
    val finished: Boolean = false,
    val busy: Boolean = false,
    val creatingAccount: Boolean = false,
    val signupGeneration: Int = 0,
    val failure: BlueskySignInFailure? = null,
) {
    val canCreateAccount: Boolean get() = details.isValidBlueskyProfile()

    override fun toString(): String = "BlueskySignInUiState()"
}

internal fun AccountCreationRequest.isValidBlueskyProfile(): Boolean =
    Regex("[A-Za-z0-9_]{3,50}").matches(username) && displayName.isNotBlank() && displayName.length <= 100
