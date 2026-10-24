@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:no-wildcard-imports", "ktlint:standard:max-line-length")

package app.logdate.feature.onboarding.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.ui.adaptive.FoldableBookLayout
import app.logdate.ui.adaptive.FoldableTabletopLayout
import app.logdate.ui.platform.plainTextClipEntry
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.*
import logdate.client.feature.onboarding.generated.resources.Res
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the new recovery phrase once, with Copy and Hide, so the person can save a copy in case they
 * lose every device. Saving it is optional: nothing asks them to type it back, and [onLater] moves on
 * without it, since the phrase stays readable from Settings.
 *
 * @param onPhraseContinue the person says they saved a copy of [words]
 */
@Composable
fun RecoveryPhraseSetupScreen(
    words: List<String>,
    isLoading: Boolean,
    errorMessage: StringResource?,
    onRetry: () -> Unit,
    onPhraseContinue: (List<String>) -> Unit,
    onLater: () -> Unit = {},
) {
    when {
        isLoading -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(Res.string.deriving_encryption_keys))
            }
        }

        errorMessage != null -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.your_recovery_phrase),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(errorMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.recovery_setup_retry))
                }
            }
        }

        else -> {
            RecoveryPhraseDisplayContent(words = words, onSaved = { onPhraseContinue(words) }, onLater = onLater)
        }
    }
}

@Composable
private fun RecoveryPhraseDisplayContent(
    words: List<String>,
    onSaved: () -> Unit,
    onLater: () -> Unit,
) {
    var hidden by remember(words) { mutableStateOf(false) }
    val phrase: @Composable (Modifier) -> Unit = { modifier ->
        RecoveryPhrasePane(words = words, hidden = hidden, onHiddenChange = {
            hidden = it
        }, modifier = modifier.verticalScroll(rememberScrollState()))
    }
    val actions: @Composable (Modifier) -> Unit = { modifier ->
        RecoveryPhraseActions(onSaved = onSaved, onLater = onLater, modifier = modifier)
    }

    FoldableTabletopLayout(
        modifier = Modifier.fillMaxSize(),
        minPaneHeight = 260.dp,
        topPane = { phrase(Modifier.fillMaxSize()) },
        bottomPane = { actions(Modifier.fillMaxSize()) },
        standardContent = {
            FoldableBookLayout(
                modifier = Modifier.fillMaxSize(),
                minPaneWidth = 320.dp,
                startPane = { phrase(Modifier.fillMaxSize()) },
                endPane = { actions(Modifier.fillMaxSize()) },
                standardContent = {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        RecoveryPhrasePane(
                            words = words,
                            hidden = hidden,
                            onHiddenChange = { hidden = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        actions(Modifier.fillMaxWidth())
                    }
                },
            )
        },
    )
}

@Composable
private fun RecoveryPhrasePane(
    words: List<String>,
    hidden: Boolean,
    onHiddenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.your_recovery_phrase),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(Res.string.recovery_phrase_setup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hidden) {
            FilledTonalButton(onClick = { onHiddenChange(false) }) {
                Icon(Icons.Outlined.Visibility, contentDescription = null)
                Text(stringResource(Res.string.recovery_phrase_setup_show), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            RecoveryPhraseGrid(words = words)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { clipboard.setClipEntry(plainTextClipEntry(words.joinToString(" "), sensitive = true)) } },
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Text(stringResource(Res.string.recovery_phrase_setup_copy), modifier = Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = { onHiddenChange(true) }) {
                    Icon(Icons.Outlined.VisibilityOff, contentDescription = null)
                    Text(stringResource(Res.string.recovery_phrase_setup_hide), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        WarningCard(text = stringResource(Res.string.warning_recovery_phrase_never_share))
        Text(
            text = stringResource(Res.string.warning_recovery_phrase_store_securely),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecoveryPhraseActions(
    onSaved: () -> Unit,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onSaved, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.recovery_phrase_setup_saved))
        }
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.recovery_phrase_setup_later))
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

/**
 * The phrase itself, as a two-column grid.
 *
 * A single column of twelve rows pushed the confirmation and the primary action off-screen and
 * made the phrase harder to transcribe; pairing the words halves the height and keeps the
 * numbering scannable.
 */
@Composable
private fun RecoveryPhraseGrid(words: List<String>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            words.chunked(2).forEachIndexed { rowIndex, pair ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    pair.forEachIndexed { columnIndex, word ->
                        RecoveryPhraseWord(
                            position = rowIndex * 2 + columnIndex + 1,
                            word = word,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryPhraseWord(
    position: Int,
    word: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = position.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = word,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}
