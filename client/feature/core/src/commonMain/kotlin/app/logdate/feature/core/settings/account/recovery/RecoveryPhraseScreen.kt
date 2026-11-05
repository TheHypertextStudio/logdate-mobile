@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.recovery

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.ui.common.SettingsAction
import app.logdate.ui.common.SettingsActionStatus
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.platform.PlatformPredictiveBackHandler
import app.logdate.ui.platform.plainTextClipEntry
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.recovery_phrase_about
import logdate.client.feature.core.generated.resources.recovery_phrase_auth_unavailable
import logdate.client.feature.core.generated.resources.recovery_phrase_checking
import logdate.client.feature.core.generated.resources.recovery_phrase_confirming
import logdate.client.feature.core.generated.resources.recovery_phrase_copied
import logdate.client.feature.core.generated.resources.recovery_phrase_copy
import logdate.client.feature.core.generated.resources.recovery_phrase_copy_failed
import logdate.client.feature.core.generated.resources.recovery_phrase_copying
import logdate.client.feature.core.generated.resources.recovery_phrase_description
import logdate.client.feature.core.generated.resources.recovery_phrase_enter
import logdate.client.feature.core.generated.resources.recovery_phrase_hidden
import logdate.client.feature.core.generated.resources.recovery_phrase_hide
import logdate.client.feature.core.generated.resources.recovery_phrase_keep_private
import logdate.client.feature.core.generated.resources.recovery_phrase_missing_body
import logdate.client.feature.core.generated.resources.recovery_phrase_missing_title
import logdate.client.feature.core.generated.resources.recovery_phrase_prompt_subtitle
import logdate.client.feature.core.generated.resources.recovery_phrase_prompt_title
import logdate.client.feature.core.generated.resources.recovery_phrase_retry
import logdate.client.feature.core.generated.resources.recovery_phrase_show
import logdate.client.feature.core.generated.resources.recovery_phrase_title
import logdate.client.feature.core.generated.resources.recovery_phrase_unreadable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RecoveryPhraseScreen(
    onBack: () -> Unit,
    onEnterPhrase: () -> Unit,
    viewModel: RecoveryPhraseViewModel = koinViewModel(),
) {
    // Keep this cheap privacy snapshot observed through ON_STOP so it cannot retain secret text.
    val state by viewModel.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val accountRecovery by viewModel.accountRecovery.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var routeActive by remember { mutableStateOf(true) }
    val leave = {
        routeActive = false
        viewModel.onScreenLeft()
    }
    PlatformPredictiveBackHandler(
        onProgress = {
            if (routeActive) {
                routeActive = false
                viewModel.hide()
            }
        },
        onBack = {
            leave()
            onBack()
        },
        onCancel = { routeActive = true },
    )
    val prompt =
        RevealPrompt(
            title = stringResource(Res.string.recovery_phrase_prompt_title),
            subtitle = stringResource(Res.string.recovery_phrase_prompt_subtitle),
            description = null,
        )
    DisposableEffect(viewModel, lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        viewModel.onScreenActive()
                        started = true
                    }
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                        started = false
                        viewModel.onScreenInactive()
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onScreenLeft()
        }
    }
    RecoveryPhraseContent(
        state =
            if ((!started || !routeActive) && state is RecoveryPhraseUiState.Available) {
                (state as RecoveryPhraseUiState.Available).copy(reveal = PhraseRevealState.Hidden)
            } else {
                state
            },
        onBack = {
            leave()
            onBack()
        },
        onReveal = { if (started && routeActive) viewModel.reveal(prompt) },
        onHide = viewModel::hide,
        onEnterPhrase = {
            leave()
            onEnterPhrase()
        },
        onRetry = viewModel::check,
        accountRecovery = accountRecovery,
        onFinishAccountRecovery = viewModel::finishAccountRecoverySetup,
        onRetryAccountRecovery = viewModel::checkAccountRecovery,
    )
}

