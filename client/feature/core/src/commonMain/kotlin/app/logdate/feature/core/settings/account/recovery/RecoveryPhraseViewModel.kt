package app.logdate.feature.core.settings.account.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.account.IdentityRecoveryFailure
import app.logdate.client.text.toUiText
import app.logdate.feature.core.AppAuthState
import app.logdate.feature.core.BiometricGatekeeper
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether the recovery phrase on this device can also move the account; shown only when that is offered. */
sealed interface AccountRecoveryUiState {
    data object Checking : AccountRecoveryUiState

    data object Ready : AccountRecoveryUiState

    /** Setup needs one passkey confirmation, such as after the passkey prompt was closed. */
    data object NeedsSetup : AccountRecoveryUiState

    /** Waiting for the passkey and the server. */
    data object Finishing : AccountRecoveryUiState

    /** A connection or server problem stopped setup, and it will be tried again automatically; `null` when the check itself failed. */
    data class Retrying(
        val failure: IdentityRecoveryFailure?,
    ) : AccountRecoveryUiState

    /** Whether setup is finished could not be checked. */
    data object CheckFailed : AccountRecoveryUiState

    data class SetupFailed(
        val failure: IdentityRecoveryFailure,
    ) : AccountRecoveryUiState
}

/** Coordinates secure reads and authentication without replacing the phrase viewer. */
class RecoveryPhraseViewModel(
    private val gatekeeper: BiometricGatekeeper,
    private val loadPhrase: suspend () -> List<String>?,
    private val recovery: AutomaticAccountRecovery? = null,
) : ViewModel() {
    private val _state = MutableStateFlow<RecoveryPhraseUiState>(RecoveryPhraseUiState.Checking)
    val state: StateFlow<RecoveryPhraseUiState> = _state.asStateFlow()
    private var checkJob: Job? = null
    private var revealJob: Job? = null
    private var generation = 0L
    private var screenActive = true
    private var authenticatedWhileStopped = false

    val accountRecovery: StateFlow<AccountRecoveryUiState?> = recovery?.state ?: MutableStateFlow(null)

    fun finishAccountRecoverySetup() {
        recovery?.finishWithPasskey()
    }

    fun checkAccountRecovery() {
        recovery?.checkAgain()
    }

    init {
        check()
    }

    /** Coalesces reads; retry clears an old availability error before starting. */
    fun check() {
        checkAccountRecovery()
        if (checkJob?.isActive == true) return
        val token = generation
        if (_state.value !is RecoveryPhraseUiState.Available) _state.value = RecoveryPhraseUiState.Checking
        checkJob =
            viewModelScope.launch {
                try {
                    val phrase = loadPhrase()
                    if (token != generation) return@launch
                    _state.value =
                        if (phrase == null) {
                            RecoveryPhraseUiState.NotOnThisDevice
                        } else {
                            val current = _state.value as? RecoveryPhraseUiState.Available
                            current ?: RecoveryPhraseUiState.Available()
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not check recovery phrase availability", error)
                    if (token == generation && _state.value !is RecoveryPhraseUiState.Available) {
                        _state.value = RecoveryPhraseUiState.Unreadable
                    }
                }
            }
    }

    fun reveal(prompt: RevealPrompt) {
        val current = _state.value as? RecoveryPhraseUiState.Available ?: return
        if (current.reveal == PhraseRevealState.Authenticating || current.reveal == PhraseRevealState.Reading) return
        if (current.reveal is PhraseRevealState.Revealed) return
        checkJob?.cancel()
        val token = ++generation
        _state.value = current.copy(reveal = PhraseRevealState.Authenticating)
        try {
            gatekeeper.authenticate(
                title = prompt.title.toUiText(),
                subtitle = prompt.subtitle.toUiText(),
                description = prompt.description?.toUiText(),
                requestEnrollmentIfNecessary = false,
                onResult = { result ->
                    viewModelScope.launch { authenticationResult(token, result) }
                },
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Napier.w("Could not start recovery phrase authentication", error)
            updateReveal(token, PhraseRevealState.Failed(RevealFailure.AUTHENTICATION_UNAVAILABLE))
        }
    }

    private fun authenticationResult(
        token: Long,
        result: AppAuthState,
    ) {
        if (token != generation) return
        val current = _state.value as? RecoveryPhraseUiState.Available ?: return
        if (current.reveal != PhraseRevealState.Authenticating) return
        when (result) {
            AppAuthState.AUTHENTICATED, AppAuthState.NO_PROMPT_NEEDED -> {
                if (screenActive) readRevealedPhrase(token) else authenticatedWhileStopped = true
            }

            AppAuthState.REQUIRE_PROMPT -> {
                updateReveal(token, PhraseRevealState.Hidden)
            }

            else -> {
                updateReveal(token, PhraseRevealState.Failed(RevealFailure.AUTHENTICATION_UNAVAILABLE))
            }
        }
    }

    private fun readRevealedPhrase(token: Long) {
        updateReveal(token, PhraseRevealState.Reading)
        revealJob =
            viewModelScope.launch {
                try {
                    val words = loadPhrase()
                    if (token != generation || !screenActive) return@launch
                    if (words == null) {
                        _state.value = RecoveryPhraseUiState.NotOnThisDevice
                    } else {
                        updateReveal(token, PhraseRevealState.Revealed(words))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Napier.w("Could not read the recovery phrase", error)
                    updateReveal(token, PhraseRevealState.Failed(RevealFailure.UNREADABLE))
                }
            }
    }

    fun hide() {
        generation++
        authenticatedWhileStopped = false
        revealJob?.cancel()
        checkJob?.cancel()
        checkJob = null
        val current = _state.value as? RecoveryPhraseUiState.Available ?: return
        _state.value = current.copy(reveal = PhraseRevealState.Hidden)
    }

    fun onScreenActive() {
        screenActive = true
        if (authenticatedWhileStopped) {
            authenticatedWhileStopped = false
            readRevealedPhrase(generation)
        } else if (_state.value !is RecoveryPhraseUiState.Available) {
            check()
        }
    }

    /** System authentication may temporarily stop this host; keep its intent, never its words. */
    fun onScreenInactive() {
        screenActive = false
        val current = _state.value as? RecoveryPhraseUiState.Available
        if (current?.reveal != PhraseRevealState.Authenticating) hide()
    }

    fun onScreenLeft() {
        screenActive = false
        hide()
    }

    private fun updateReveal(
        token: Long,
        reveal: PhraseRevealState,
    ) {
        if (token != generation) return
        val current = _state.value as? RecoveryPhraseUiState.Available ?: return
        _state.value = current.copy(reveal = reveal)
    }
}
