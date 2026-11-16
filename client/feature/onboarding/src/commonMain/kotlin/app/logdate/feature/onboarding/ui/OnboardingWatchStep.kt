@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.logdate.feature.core.settings.ui.watch.WatchConnectionState
import app.logdate.feature.core.settings.ui.watch.WatchStatus
import app.logdate.feature.core.settings.ui.watch.WatchUnknownActions
import app.logdate.feature.core.settings.ui.watch.v2.WatchSetupState
import app.logdate.ui.step.StepHeroIcon
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepScaffold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.watch_setup_add
import logdate.client.feature.onboarding.generated.resources.watch_setup_check
import logdate.client.feature.onboarding.generated.resources.watch_setup_check_known
import logdate.client.feature.onboarding.generated.resources.watch_setup_confirm
import logdate.client.feature.onboarding.generated.resources.watch_setup_continue
import logdate.client.feature.onboarding.generated.resources.watch_setup_description
import logdate.client.feature.onboarding.generated.resources.watch_setup_failed
import logdate.client.feature.onboarding.generated.resources.watch_setup_linking
import logdate.client.feature.onboarding.generated.resources.watch_setup_opening
import logdate.client.feature.onboarding.generated.resources.watch_setup_ready
import logdate.client.feature.onboarding.generated.resources.watch_setup_save_failed
import logdate.client.feature.onboarding.generated.resources.watch_setup_skip
import logdate.client.feature.onboarding.generated.resources.watch_setup_title
import logdate.client.feature.onboarding.generated.resources.watch_setup_waiting
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnboardingWatchStep(
    onBack: () -> Unit,
    onNext: () -> Unit,
    viewModel: OnboardingViewModel = koinViewModel(),
) {
    val connection by remember(viewModel) { viewModel.watchSetup.connection() }.collectAsState(WatchConnectionState.Loading)
    val scope = rememberCoroutineScope()
    val currentOnNext by rememberUpdatedState(onNext)
    val actions = remember(viewModel) { WatchOnboardingActions(viewModel, scope) { currentOnNext() } }
    LaunchedEffect(connection) {
        if ((actions.state == null || actions.state is WatchSetupState.CheckOnWatch) &&
            (connection is WatchConnectionState.Connected || connection is WatchConnectionState.OutOfRange)
        ) {
            actions.prepare()
        }
    }
    OnboardingWatchStepContent(
        connection,
        actions.state,
        actions.busy,
        actions.saveFailed,
        onBack,
        onPrimary = actions::primary,
        onSkip = actions::skip,
        saving = actions.saving,
        onConfirmMissing = actions::confirmMissing,
    )
}

internal class WatchOnboardingActions(
    private val viewModel: OnboardingViewModel,
    private val scope: CoroutineScope,
    private val onNext: () -> Unit,
) {
    var state by mutableStateOf<WatchSetupState?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var saveFailed by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    private var operation = 0L
    private var job: Job? = null

    fun prepare() = launch { setup(advance = false) }

    fun primary() =
        launch {
            if (state == WatchSetupState.Ready) finish() else setup(advance = true)
        }

    fun confirmMissing(nodeId: String) =
        launch {
            viewModel.watchSetup.addAfterMissingConfirmation(nodeId).collect { state = it }
            if (state == WatchSetupState.Ready) finish()
        }

    fun skip() {
        if (saving) return
        job?.cancel()
        saving = true
        start { finish() }
    }

    private fun launch(action: suspend () -> Unit) {
        if (!busy) start(action)
    }

    private fun start(action: suspend () -> Unit) {
        val current = ++operation
        busy = true
        saveFailed = false
        job =
            scope.launch {
                try {
                    action()
                } finally {
                    if (current == operation) {
                        busy = false
                        saving = false
                    }
                }
            }
    }

    private suspend fun setup(advance: Boolean) {
        viewModel.watchSetup.addToWatch().collect { state = it }
        if (advance && state == WatchSetupState.Ready) finish()
    }

    private suspend fun finish() {
        saving = true
        saveFailed = viewModel.markWatchHandled().isFailure
        if (!saveFailed) onNext()
    }
}

@Composable
internal fun OnboardingWatchStepContent(
    connection: WatchConnectionState,
    state: WatchSetupState?,
    busy: Boolean,
    saveFailed: Boolean,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
    saving: Boolean = false,
    onConfirmMissing: (String) -> Unit = {},
) {
    val installed = connection is WatchConnectionState.Connected || connection is WatchConnectionState.OutOfRange
    val message =
        when (state) {
            WatchSetupState.Ready -> {
                Res.string.watch_setup_ready
            }

            WatchSetupState.ConfirmingOnPhone -> {
                Res.string.watch_setup_confirm
            }

            WatchSetupState.OpeningPlayOnWatch -> {
                Res.string.watch_setup_opening
            }

            WatchSetupState.WaitingForInstall -> {
                Res.string.watch_setup_waiting
            }

            WatchSetupState.SettingUp -> {
                Res.string.watch_setup_linking
            }

            WatchSetupState.Failed -> {
                Res.string.watch_setup_failed
            }

            is WatchSetupState.CheckOnWatch -> {
                if (state.knownInstalled) {
                    Res.string.watch_setup_check_known
                } else {
                    Res.string.watch_setup_check
                }
            }

            null -> {
                Res.string.watch_setup_description
            }
        }
    StepScaffold(
        title = stringResource(Res.string.watch_setup_title),
        supportingText = stringResource(message),
        onBack = onBack,
        hero = { StepHeroIcon(Icons.Rounded.Watch) },
        actions = {
            val checking = state as? WatchSetupState.CheckOnWatch
            if (checking != null) {
                WatchUnknownActions(
                    WatchStatus.Unknown(checking.watchName, checking.nodeId, checking.knownInstalled),
                    onPrimary,
                    onConfirmMissing,
                )
            } else {
                StepPrimaryButton(
                    onClick = onPrimary,
                    text = stringResource(if (installed) Res.string.watch_setup_continue else Res.string.watch_setup_add),
                    enabled = !busy,
                    busy = busy,
                )
            }
            TextButton(onClick = onSkip, enabled = !saving) { Text(stringResource(Res.string.watch_setup_skip)) }
        },
        content = { if (saveFailed) Text(stringResource(Res.string.watch_setup_save_failed), color = MaterialTheme.colorScheme.error) },
    )
}