@Composable
fun RecoveryPhraseContent(
    state: RecoveryPhraseUiState,
    onBack: () -> Unit,
    onReveal: () -> Unit,
    onHide: () -> Unit,
    onEnterPhrase: () -> Unit,
    onRetry: () -> Unit = {},
    onCopy: (suspend (List<String>) -> Unit)? = null,
    accountRecovery: AccountRecoveryUiState? = null,
    onFinishAccountRecovery: () -> Unit = {},
    onRetryAccountRecovery: () -> Unit = {},
) {
    val reduceMotion by rememberSystemReduceMotion()
    var showInfo by remember { mutableStateOf(false) }
    val available = state as? RecoveryPhraseUiState.Available
    val revealed = if (showInfo) null else available?.reveal as? PhraseRevealState.Revealed
    val aboutLabel = stringResource(Res.string.recovery_phrase_about)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactHeight = maxHeight < 480.dp
        SettingsScaffold(
            title = stringResource(Res.string.recovery_phrase_title),
            onBack = onBack,
            compactHeader = compactHeight,
            actions = {
                if (revealed != null) {
                    TextButton(onClick = onHide) {
                        Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(Res.string.recovery_phrase_hide), modifier = Modifier.padding(start = Spacing.sm))
                    }
                }
            },
        ) {
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = Spacing.lg,
                            ).animateContentSize(tween(if (reduceMotion) 0 else 200)),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight && revealed == null) Spacing.sm else Spacing.lg),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Text(
                            stringResource(Res.string.recovery_phrase_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = {
                                if (available != null && available.reveal != PhraseRevealState.Hidden) onHide()
                                showInfo = true
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(aboutLabel)
                        }
                    }
                    when (state) {
                        RecoveryPhraseUiState.Checking -> {
                            RecoveryPhrasePrivacyVisual(
                                modifier =
                                    Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(vertical = if (compactHeight) Spacing.sm else Spacing.lg),
                                scale = if (compactHeight) 0.5f else 1f,
                            )
                            SettingsAction(
                                label = stringResource(Res.string.recovery_phrase_show),
                                busyLabel = stringResource(Res.string.recovery_phrase_checking),
                                status = SettingsActionStatus.BUSY,
                                onClick = {},
                            )
                        }
                        RecoveryPhraseUiState.NotOnThisDevice -> {
                            Text(stringResource(Res.string.recovery_phrase_missing_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(Res.string.recovery_phrase_missing_body), style = MaterialTheme.typography.bodyMedium)
                            SettingsAction(label = stringResource(Res.string.recovery_phrase_enter), onClick = onEnterPhrase)
                        }
                        RecoveryPhraseUiState.Unreadable ->
                            SettingsAction(
                                label = stringResource(Res.string.recovery_phrase_retry),
                                status = SettingsActionStatus.FAILED,
                                error = stringResource(Res.string.recovery_phrase_unreadable),
                                onClick = onRetry,
                            )
                        is RecoveryPhraseUiState.Available -> {
                            if (revealed == null) {
                                if (compactHeight) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterHorizontally),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RecoveryPhrasePrivacyVisual(scale = 0.5f)
                                        Text(
                                            stringResource(Res.string.recovery_phrase_hidden),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                                    ) {
                                        RecoveryPhrasePrivacyVisual()
                                        Text(
                                            stringResource(Res.string.recovery_phrase_hidden),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                RevealAction(state.reveal, onReveal)
                            } else {
                                Surface(
                                    modifier = Modifier.testTag("recovery-phrase-content"),
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                                    ) {
                                        PhraseGrid(revealed.words, Modifier.fillMaxWidth())
                                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                            Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text(
                                                stringResource(Res.string.recovery_phrase_keep_private),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                CopyPhraseAction(revealed.words, onCopy)
                            }
                        }
                    }
                }
            }
            accountRecovery?.let { recovery ->
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg)) {
                        AccountRecoveryNotice(recovery, onFinishAccountRecovery, onRetryAccountRecovery)
                    }
                }
            }
        }
    }
    if (showInfo) RecoveryPhraseInfoSheet(onDismiss = { showInfo = false })
}

