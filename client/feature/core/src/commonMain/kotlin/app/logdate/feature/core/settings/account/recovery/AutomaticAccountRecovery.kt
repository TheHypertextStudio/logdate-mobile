package app.logdate.feature.core.settings.account.recovery

import app.logdate.client.repository.account.IdentityRecoveryException
import app.logdate.client.repository.account.IdentityRecoveryFailure
import app.logdate.client.repository.account.PasskeyConfirmation
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Sets up account recovery without asking anyone to open or save the recovery phrase. Once this
 * device has the phrase and someone is signed in, it registers the phrase's key on their identity,
 * spending the passkey confirmation from their latest sign-in or sign-up so they see no second
 * prompt. It runs when the app starts, after each passkey sign-in, and when asked to check again.
 *
 * Without a recent confirmation nothing is sent: [state] becomes [AccountRecoveryUiState.NeedsSetup]
 * until the next sign-in, or until [finishWithPasskey] shows the passkey prompt. Connection and
 * server problems are retried after [retryDelays] while [state] says so; other failures stay in
 * [state] with what to do about them. Nothing here shows a screen.
 *
 * @param signedInAccount the signed-in account's id, or `null` when signed out
 * @param passkeyConfirmations the latest passkey sign-in confirmation; each new one starts a try
 */
class AutomaticAccountRecovery(
    private val recovery: AccountRecovery,
    private val loadPhrase: suspend () -> List<String>?,
    private val signedInAccount: Flow<String?>,
    private val passkeyConfirmations: Flow<PasskeyConfirmation?>,
    private val scope: CoroutineScope,
    private val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
) {
    private val _state = MutableStateFlow<AccountRecoveryUiState?>(null)

    /** `null` when account recovery isn't offered, nobody is signed in, or this device has no phrase. */
    val state: StateFlow<AccountRecoveryUiState?> = _state.asStateFlow()

    private val checks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val attempts = Mutex()
    private var started = false

    /** Starts setting up account recovery whenever it can be done without a prompt. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(
                recovery.isOffered.distinctUntilChanged(),
                signedInAccount.distinctUntilChanged(),
                passkeyConfirmations.distinctUntilChanged(),
                checks.onStart { emit(Unit) },
            ) { offered, account, _, _ -> offered && account != null }
                .collectLatest { available -> if (available) setUpQuietly() else _state.value = null }
        }
    }

    /** Tries again now without a prompt, such as when the recovery phrase page opens. */
    fun checkAgain() {
        checks.tryEmit(Unit)
    }

    /** Asks for the passkey and finishes setting up, for a person who chose to do it now. */
    fun finishWithPasskey() {
        if (_state.value == AccountRecoveryUiState.Finishing) return
        scope.launch {
            val phrase = readPhrase() ?: return@launch
            _state.value = AccountRecoveryUiState.Finishing
            val outcome = attempts.withLock { recovery.finishSetup(phrase, askForPasskey = true) }
            _state.value =
                when (val next = outcome.toState()) {
                    // The passkey was spent on this try, so trying again without one would only ask again.
                    is AccountRecoveryUiState.Retrying -> {
                        next.failure?.let(AccountRecoveryUiState::SetupFailed)
                            ?: AccountRecoveryUiState.CheckFailed
                    }

                    else -> {
                        next
                    }
                }
        }
    }

    private suspend fun setUpQuietly() {
        val phrase = readPhrase() ?: return
        if (_state.value == null) _state.value = AccountRecoveryUiState.Checking
        for (attempt in 0..retryDelays.size) {
            val next = attempts.withLock { recovery.finishSetup(phrase, askForPasskey = false) }.toState()
            if (next !is AccountRecoveryUiState.Retrying) {
                _state.value = next
                return
            }
            if (attempt == retryDelays.size) {
                _state.value = next.failure?.let(AccountRecoveryUiState::SetupFailed) ?: AccountRecoveryUiState.CheckFailed
                return
            }
            _state.value = next
            delay(retryDelays[attempt])
        }
    }

    /** The phrase on this device; `null`, with [state] cleared, when there is none. */
    private suspend fun readPhrase(): List<String>? {
        val phrase =
            try {
                loadPhrase()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Napier.w("Could not read the recovery phrase to set up account recovery", error)
                _state.value = AccountRecoveryUiState.CheckFailed
                return null
            }
        if (phrase == null) _state.value = null
        return phrase
    }

    private fun Result<Unit>.toState(): AccountRecoveryUiState =
        fold(
            onSuccess = { AccountRecoveryUiState.Ready },
            onFailure = { error ->
                if (error is CancellationException) throw error
                val failure =
                    (error as? IdentityRecoveryException)?.failure
                        ?: return@fold AccountRecoveryUiState.Retrying(failure = null).also {
                            Napier.w("Could not check whether account recovery is set up", error)
                        }
                when (failure) {
                    IdentityRecoveryFailure.CONFIRMATION_NEEDED,
                    IdentityRecoveryFailure.STEP_UP_CANCELLED,
                    -> AccountRecoveryUiState.NeedsSetup

                    in TRANSIENT_FAILURES -> AccountRecoveryUiState.Retrying(failure)

                    else -> AccountRecoveryUiState.SetupFailed(failure)
                }
            },
        )

    private companion object {
        val DEFAULT_RETRY_DELAYS = listOf(10.seconds, 1.minutes, 5.minutes, 30.minutes)

        /** Failures that can pass on their own, so trying again later may succeed without the person. */
        val TRANSIENT_FAILURES =
            setOf(
                IdentityRecoveryFailure.UNREACHABLE,
                IdentityRecoveryFailure.IDENTITY_CHANGED,
                IdentityRecoveryFailure.REFUSED,
            )
    }
}
