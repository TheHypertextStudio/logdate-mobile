package app.logdate.feature.core.settings.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.identity.ResolvedUserIdentity
import app.logdate.client.permissions.EmailVerificationOutcome
import app.logdate.client.repository.account.LinkedSignInProvider
import app.logdate.client.repository.account.PasskeyAccountRepository
import app.logdate.feature.core.settings.account.recovery.AccountRecoveryUiState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Account screen shows. */
sealed interface AccountUiState {
    data object Loading : AccountUiState

    /** No session on this device, so the screen offers sign-in instead. */
    data object SignedOut : AccountUiState

    data class SignedIn(
        val header: AccountHeader,
        val signIn: SignInSummary,
        /** Whether this device holds the recovery phrase that unlocks the synced journal; `null` until checked. */
        val hasRecoveryPhrase: Boolean?,
        /** Whether account recovery needs the person, such as one passkey confirmation or a fix for a failure. */
        val accountRecoveryNeedsAttention: Boolean = false,
        /** `null` when there is no email to show and no way to add one here. */
        val email: EmailRow?,
        val server: ServerRow,
        val isSigningOut: Boolean,
        /** What signing out does to a linked watch, so the confirmation can say so. */
        val watch: WatchAtSignOut = WatchAtSignOut.None,
    ) : AccountUiState
}

/** A linked watch is erased when the person signs out; this is what that costs them. */
sealed interface WatchAtSignOut {
    /** No watch is linked. */
    data object None : WatchAtSignOut

    /** A watch is linked and everything on it has synced. */
    data object Synced : WatchAtSignOut

    /** [count] moments on the watch haven't synced; any it can't send first are cleared. */
    data class Unsent(
        val count: Int,
    ) : WatchAtSignOut

    /** A watch is linked but couldn't say whether anything on it is unsynced. */
    data object Unknown : WatchAtSignOut
}

/** Sums what each linked watch last reported as [unsent]; one watch with no report makes the total unknown. */
fun watchAtSignOut(unsent: List<Int?>): WatchAtSignOut {
    if (unsent.isEmpty()) return WatchAtSignOut.None
    val total = unsent.fold(0) { sum, count -> sum + (count ?: return WatchAtSignOut.Unknown) }
    return if (total == 0) WatchAtSignOut.Synced else WatchAtSignOut.Unsent(total)
}

data class AccountHeader(
    val displayName: String,
    val username: String?,
)

/** A short description of how the person signs in, for the row that opens the full list. */
sealed interface SignInSummary {
    data object Loading : SignInSummary

    data class Known(
        val passkeyCount: Int,
        val linkedProviders: List<LinkedSignInProvider.Kind>,
    ) : SignInSummary

    /** The list could not be read; the row still opens the screen that can retry. */
    data object Unknown : SignInSummary
}

data class EmailRow(
    val address: String?,
    val isVerified: Boolean,
    val canVerify: Boolean,
)

data class ServerRow(
    val name: String?,
    val host: String,
    val isLogDateCloud: Boolean,
)

/** Where email verification stands, for the sheet that runs it. */
data class EmailVerificationProgress(
    val isVerifying: Boolean = false,
    val outcome: EmailVerificationOutcome? = null,
)

sealed interface AccountEvent {
    data object SignedOut : AccountEvent

    data object SignOutFailed : AccountEvent
}

/**
 * The Account screen: who is signed in, how they sign in and recover, where their journal is
 * hosted, and signing out.
 */
