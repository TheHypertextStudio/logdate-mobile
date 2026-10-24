package app.logdate.feature.onboarding.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.device.crypto.IdentityKeyManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_recovery_phrase_enter_existing
import logdate.client.feature.onboarding.generated.resources.onboarding_recovery_phrase_prepare_failed
import org.jetbrains.compose.resources.StringResource

data class RecoveryPhraseSetupUiState(
    val words: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: StringResource? = null,
)

class RecoveryPhraseViewModel(
    private val identityKeyManager: IdentityKeyManager,
) : ViewModel() {
    private val _setupState = MutableStateFlow(RecoveryPhraseSetupUiState())
    val setupState: StateFlow<RecoveryPhraseSetupUiState> = _setupState.asStateFlow()

    init {
        prepareRecoveryPhrase()
    }

    fun prepareRecoveryPhrase() {
        viewModelScope.launch {
            _setupState.value = RecoveryPhraseSetupUiState(isLoading = true)
            _setupState.value =
                runCatching {
                    val existingPhrase = identityKeyManager.getStoredRecoveryPhrase()
                    when {
                        existingPhrase != null -> {
                            RecoveryPhraseSetupUiState(
                                words = existingPhrase.words,
                                isLoading = false,
                            )
                        }

                        identityKeyManager.hasIdentityKey() -> {
                            RecoveryPhraseSetupUiState(
                                isLoading = false,
                                errorMessage = Res.string.onboarding_recovery_phrase_enter_existing,
                            )
                        }

                        else -> {
                            // Goes through ensureIdentityKey so this shares the lock with the sync
                            // and onboarding provisioning paths; setupNewIdentity would race them.
                            identityKeyManager.ensureIdentityKey()
                            RecoveryPhraseSetupUiState(
                                words = identityKeyManager.getStoredRecoveryPhrase()?.words.orEmpty(),
                                isLoading = false,
                            )
                        }
                    }
                }.getOrElse { error ->
                    Napier.e("Failed to prepare recovery phrase", error)
                    RecoveryPhraseSetupUiState(
                        isLoading = false,
                        errorMessage = Res.string.onboarding_recovery_phrase_prepare_failed,
                    )
                }
        }
    }

    /** Records that the person saved a copy of [words]; nothing waits on it. */
    fun confirmSaved(words: List<String>) {
        viewModelScope.launch {
            runCatching { identityKeyManager.verifyRecoveryPhrase(words) }
                .onSuccess { recorded -> if (!recorded) Napier.w("The saved recovery phrase did not match this device's phrase") }
                .onFailure { error -> Napier.w("Could not record that the recovery phrase was saved", error) }
        }
    }

    suspend fun recoverIdentity(words: List<String>): Result<Unit> =
        runCatching {
            identityKeyManager.recoverIdentity(words)
            _setupState.value =
                RecoveryPhraseSetupUiState(
                    words = words,
                    isLoading = false,
                )
        }
}
