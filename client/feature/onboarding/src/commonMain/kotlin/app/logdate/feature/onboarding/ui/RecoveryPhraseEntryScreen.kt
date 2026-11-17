@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports")

package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val RecoveryPhraseWordPattern = Regex("^[a-z]+$")

@Composable
fun RecoveryPhraseEntryScreen(
    onRecoverPhrase: suspend (List<String>) -> Result<Unit>,
    onRecovered: () -> Unit,
    onError: (String) -> Unit = {},
    onSkip: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    var phraseWords by rememberSaveable { mutableStateOf(List(12) { "" }) }
    var isRecovering by remember { mutableStateOf(false) }
    var errorMessageRes by remember { mutableStateOf<StringResource?>(null) }

    FoldableTabletopLayout(
        modifier = Modifier.fillMaxSize(),
        minPaneHeight = 260.dp,
        topPane = {
            RecoveryPhraseEntryTopPane(
                phraseWords = phraseWords,
                errorMessageRes = errorMessageRes,
                isRecovering = isRecovering,
                onPhraseWordsChange = { phraseWords = it },
                onClearError = { errorMessageRes = null },
                modifier = Modifier.fillMaxSize(),
            )
        },
        bottomPane = {
            RecoveryPhraseEntryBottomPane(
                isRecovering = isRecovering,
                onRecoverPhrase = onRecoverPhrase,
                onRecovered = onRecovered,
                onError = onError,
                onSkip = onSkip,
                phraseWords = phraseWords,
                onIsRecoveringChange = { isRecovering = it },
                onErrorMessageChange = { errorMessageRes = it },
                modifier = Modifier.fillMaxSize(),
            )
        },
        standardContent = {
            FoldableBookLayout(
                modifier = Modifier.fillMaxSize(),
                minPaneWidth = 320.dp,
                startPane = {
                    RecoveryPhraseEntryTopPane(
                        phraseWords = phraseWords,
                        errorMessageRes = errorMessageRes,
                        isRecovering = isRecovering,
                        onPhraseWordsChange = { phraseWords = it },
                        onClearError = { errorMessageRes = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                endPane = {
                    RecoveryPhraseEntryBottomPane(
                        isRecovering = isRecovering,
                        onRecoverPhrase = onRecoverPhrase,
                        onRecovered = onRecovered,
                        onError = onError,
                        onSkip = onSkip,
                        phraseWords = phraseWords,
                        onIsRecoveringChange = { isRecovering = it },
                        onErrorMessageChange = { errorMessageRes = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
                standardContent = {
                    RecoveryPhraseEntryCompactContent(
                        phraseWords = phraseWords,
                        onPhraseWordsChange = { phraseWords = it },
                        isRecovering = isRecovering,
                        onIsRecoveringChange = { isRecovering = it },
                        errorMessageRes = errorMessageRes,
                        onErrorMessageChange = { errorMessageRes = it },
                        onRecoverPhrase = onRecoverPhrase,
                        onRecovered = onRecovered,
                        onError = onError,
                        onSkip = onSkip,
                    )
                },
            )
        },
    )
}

@Composable
private fun RecoveryPhraseEntryTopPane(
    phraseWords: List<String>,
    errorMessageRes: StringResource?,
    isRecovering: Boolean,
    onPhraseWordsChange: (List<String>) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.enter_recovery_phrase),
            style = MaterialTheme.typography.headlineMedium,
        )

        Text(
            text = stringResource(Res.string.enter_your_12_word_recovery_phrase_to_restore_your_encryption_keys_and_decrypt_your_data),
            style = MaterialTheme.typography.bodyMedium,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            phraseWords.chunked(2).forEachIndexed { rowIndex, rowWords ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowWords.forEachIndexed { colIndex, word ->
                        val index = rowIndex * 2 + colIndex
                        OutlinedTextField(
                            value = word,
                            onValueChange = { newValue ->
                                val updated =
                                    phraseWords.toMutableList().apply {
                                        this[index] = newValue.lowercase().trim()
                                    }
                                onPhraseWordsChange(updated)
                                onClearError()
                            },
                            label = { Text((index + 1).toString()) },
                            singleLine = true,
                            enabled = !isRecovering,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        errorMessageRes?.let { messageRes ->
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (isRecovering) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(Res.string.deriving_encryption_keys))
            }
        }
    }
}

@Composable
private fun RecoveryPhraseEntryBottomPane(
    isRecovering: Boolean,
    onRecoverPhrase: suspend (List<String>) -> Result<Unit>,
    onRecovered: () -> Unit,
    onError: (String) -> Unit,
    onSkip: (() -> Unit)?,
    phraseWords: List<String>,
    onIsRecoveringChange: (Boolean) -> Unit,
    onErrorMessageChange: (StringResource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier =
            modifier
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                val normalizedWords = phraseWords.map { it.trim().lowercase() }
                val validationError = validateRecoveryPhrase(normalizedWords)
                if (validationError != null) {
                    onErrorMessageChange(validationError)
                    return@Button
                }

                coroutineScope.launch {
                    onIsRecoveringChange(true)
                    onErrorMessageChange(null)
                    Napier.d("Attempting recovery with entered recovery phrase")

                    onRecoverPhrase(normalizedWords)
                        .onSuccess { onRecovered() }
                        .onFailure { throwable ->
                            val reason =
                                throwable.message?.takeIf { it.isNotBlank() }
                                    ?: "Recovery flow failed"
                            Napier.e("Recovery phrase flow failed: $reason", throwable)
                            onErrorMessageChange(Res.string.recovery_error_unexpected)
                            onError(reason)
                        }

                    onIsRecoveringChange(false)
                }
            },
            enabled = !isRecovering,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.recover_account))
        }

        // The recovery phrase restores the AT Protocol signing key. Entries sync without it, so
        // demanding it here left someone signing in on a second device with no way forward.
        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                enabled = !isRecovering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.recovery_phrase_entry_skip))
            }
            Text(
                text = stringResource(Res.string.recovery_phrase_entry_skip_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecoveryPhraseEntryCompactContent(
    phraseWords: List<String>,
    onPhraseWordsChange: (List<String>) -> Unit,
    isRecovering: Boolean,
    onIsRecoveringChange: (Boolean) -> Unit,
    errorMessageRes: StringResource?,
    onErrorMessageChange: (StringResource?) -> Unit,
    onRecoverPhrase: suspend (List<String>) -> Result<Unit>,
    onRecovered: () -> Unit,
    onError: (String) -> Unit,
    onSkip: (() -> Unit)?,
) {
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.enter_recovery_phrase),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(Res.string.enter_your_12_word_recovery_phrase_to_restore_your_encryption_keys_and_decrypt_your_data),
            style = MaterialTheme.typography.bodyMedium,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            phraseWords.chunked(2).forEachIndexed { rowIndex, rowWords ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowWords.forEachIndexed { colIndex, word ->
                        val index = rowIndex * 2 + colIndex
                        OutlinedTextField(
                            value = word,
                            onValueChange = { newValue ->
                                val updated =
                                    phraseWords.toMutableList().apply {
                                        this[index] = newValue.lowercase().trim()
                                    }
                                onPhraseWordsChange(updated)
                                onErrorMessageChange(null)
                            },
                            label = { Text((index + 1).toString()) },
                            singleLine = true,
                            enabled = !isRecovering,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        errorMessageRes?.let { messageRes ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (isRecovering) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(Res.string.deriving_encryption_keys))
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = {
                val normalizedWords = phraseWords.map { it.trim().lowercase() }
                val validationError = validateRecoveryPhrase(normalizedWords)
                if (validationError != null) {
                    onErrorMessageChange(validationError)
                    return@Button
                }
                coroutineScope.launch {
                    onIsRecoveringChange(true)
                    onErrorMessageChange(null)
                    Napier.d("Attempting recovery with entered recovery phrase")
                    onRecoverPhrase(normalizedWords)
                        .onSuccess { onRecovered() }
                        .onFailure { throwable ->
                            val reason =
                                throwable.message?.takeIf { it.isNotBlank() }
                                    ?: "Recovery flow failed"
                            Napier.e("Recovery phrase flow failed: $reason", throwable)
                            onErrorMessageChange(Res.string.recovery_error_unexpected)
                            onError(reason)
                        }
                    onIsRecoveringChange(false)
                }
            },
            enabled = !isRecovering,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.recover_account))
        }
        if (onSkip != null) {
            TextButton(
                onClick = onSkip,
                enabled = !isRecovering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.recovery_phrase_entry_skip))
            }
            Text(
                text = stringResource(Res.string.recovery_phrase_entry_skip_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun validateRecoveryPhrase(words: List<String>): StringResource? {
    if (words.any { it.isBlank() }) {
        return Res.string.recovery_error_missing_words
    }
    if (words.any { !RecoveryPhraseWordPattern.matches(it) }) {
        return Res.string.recovery_error_invalid_word_format
    }
    return null
}
