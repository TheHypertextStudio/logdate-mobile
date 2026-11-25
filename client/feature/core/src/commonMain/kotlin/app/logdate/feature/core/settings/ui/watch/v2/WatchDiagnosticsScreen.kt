@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.watch.WatchSystemSetupButton
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.applyScreenStyles
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepSecondaryButton
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_diagnostics_prerequisite
import logdate.client.feature.core.generated.resources.watch_setup_complete_action
import logdate.client.feature.core.generated.resources.watch_setup_pair_title
import logdate.client.feature.core.generated.resources.watch_v2_check_again
import logdate.client.feature.core.generated.resources.watch_v2_check_app
import logdate.client.feature.core.generated.resources.watch_v2_check_association
import logdate.client.feature.core.generated.resources.watch_v2_check_error
import logdate.client.feature.core.generated.resources.watch_v2_check_error_title
import logdate.client.feature.core.generated.resources.watch_v2_check_failed
import logdate.client.feature.core.generated.resources.watch_v2_check_fixable
import logdate.client.feature.core.generated.resources.watch_v2_check_link
import logdate.client.feature.core.generated.resources.watch_v2_check_notifications
import logdate.client.feature.core.generated.resources.watch_v2_check_passed
import logdate.client.feature.core.generated.resources.watch_v2_check_ping
import logdate.client.feature.core.generated.resources.watch_v2_check_protocol
import logdate.client.feature.core.generated.resources.watch_v2_check_running
import logdate.client.feature.core.generated.resources.watch_v2_check_sync
import logdate.client.feature.core.generated.resources.watch_v2_check_watch
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_all_good
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_all_passed_summary
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_passed_count
import logdate.client.feature.core.generated.resources.watch_v2_fix_install
import logdate.client.feature.core.generated.resources.watch_v2_fix_open
import logdate.client.feature.core.generated.resources.watch_v2_fix_pair
import logdate.client.feature.core.generated.resources.watch_v2_fix_sync
import logdate.client.feature.core.generated.resources.watch_v2_fix_update
import logdate.client.feature.core.generated.resources.watch_v2_notifications_fix
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
fun WatchCheckConnectionScreen(
    onBack: () -> Unit,
    model: WatchDiagnosticsViewModel = koinViewModel(),
) {
    val state by model.state.collectAsState()
    val busy by model.busy.collectAsState()
    WatchDiagnosticsContent(state, busy, model::check, model::fix, onBack)
}

@Composable
fun WatchDiagnosticsContent(
    state: WatchDiagnosticsState,
    busy: Boolean,
    onCheck: () -> Unit,
    onFix: (WatchDiagnosticFix) -> Unit,
    onBack: () -> Unit,
) {
    when (val current = state) {
        WatchDiagnosticsState.Checking -> {
            DiagnosticsStepHost(onBack = onBack) {
                WatchFlowStepLayout(
                    title = stringResource(Res.string.watch_v2_check_running),
                    description = null,
                    modifier = Modifier.fillMaxSize(),
                    picture = { SearchingPicture(it) },
                ) {}
            }
        }

        WatchDiagnosticsState.NeedsWatch -> {
            DiagnosticsStepHost(onBack = onBack) {
                WatchFlowStepLayout(
                    title = stringResource(Res.string.watch_setup_pair_title),
                    description = stringResource(Res.string.watch_diagnostics_prerequisite),
                    modifier = Modifier.fillMaxSize(),
                    picture = { IntroPicture(it) },
                ) {
                    WatchSystemSetupButton(prominent = true)
                    StepSecondaryButton(
                        text = stringResource(Res.string.watch_v2_check_again),
                        onClick = onCheck,
                        enabled = !busy,
                        quiet = true,
                    )
                }
            }
        }

        WatchDiagnosticsState.Failed -> {
            DiagnosticsStepHost(onBack = onBack) {
                WatchFlowStepLayout(
                    title = stringResource(Res.string.watch_v2_check_error_title),
                    description = stringResource(Res.string.watch_v2_check_error),
                    modifier = Modifier.fillMaxSize(),
                    announce = true,
                    picture = {
                        StoppedPicture(
                            side = it,
                            shape = MaterialShapes.SoftBurst.toShape(),
                            tone = WatchSetupTone.PROBLEM,
                            symbol = Icons.Rounded.WatchOff,
                            badge = Icons.Rounded.PriorityHigh,
                        )
                    },
                ) {
                    StepPrimaryButton(
                        text = stringResource(Res.string.watch_v2_check_again),
                        onClick = onCheck,
                        enabled = !busy,
                        busy = busy,
                    )
                }
            }
        }

        is WatchDiagnosticsState.Ready -> {
            val attentionSteps = current.steps.filter { it.outcome != WatchDiagnosticOutcome.PASSED }
            val passedSteps = current.steps.filter { it.outcome == WatchDiagnosticOutcome.PASSED }
            if (attentionSteps.isEmpty()) {
                DiagnosticsStepHost(onBack = onBack) {
                    WatchFlowStepLayout(
                        title = stringResource(Res.string.watch_v2_diagnostics_all_good),
                        description = stringResource(Res.string.watch_v2_diagnostics_all_passed_summary, current.steps.size),
                        modifier = Modifier.fillMaxSize(),
                        announce = true,
                        picture = { ReadyPicture(it) },
                    ) {
                        StepPrimaryButton(
                            text = stringResource(Res.string.watch_setup_complete_action),
                            onClick = onBack,
                        )
                        StepSecondaryButton(
                            text = stringResource(Res.string.watch_v2_check_again),
                            onClick = onCheck,
                            enabled = !busy,
                            quiet = true,
                        )
                    }
                }
            } else {
                var showPassed by remember { mutableStateOf(false) }
                SettingsScaffold(
                    title = stringResource(Res.string.watch_v2_diagnostics),
                    onBack = onBack,
                    actions = {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = Spacing.md).size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(onClick = onCheck) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.watch_v2_check_again),
                                )
                            }
                        }
                    },
                ) {
                    item {
                        MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                            attentionSteps.forEach { step ->
                                WatchDiagnosticRow(step = step, busy = busy, onFix = onFix)
                            }
                        }
                    }
                    if (passedSteps.isNotEmpty()) {
                        item {
                            MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                                SettingsRow(
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            pluralStringResource(
                                                Res.plurals.watch_v2_diagnostics_passed_count,
                                                passedSteps.size,
                                                passedSteps.size,
                                            ),
                                        )
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = if (showPassed) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                            contentDescription = null,
                                        )
                                    },
                                    modifier =
                                        Modifier.clickable(role = Role.Button) {
                                            showPassed = !showPassed
                                        },
                                )
                                if (showPassed) {
                                    passedSteps.forEach { step ->
                                        WatchDiagnosticRow(step = step, busy = busy, onFix = onFix)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsStepHost(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().applyScreenStyles(),
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(UiRes.string.common_back))
                    }
                },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            content()
        }
    }
}

