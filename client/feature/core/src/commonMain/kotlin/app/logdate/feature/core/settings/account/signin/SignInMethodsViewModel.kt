package app.logdate.feature.core.settings.account.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.networking.PasskeyApiErrorCodes
import app.logdate.client.networking.PasskeyApiException
import app.logdate.client.permissions.PasskeyErrorCodes
import app.logdate.client.permissions.PasskeyException
import app.logdate.client.permissions.PasskeyManager
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.client.repository.account.NotSignedInException
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.shared.model.PasskeyInfo
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** What the sign-in methods screen shows. */
sealed interface SignInMethodsUiState {
    data object Loading : SignInMethodsUiState

    data object NotLoaded : SignInMethodsUiState

    data class Loaded(
        val passkeys: List<PasskeyRow>,
        val linkedProviders: List<LinkedProviderRow>,
        /** Whether this device can create a passkey. Where it can't, the action is not shown. */
        val canAddPasskey: Boolean,
        val add: AddPasskeyState = AddPasskeyState.Idle,
        val removal: PasskeyRemovalState = PasskeyRemovalState.Idle,
        val refresh: SignInMethodsRefreshState = SignInMethodsRefreshState.Idle,
    ) : SignInMethodsUiState {
        val isAddingPasskey: Boolean get() = add == AddPasskeyState.Running
        val removingCredentialId: String? get() = (removal as? PasskeyRemovalState.Running)?.credentialId
        val isBusy: Boolean get() =
            isAddingPasskey ||
                removal is PasskeyRemovalState.Running ||
                refresh == SignInMethodsRefreshState.Running
    }

    data class Failed(
        val reason: SignInMethodsLoadError,
    ) : SignInMethodsUiState
}

/**
 * One passkey on the account.
 *
 * @property deviceName the device it was created on, or `null` when the server never learned one
 * @property canRemove whether removing it would still leave a way to sign in
 */
data class PasskeyRow(
    val credentialId: String,
    val deviceName: String?,
    val addedOn: LocalDate?,
    val lastUsedOn: LocalDate?,
    val canRemove: Boolean,
)

/** A non-passkey way to sign in, such as a linked Google account. */
data class LinkedProviderRow(
    val kind: LinkedSignInProvider.Kind,
    val email: String?,
    val lastSignInOn: LocalDate?,
)

enum class SignInMethodsLoadError {
    NOT_SIGNED_IN,
    OFFLINE,
    SERVER,
}

enum class AddPasskeyFailure {
    /** The platform refused because a passkey for this account already lives on this device. */
    ALREADY_ON_THIS_DEVICE,
    NOT_SIGNED_IN,
    OFFLINE,

    /** The platform could not create a passkey (unsupported, locked, or its own error). */
    PLATFORM,
    SERVER,
}

enum class RemovePasskeyFailure {
    LAST_SIGN_IN_METHOD,
    OFFLINE,
    SERVER,
}

sealed interface AddPasskeyState {
    data object Idle : AddPasskeyState

    data object Running : AddPasskeyState

    data class Failed(
        val reason: AddPasskeyFailure,
    ) : AddPasskeyState
}

sealed interface PasskeyRemovalState {
    data object Idle : PasskeyRemovalState

    data class Running(
        val credentialId: String,
    ) : PasskeyRemovalState

    data class Failed(
        val credentialId: String,
        val reason: RemovePasskeyFailure,
    ) : PasskeyRemovalState
}

sealed interface SignInMethodsRefreshState {
    data object Idle : SignInMethodsRefreshState

    data object Running : SignInMethodsRefreshState

    data class Failed(
        val reason: SignInMethodsLoadError,
    ) : SignInMethodsRefreshState
}

sealed interface SignInMethodsEvent {
    data class PasskeyAdded(
        val deviceName: String?,
    ) : SignInMethodsEvent

    data object PasskeyRemoved : SignInMethodsEvent
}

data class SignInMethodsNotice(
    val id: Long,
    val event: SignInMethodsEvent,
)

/**
 * Lists the ways a person can sign in to their account and lets them add or remove passkeys.
 *
 * @param defaultPasskeyName the name the server gives a passkey when the client sends none. Older
 *   passkeys all carry it, so it says nothing about which device they belong to and is shown as
 *   an unnamed passkey instead.
 * @param connectedRpId the connected server's passkey relying party ID, or `null` when unknown
 * @param passkeysWorkWith whether this device's platform can create passkeys for a relying party
 */
