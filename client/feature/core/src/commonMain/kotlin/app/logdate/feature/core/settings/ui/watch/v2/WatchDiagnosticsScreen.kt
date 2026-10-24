@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.logdate.feature.core.settings.ui.watch.WatchSystemSetupButton
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_diagnostics_prerequisite
import logdate.client.feature.core.generated.resources.watch_v2_check_again
import logdate.client.feature.core.generated.resources.watch_v2_check_app
import logdate.client.feature.core.generated.resources.watch_v2_check_association
import logdate.client.feature.core.generated.resources.watch_v2_check_error
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
import logdate.client.feature.core.generated.resources.watch_v2_fix_install
import logdate.client.feature.core.generated.resources.watch_v2_fix_open
import logdate.client.feature.core.generated.resources.watch_v2_fix_pair
import logdate.client.feature.core.generated.resources.watch_v2_fix_sync
import logdate.client.feature.core.generated.resources.watch_v2_fix_update
import logdate.client.feature.core.generated.resources.watch_v2_notifications_fix
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

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
    SettingsScaffold(title = stringResource(Res.string.watch_v2_diagnostics), onBack = onBack) {
        if (state != WatchDiagnosticsState.NeedsWatch) {
            item {
                TextButton(onClick = onCheck, enabled = !busy, modifier = Modifier.padding(horizontal = Spacing.lg)) {
                    Text(stringResource(if (busy) Res.string.watch_v2_check_running else Res.string.watch_v2_check_again))
                }
            }
        }
        when (val current = state) {
            WatchDiagnosticsState.Checking -> {
                Unit
            }

            WatchDiagnosticsState.NeedsWatch -> {
                item {
                    MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(stringResource(Res.string.watch_diagnostics_prerequisite))
                            WatchSystemSetupButton()
                            TextButton(onClick = onCheck, enabled = !busy) { Text(stringResource(Res.string.watch_v2_check_again)) }
                        }
                    }
                }
            }

            WatchDiagnosticsState.Failed -> {
                item { Text(stringResource(Res.string.watch_v2_check_error), Modifier.padding(Spacing.lg)) }
            }

            is WatchDiagnosticsState.Ready -> {
                item {
                    MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                        current.steps.forEach { step ->
                            SimpleSettingsItem(
                                title = stringResource(step.check.title()),
                                description =
                                    stringResource(
                                        when (step.outcome) {
                                            WatchDiagnosticOutcome.PASSED -> Res.string.watch_v2_check_passed
                                            WatchDiagnosticOutcome.FIXABLE -> Res.string.watch_v2_check_fixable
                                            WatchDiagnosticOutcome.FAILED -> Res.string.watch_v2_check_failed
                                        },
                                    ),
                                onClick = step.fix?.let { fix -> { onFix(fix) } },
                                enabled = !busy,
                                action = {
                                    step.fix?.let { fix ->
                                        TextButton(onClick = { onFix(fix) }, enabled = !busy) { Text(stringResource(fix.label())) }
                                    }
                                },
                            )
                            if (step.check == WatchDiagnosticCheck.NOTIFICATIONS && step.outcome != WatchDiagnosticOutcome.PASSED) {
                                Text(stringResource(Res.string.watch_v2_notifications_fix), Modifier.padding(Spacing.md))
                            }
                        }
                    }
                }
            }
        }
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
