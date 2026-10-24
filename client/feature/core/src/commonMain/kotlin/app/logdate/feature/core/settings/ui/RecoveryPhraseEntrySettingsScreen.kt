@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.sync.RecoverIdentityUseCase
import app.logdate.client.sync.RecoveryPhraseVerificationException
import app.logdate.client.sync.RecoveryPhraseVerificationReason
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.theme.Spacing
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_description
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_error
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_mismatch_error
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_no_proof_error
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_retry_error
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_submit
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_title
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_unavailable_error
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_settings_word_count_error
import logdate.client.feature.core.generated.resources.recovery_phrase_entry_word_number
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

class RecoveryPhraseEntryViewModel(
    private val recoverIdentityUseCase: RecoverIdentityUseCase,
    private val onRecoverySuccess: () -> Unit = {},
) : ViewModel() {
    fun submit(
        words: List<String>,
        onResult: (Result<Unit>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = recoverIdentityUseCase(words)
            result.onFailure { Napier.e("Could not recover identity from Settings", it) }
            result.onSuccess {
                runCatching(onRecoverySuccess)
                    .onFailure { error -> Napier.w("Could not request Cloud restore after recovery", error) }
            }
            onResult(result)
        }
    }
}

internal enum class RecoveryPhraseFailure { INVALID_WORDS, MISMATCH, UNAVAILABLE, NO_PROOF, RETRYABLE }

internal fun classifyRecoveryPhraseFailure(error: Throwable): RecoveryPhraseFailure =
    when (error) {
        is RecoveryPhraseVerificationException -> {
            when (error.reason) {
                RecoveryPhraseVerificationReason.MISMATCH -> RecoveryPhraseFailure.MISMATCH
                RecoveryPhraseVerificationReason.UNAVAILABLE -> RecoveryPhraseFailure.UNAVAILABLE
                RecoveryPhraseVerificationReason.NO_PROOF -> RecoveryPhraseFailure.NO_PROOF
            }
        }

        is IllegalArgumentException -> {
            if (error.message == "Invalid recovery phrase") RecoveryPhraseFailure.INVALID_WORDS else RecoveryPhraseFailure.RETRYABLE
        }

        else -> {
            RecoveryPhraseFailure.RETRYABLE
        }
    }

internal fun RecoveryPhraseFailure.messageResource(): StringResource =
    when (this) {
        RecoveryPhraseFailure.INVALID_WORDS -> Res.string.recovery_phrase_entry_settings_error
        RecoveryPhraseFailure.MISMATCH -> Res.string.recovery_phrase_entry_settings_mismatch_error
        RecoveryPhraseFailure.UNAVAILABLE -> Res.string.recovery_phrase_entry_settings_unavailable_error
        RecoveryPhraseFailure.NO_PROOF -> Res.string.recovery_phrase_entry_settings_no_proof_error
        RecoveryPhraseFailure.RETRYABLE -> Res.string.recovery_phrase_entry_settings_retry_error
    }

/**
 * Lets a signed-in user enter their recovery phrase again from Settings -- the recovery step in
 * onboarding can be skipped, and a device can also lose its key later (a Keystore reset, a
 * restore). Both leave backup paused with nowhere else to fix it from.
 */
@Composable
fun RecoveryPhraseEntrySettingsScreen(
    onBack: () -> Unit,
    onRecovered: () -> Unit,
    viewModel: RecoveryPhraseEntryViewModel = koinViewModel(),
) = RecoveryPhraseEntrySettingsContent(onBack, onRecovered, viewModel::submit)

@Composable
internal fun RecoveryPhraseEntrySettingsContent(
    onBack: () -> Unit,
    onRecovered: () -> Unit,
    submitRecovery: (List<String>, (Result<Unit>) -> Unit) -> Unit,
    initialWords: List<String> = List(12) { "" },
) {
    var phraseWords by remember { mutableStateOf(initialWords) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val wordCountError = stringResource(Res.string.recovery_phrase_entry_settings_word_count_error)
    val invalidWordsError = stringResource(RecoveryPhraseFailure.INVALID_WORDS.messageResource())
    val mismatchError = stringResource(RecoveryPhraseFailure.MISMATCH.messageResource())
    val unavailableError = stringResource(RecoveryPhraseFailure.UNAVAILABLE.messageResource())
    val noProofError = stringResource(RecoveryPhraseFailure.NO_PROOF.messageResource())
    val retryError = stringResource(RecoveryPhraseFailure.RETRYABLE.messageResource())

    SettingsScaffold(title = stringResource(Res.string.recovery_phrase_entry_settings_title), onBack = onBack) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text(
                    text = stringResource(Res.string.recovery_phrase_entry_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                )

                RecoveryPhraseWordFields(
                    words = phraseWords,
                    enabled = !isSubmitting,
                    onWordChange = { index, newValue ->
                        phraseWords = phraseWords.toMutableList().apply { this[index] = newValue.lowercase().trim() }
                        errorMessage = null
                    },
                )

                errorMessage?.let { message ->
                    Card(
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(Spacing.md),
                        )
                    }
                }

                if (isSubmitting) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
                        CircularProgressIndicator()
                    }
                }

                Button(
                    onClick = {
                        val normalized = phraseWords.map { it.trim().lowercase() }
                        if (normalized.any { it.isBlank() }) {
                            errorMessage = wordCountError
                            return@Button
                        }
                        isSubmitting = true
                        errorMessage = null
                        submitRecovery(normalized) { result ->
                            isSubmitting = false
                            result
                                .onSuccess { onRecovered() }
                                .onFailure {
                                    errorMessage =
                                        when (classifyRecoveryPhraseFailure(it)) {
                                            RecoveryPhraseFailure.INVALID_WORDS -> invalidWordsError
                                            RecoveryPhraseFailure.MISMATCH -> mismatchError
                                            RecoveryPhraseFailure.UNAVAILABLE -> unavailableError
                                            RecoveryPhraseFailure.NO_PROOF -> noProofError
                                            RecoveryPhraseFailure.RETRYABLE -> retryError
                                        }
                                }
                        }
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.recovery_phrase_entry_settings_submit))
                }
            }
        }
    }
}

@Composable
internal fun RecoveryPhraseWordFields(
    words: List<String>,
    enabled: Boolean,
    onWordChange: (Int, String) -> Unit,
) {
    val focusRequesters = remember(words.size) { List(words.size) { FocusRequester() } }
    val focusManager = LocalFocusManager.current
    BoxWithConstraints {
        val columns = if (maxWidth < 480.dp || LocalDensity.current.fontScale >= 1.6f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            words.chunked(columns).forEachIndexed { rowIndex, rowWords ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    rowWords.forEachIndexed { colIndex, word ->
                        val index = rowIndex * columns + colIndex
                        val last = index == words.lastIndex
                        OutlinedTextField(
                            value = word,
                            onValueChange = { onWordChange(index, it) },
                            label = { Text(stringResource(Res.string.recovery_phrase_entry_word_number, index + 1)) },
                            singleLine = true,
                            enabled = enabled,
                            keyboardOptions = KeyboardOptions(imeAction = if (last) ImeAction.Done else ImeAction.Next),
                            keyboardActions =
                                KeyboardActions(
                                    onNext = { focusRequesters.getOrNull(index + 1)?.requestFocus() },
                                    onDone = { focusManager.clearFocus() },
                                ),
                            modifier = Modifier.weight(1f).focusRequester(focusRequesters[index]).testTag("recovery_word_$index"),
                        )
                    }
                }
            }
        }
    }
}