@Composable
private fun RevealAction(
    state: PhraseRevealState,
    onReveal: () -> Unit,
) {
    val busy = state == PhraseRevealState.Authenticating || state == PhraseRevealState.Reading
    SettingsAction(
        label = stringResource(Res.string.recovery_phrase_show),
        busyLabel = stringResource(Res.string.recovery_phrase_confirming),
        status =
            when {
                busy -> SettingsActionStatus.BUSY
                state is PhraseRevealState.Failed -> SettingsActionStatus.FAILED
                else -> SettingsActionStatus.IDLE
            },
        error =
            (state as? PhraseRevealState.Failed)?.let {
                stringResource(
                    if (it.reason ==
                        RevealFailure.UNREADABLE
                    ) {
                        Res.string.recovery_phrase_unreadable
                    } else {
                        Res.string.recovery_phrase_auth_unavailable
                    },
                )
            },
        icon = Icons.Outlined.Visibility,
        onClick = onReveal,
    )
}

@Composable
private fun CopyPhraseAction(
    words: List<String>,
    onCopy: (suspend (List<String>) -> Unit)?,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var status by remember(words) { mutableStateOf(SettingsActionStatus.IDLE) }
    LaunchedEffect(status) {
        if (status == SettingsActionStatus.SUCCEEDED) {
            delay(2500)
            status = SettingsActionStatus.IDLE
        }
    }
    SettingsAction(
        label = stringResource(Res.string.recovery_phrase_copy),
        busyLabel = stringResource(Res.string.recovery_phrase_copying),
        successLabel = stringResource(Res.string.recovery_phrase_copied),
        error = stringResource(Res.string.recovery_phrase_copy_failed),
        status = status,
        icon = Icons.Outlined.ContentCopy,
        onClick = {
            if (status != SettingsActionStatus.BUSY) {
                status = SettingsActionStatus.BUSY
                scope.launch {
                    try {
                        if (onCopy !=
                            null
                        ) {
                            onCopy(words)
                        } else {
                            clipboard.setClipEntry(plainTextClipEntry(words.joinToString(" "), sensitive = true))
                        }
                        status = SettingsActionStatus.SUCCEEDED
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Napier.w("Could not copy the recovery phrase")
                        status = SettingsActionStatus.FAILED
                    }
                }
            }
        },
    )
}

/** Removed directly on Hide. Only the incoming words animate; no outgoing secret is retained. */
@Composable
private fun PhraseGrid(
    words: List<String>,
    modifier: Modifier = Modifier,
) {
    val reduceMotion by rememberSystemReduceMotion()
    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered || reduceMotion) 1f else 0f, tween(if (reduceMotion) 0 else 160), label = "Reveal phrase")
    LaunchedEffect(Unit) { entered = true }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val wordStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
    val numberStyle = MaterialTheme.typography.labelMedium
    val numberWidth =
        with(density) {
            (1..words.size).maxOf { textMeasurer.measure(it.toString(), numberStyle, maxLines = 1).size.width }.toDp()
        }.coerceAtLeast(24.dp)
    val wordWidth =
        with(density) {
            words.maxOf { textMeasurer.measure(it, wordStyle, maxLines = 1).size.width }.toDp()
        }
    BoxWithConstraints(modifier.graphicsLayer { this.alpha = alpha }) {
        val columnWidth = numberWidth + Spacing.sm + wordWidth
        val columns = if (maxWidth >= columnWidth * 2 + Spacing.lg) 2 else 1
        val rows = (words.size + columns - 1) / columns
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
            words.chunked(rows.coerceAtLeast(1)).forEachIndexed { column, columnWords ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    columnWords.forEachIndexed { row, word ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = (column * rows + row + 1).toString(),
                                style = numberStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.width(numberWidth),
                            )
                            Text(
                                word,
                                style = wordStyle,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(start = Spacing.sm),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun RecoveryPhraseRevealedPreview() {
    RecoveryPhraseContent(
        state = RecoveryPhraseUiState.Available(PhraseRevealState.Revealed(List(12) { "word${it + 1}" })),
        onBack = {},
        onReveal = {},
        onHide = {},
        onEnterPhrase = {},
    )
}