class SignInMethodsViewModel(
    private val accountRepository: PasskeyAccountRepository,
    private val passkeyManager: PasskeyManager,
    private val defaultPasskeyName: () -> String?,
    private val connectedRpId: () -> String? = { null },
    private val passkeysWorkWith: (rpId: String) -> Boolean = { true },
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    private val _state = MutableStateFlow<SignInMethodsUiState>(SignInMethodsUiState.Loading)
    val state: StateFlow<SignInMethodsUiState> = _state.asStateFlow()

    private val _notices = MutableStateFlow<List<SignInMethodsNotice>>(emptyList())
    val notices: StateFlow<List<SignInMethodsNotice>> = _notices.asStateFlow()
    private var nextNoticeId = 0L
    private var working = false

    init {
        refresh()
    }

    fun acknowledgeNotice(id: Long) {
        _notices.update { notices -> notices.filterNot { it.id == id } }
    }

    fun refresh() {
        if (working) return
        working = true
        markRefreshing()
        work { loadAndApply() }
    }

    fun addPasskey() {
        val loaded = _state.value as? SignInMethodsUiState.Loaded ?: return
        if (working || !loaded.canAddPasskey) return
        working = true
        _state.value = loaded.copy(add = AddPasskeyState.Running)
        work {
            val result = request { accountRepository.addPasskey() }
            result.fold(
                onSuccess = { passkey ->
                    updateLoaded {
                        val rows =
                            (it.passkeys.filterNot { row -> row.credentialId == passkey.credentialId } + passkey.toRow(false))
                                .sortedBy { row -> row.addedOn }
                        it.copy(passkeys = removableRows(rows, it.linkedProviders), add = AddPasskeyState.Idle)
                    }
                    notice(SignInMethodsEvent.PasskeyAdded(passkey.displayName()))
                    markRefreshing()
                    loadAndApply()
                },
                onFailure = { failure ->
                    if (failure is NotSignedInException) {
                        _state.value = SignInMethodsUiState.Failed(SignInMethodsLoadError.NOT_SIGNED_IN)
                    } else {
                        val reason = addPasskeyFailure(failure)
                        updateLoaded { it.copy(add = reason?.let { reason -> AddPasskeyState.Failed(reason) } ?: AddPasskeyState.Idle) }
                    }
                },
            )
        }
    }

    fun removePasskey(credentialId: String) {
        val loaded = _state.value as? SignInMethodsUiState.Loaded ?: return
        if (working) return
        val row = loaded.passkeys.firstOrNull { it.credentialId == credentialId } ?: return
        if (!row.canRemove) {
            _state.value = loaded.copy(removal = PasskeyRemovalState.Failed(credentialId, RemovePasskeyFailure.LAST_SIGN_IN_METHOD))
            return
        }
        working = true
        _state.value = loaded.copy(removal = PasskeyRemovalState.Running(credentialId))
        work {
            val result = request { accountRepository.deletePasskey(credentialId) }
            result.fold(
                onSuccess = {
                    updateLoaded {
                        val rows = it.passkeys.filterNot { row -> row.credentialId == credentialId }
                        it.copy(passkeys = removableRows(rows, it.linkedProviders), removal = PasskeyRemovalState.Idle)
                    }
                    notice(SignInMethodsEvent.PasskeyRemoved)
                    markRefreshing()
                    loadAndApply()
                },
                onFailure = { failure ->
                    if (failure is NotSignedInException) {
                        _state.value = SignInMethodsUiState.Failed(SignInMethodsLoadError.NOT_SIGNED_IN)
                    } else {
                        Napier.w("Failed to remove passkey", failure)
                        updateLoaded { it.copy(removal = PasskeyRemovalState.Failed(credentialId, removePasskeyFailure(failure))) }
                    }
                },
            )
        }
    }

    private fun work(block: suspend () -> Unit) {
        working = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                updateLoaded {
                    it.copy(
                        add = if (it.add == AddPasskeyState.Running) AddPasskeyState.Idle else it.add,
                        removal = if (it.removal is PasskeyRemovalState.Running) PasskeyRemovalState.Idle else it.removal,
                        refresh = if (it.refresh == SignInMethodsRefreshState.Running) SignInMethodsRefreshState.Idle else it.refresh,
                    )
                }
                if (_state.value == SignInMethodsUiState.Loading) _state.value = SignInMethodsUiState.NotLoaded
                working = false
            }
        }
    }

    private fun markRefreshing() {
        val previous = _state.value as? SignInMethodsUiState.Loaded
        _state.value = previous?.copy(refresh = SignInMethodsRefreshState.Running) ?: SignInMethodsUiState.Loading
    }

    private fun updateLoaded(transform: (SignInMethodsUiState.Loaded) -> SignInMethodsUiState.Loaded) {
        _state.update { if (it is SignInMethodsUiState.Loaded) transform(it) else it }
    }

    private fun notice(event: SignInMethodsEvent) {
        val notice = SignInMethodsNotice(++nextNoticeId, event)
        _notices.update { it + notice }
    }

    private suspend fun loadAndApply() {
        val previous = _state.value as? SignInMethodsUiState.Loaded
        val result = load(previous)
        _state.value =
            when {
                result is SignInMethodsUiState.Failed && previous != null && result.reason != SignInMethodsLoadError.NOT_SIGNED_IN ->
                    previous.copy(refresh = SignInMethodsRefreshState.Failed(result.reason))
                result is SignInMethodsUiState.Loaded && previous != null -> result.copy(add = previous.add, removal = previous.removal)
                else -> result
            }
    }

    private suspend fun load(previous: SignInMethodsUiState.Loaded?): SignInMethodsUiState {
        try {
            val (passkeysResult, providersResult) =
                coroutineScope {
                    val passkeys = async { request { accountRepository.listPasskeys() } }
                    val providers = async { request { accountRepository.listLinkedSignInProviders() } }
                    passkeys.await() to providers.await()
                }
            val passkeys = passkeysResult.getOrElse { return failedToLoad(it) }
            val providersFailure = providersResult.exceptionOrNull()
            if (providersFailure is NotSignedInException) return failedToLoad(providersFailure)
            val providers =
                providersResult.fold(
                    onSuccess = { values -> values.map { LinkedProviderRow(it.kind, it.email, it.lastSignInAt?.toLocalDate()) } },
                    onFailure = {
                        Napier.w("Could not load linked sign-in providers", it)
                        previous?.linkedProviders.orEmpty()
                    },
                )
            val supported =
                try {
                    passkeyManager.getCapabilities().isSupported
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    Napier.w("Could not read this device's passkey support", failure)
                    previous?.canAddPasskey ?: false
                }
            return SignInMethodsUiState.Loaded(
                passkeys = removableRows(passkeys.sortedBy { it.createdAt }.map { it.toRow(false) }, providers),
                linkedProviders = providers,
                canAddPasskey = supported && connectedRpId()?.let(passkeysWorkWith) != false,
                refresh = providersFailure?.let { SignInMethodsRefreshState.Failed(loadFailure(it)) } ?: SignInMethodsRefreshState.Idle,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return failedToLoad(failure)
        }
    }

    private suspend fun <T> request(block: suspend () -> Result<T>): Result<T> =
        try {
            block().also { result -> result.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(failure)
        }

    private fun removableRows(
        rows: List<PasskeyRow>,
        providers: List<LinkedProviderRow>,
    ): List<PasskeyRow> = rows.map { it.copy(canRemove = rows.size + providers.size > 1) }

    private fun PasskeyInfo.toRow(canRemove: Boolean) =
        PasskeyRow(
            credentialId = credentialId,
            deviceName = displayName(),
            addedOn = createdAt.toLocalDate(),
            lastUsedOn = lastUsedAt?.toLocalDate(),
            canRemove = canRemove,
        )

    private fun failedToLoad(error: Throwable): SignInMethodsUiState.Failed {
        Napier.w("Failed to load sign-in methods", error)
        return SignInMethodsUiState.Failed(loadFailure(error))
    }

    private fun PasskeyInfo.displayName(): String? = nickname?.trim()?.takeIf { it.isNotEmpty() && it != defaultPasskeyName() }

    private fun Instant.toLocalDate(): LocalDate = toLocalDateTime(timeZone).date
}

private fun loadFailure(error: Throwable): SignInMethodsLoadError =
    when {
        error is NotSignedInException -> SignInMethodsLoadError.NOT_SIGNED_IN
        error.isNetworkError() -> SignInMethodsLoadError.OFFLINE
        else -> SignInMethodsLoadError.SERVER
    }

/** Maps an add-passkey failure to what the person is told, or `null` when they cancelled. */
private fun addPasskeyFailure(error: Throwable): AddPasskeyFailure? {
    if (error is PasskeyException) {
        return when (error.errorCode) {
            PasskeyErrorCodes.USER_CANCELLED -> null
            PasskeyErrorCodes.INVALID_STATE, PasskeyErrorCodes.CONSTRAINT_ERROR -> AddPasskeyFailure.ALREADY_ON_THIS_DEVICE
            else -> AddPasskeyFailure.PLATFORM.also { Napier.w("The platform could not create a passkey", error) }
        }
    }
    Napier.w("Failed to add a passkey", error)
    return when {
        error is NotSignedInException -> AddPasskeyFailure.NOT_SIGNED_IN
        error.isNetworkError() -> AddPasskeyFailure.OFFLINE
        else -> AddPasskeyFailure.SERVER
    }
}

private fun removePasskeyFailure(error: Throwable): RemovePasskeyFailure =
    when {
        (error as? PasskeyApiException)?.errorCode == PasskeyApiErrorCodes.LAST_SIGNIN_FACTOR -> RemovePasskeyFailure.LAST_SIGN_IN_METHOD
        error.isNetworkError() -> RemovePasskeyFailure.OFFLINE
        else -> RemovePasskeyFailure.SERVER
    }

private fun Throwable.isNetworkError(): Boolean = (this as? PasskeyApiException)?.errorCode == PasskeyApiErrorCodes.NETWORK_ERROR