class AccountViewModel(
    private val accountRepository: PasskeyAccountRepository,
    userIdentity: Flow<ResolvedUserIdentity>,
    private val connectedServer: ConnectedServer,
    private val hasRecoveryPhrase: suspend () -> Boolean,
    private val isEmailVerificationAvailable: suspend () -> Boolean,
    private val verifyEmail: suspend () -> EmailVerificationOutcome,
    private val accountRecovery: Flow<AccountRecoveryUiState?> = flowOf(null),
    private val linkedWatch: suspend () -> WatchAtSignOut = { WatchAtSignOut.None },
) : ViewModel() {
    private val details = MutableStateFlow(AccountDetails())
    private var loadJob: Job? = null

    private val _emailVerification = MutableStateFlow(EmailVerificationProgress())
    val emailVerification: StateFlow<EmailVerificationProgress> = _emailVerification.asStateFlow()

    private val _events = Channel<AccountEvent>(Channel.BUFFERED)
    val events: Flow<AccountEvent> = _events.receiveAsFlow()

    val state: StateFlow<AccountUiState> =
        combine(
            accountRepository.isAuthenticated,
            accountRepository.currentAccount,
            userIdentity,
            connectedServer.info,
            details,
        ) { isAuthenticated, account, identity, server, loaded ->
            if (!isAuthenticated) return@combine AccountUiState.SignedOut
            AccountUiState.SignedIn(
                header =
                    AccountHeader(
                        displayName = identity.displayName.ifBlank { account?.displayName.orEmpty() },
                        username = identity.username ?: account?.username,
                    ),
                signIn = loaded.signIn,
                hasRecoveryPhrase = loaded.hasRecoveryPhrase,
                accountRecoveryNeedsAttention = loaded.accountRecovery.needsAttention(),
                email =
                    when {
                        account?.email != null -> {
                            EmailRow(
                                address = account.email,
                                isVerified = account.emailVerified,
                                canVerify = !account.emailVerified && loaded.canVerifyEmail,
                            )
                        }

                        loaded.canVerifyEmail -> {
                            EmailRow(address = null, isVerified = false, canVerify = true)
                        }

                        else -> {
                            null
                        }
                    },
                server = ServerRow(name = server.displayName, host = server.host, isLogDateCloud = server.isLogDateCloud),
                isSigningOut = loaded.isSigningOut,
                watch = loaded.watch,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AccountUiState.Loading)

    init {
        refresh()
        viewModelScope.launch { accountRecovery.collect { recovery -> details.update { it.copy(accountRecovery = recovery) } } }
    }

    /**
     * Reloads what may have changed since the screen was last open, such as a newly added passkey.
     * A reload already under way is left to finish.
     */
    fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob =
            viewModelScope.launch {
                launch { connectedServer.refresh() }
                loadDetails()
            }
    }

    fun verifyEmail() {
        if (_emailVerification.value.isVerifying) return
        _emailVerification.value = EmailVerificationProgress(isVerifying = true)
        viewModelScope.launch {
            val outcome =
                runCatching { verifyEmail.invoke() }
                    .getOrElse { error ->
                        Napier.e("Email verification failed unexpectedly", error)
                        EmailVerificationOutcome.Failed("verification_crashed")
                    }
            _emailVerification.value = EmailVerificationProgress(outcome = outcome)
            if (outcome is EmailVerificationOutcome.Success) {
                accountRepository.getAccountInfo().onFailure { Napier.w("Could not reload the account after verifying email", it) }
            }
        }
    }

    fun dismissEmailVerification() {
        _emailVerification.value = EmailVerificationProgress()
    }

    fun signOut() {
        if (details.value.isSigningOut) return
        details.update { it.copy(isSigningOut = true) }
        viewModelScope.launch {
            accountRepository
                .signOut()
                .onSuccess { _events.send(AccountEvent.SignedOut) }
                .onFailure { error ->
                    Napier.w("Failed to sign out", error)
                    _events.send(AccountEvent.SignOutFailed)
                }
            details.update { it.copy(isSigningOut = false) }
        }
    }

    /** Rereads the linked watch as the sign-out confirmation opens, so it counts moments recorded since the screen did. */
    fun checkWatch() {
        viewModelScope.launch { loadWatch() }
    }

    /** A check that fails warns as if a watch could lose moments, since signing out erases one either way. */
    private suspend fun loadWatch() {
        val watch =
            try {
                linkedWatch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Napier.w("Could not check the linked watch for unsynced moments", error)
                WatchAtSignOut.Unknown
            }
        details.update { it.copy(watch = watch) }
    }

    /** Each part is shown as soon as it is known, so the local checks don't wait on the network. */
    private suspend fun loadDetails() =
        coroutineScope {
            launch {
                val hasPhrase =
                    runCatching { hasRecoveryPhrase() }
                        .onFailure { Napier.w("Could not check for the recovery phrase", it) }
                        .getOrDefault(false)
                details.update { it.copy(hasRecoveryPhrase = hasPhrase) }
            }
            launch {
                val canVerify =
                    runCatching { isEmailVerificationAvailable() }
                        .onFailure { Napier.w("Could not check whether email verification is available", it) }
                        .getOrDefault(false)
                details.update { it.copy(canVerifyEmail = canVerify) }
            }
            launch { loadWatch() }
            launch {
                val signIn = loadSignInSummary()
                details.update { it.copy(signIn = signIn) }
            }
        }

    private suspend fun loadSignInSummary(): SignInSummary {
        val (passkeys, providers) =
            coroutineScope {
                val passkeys = async { accountRepository.listPasskeys() }
                val providers = async { accountRepository.listLinkedSignInProviders() }
                passkeys.await() to providers.await()
            }
        val error = passkeys.exceptionOrNull() ?: providers.exceptionOrNull()
        if (error != null) {
            Napier.w("Could not read sign-in methods for the Account summary", error)
            return SignInSummary.Unknown
        }
        return SignInSummary.Known(
            passkeyCount = passkeys.getOrThrow().size,
            linkedProviders = providers.getOrThrow().map { it.kind },
        )
    }

    private data class AccountDetails(
        val signIn: SignInSummary = SignInSummary.Loading,
        val hasRecoveryPhrase: Boolean? = null,
        val canVerifyEmail: Boolean = false,
        val isSigningOut: Boolean = false,
        val accountRecovery: AccountRecoveryUiState? = null,
        val watch: WatchAtSignOut = WatchAtSignOut.None,
    )
}

/** Setup that is waiting on the person, as opposed to finished, under way, or retrying on its own. */
private fun AccountRecoveryUiState?.needsAttention(): Boolean =
    this == AccountRecoveryUiState.NeedsSetup ||
        this == AccountRecoveryUiState.CheckFailed ||
        this is AccountRecoveryUiState.SetupFailed