@Composable
private fun WatchDiagnosticRow(
    step: WatchDiagnosticStep,
    busy: Boolean,
    onFix: (WatchDiagnosticFix) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.6f
    val statusText =
        if (step.check == WatchDiagnosticCheck.NOTIFICATIONS && step.outcome != WatchDiagnosticOutcome.PASSED) {
            stringResource(Res.string.watch_v2_notifications_fix)
        } else {
            stringResource(
                when (step.outcome) {
                    WatchDiagnosticOutcome.PASSED -> Res.string.watch_v2_check_passed
                    WatchDiagnosticOutcome.FIXABLE -> Res.string.watch_v2_check_fixable
                    WatchDiagnosticOutcome.FAILED -> Res.string.watch_v2_check_failed
                },
            )
        }
    val fix = step.fix
    SettingsRow(
        enabled = !busy,
        leadingContent = { DiagnosticStatusBadge(step.outcome) },
        headlineContent = { Text(stringResource(step.check.title())) },
        supportingContent = {
            if (largeText && fix != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(statusText)
                    DiagnosticFixButton(fix = fix, busy = busy, onFix = onFix)
                }
            } else {
                Text(statusText)
            }
        },
        trailingContent =
            if (!largeText && fix != null) {
                { DiagnosticFixButton(fix = fix, busy = busy, onFix = onFix) }
            } else {
                null
            },
    )
}

@Composable
private fun DiagnosticStatusBadge(outcome: WatchDiagnosticOutcome) {
    when (outcome) {
        WatchDiagnosticOutcome.PASSED -> {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        WatchDiagnosticOutcome.FIXABLE,
        WatchDiagnosticOutcome.FAILED,
        -> {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val icon =
                        if (outcome == WatchDiagnosticOutcome.FAILED) {
                            Icons.Rounded.ErrorOutline
                        } else {
                            Icons.Rounded.WarningAmber
                        }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticFixButton(
    fix: WatchDiagnosticFix,
    busy: Boolean,
    onFix: (WatchDiagnosticFix) -> Unit,
) {
    FilledTonalButton(
        onClick = { onFix(fix) },
        enabled = !busy,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(stringResource(fix.label()))
    }
}

private fun WatchDiagnosticCheck.title() =
    when (this) {
        WatchDiagnosticCheck.WATCH -> Res.string.watch_v2_check_watch
        WatchDiagnosticCheck.APP -> Res.string.watch_v2_check_app
        WatchDiagnosticCheck.PROTOCOL -> Res.string.watch_v2_check_protocol
        WatchDiagnosticCheck.ASSOCIATION -> Res.string.watch_v2_check_association
        WatchDiagnosticCheck.LINK -> Res.string.watch_v2_check_link
        WatchDiagnosticCheck.PING -> Res.string.watch_v2_check_ping
        WatchDiagnosticCheck.SYNC -> Res.string.watch_v2_check_sync
        WatchDiagnosticCheck.NOTIFICATIONS -> Res.string.watch_v2_check_notifications
    }

private fun WatchDiagnosticFix.label() =
    when (this) {
        WatchDiagnosticFix.PAIR -> Res.string.watch_v2_fix_pair
        WatchDiagnosticFix.INSTALL -> Res.string.watch_v2_fix_install
        WatchDiagnosticFix.UPDATE -> Res.string.watch_v2_fix_update
        WatchDiagnosticFix.OPEN_WATCH -> Res.string.watch_v2_fix_open
        WatchDiagnosticFix.SYNC -> Res.string.watch_v2_fix_sync
    }
