@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.watch.WatchSystemSetupButton
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepSecondaryButton
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
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_all_good
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_all_passed_summary
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_attention_section
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_issues_found
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_issues_summary
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_passing_section
import logdate.client.feature.core.generated.resources.watch_v2_fix_install
import logdate.client.feature.core.generated.resources.watch_v2_fix_open
import logdate.client.feature.core.generated.resources.watch_v2_fix_pair
import logdate.client.feature.core.generated.resources.watch_v2_fix_sync
import logdate.client.feature.core.generated.resources.watch_v2_fix_update
import logdate.client.feature.core.generated.resources.watch_v2_notifications_fix
import org.jetbrains.compose.resources.pluralStringResource
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
        when (val current = state) {
            WatchDiagnosticsState.Checking -> {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = stringResource(Res.string.watch_v2_check_running),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }

            WatchDiagnosticsState.NeedsWatch -> {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.WatchOff,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(Res.string.watch_diagnostics_prerequisite),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            )
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
            }

            WatchDiagnosticsState.Failed -> {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Row(
                                modifier = Modifier.padding(Spacing.lg),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    text = stringResource(Res.string.watch_v2_check_error),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                        StepPrimaryButton(
                            text = stringResource(if (busy) Res.string.watch_v2_check_running else Res.string.watch_v2_check_again),
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
                item {
                    DiagnosticsSummaryCard(
                        total = current.steps.size,
                        attentionCount = attentionSteps.size,
                        busy = busy,
                        onCheck = onCheck,
                    )
                }
                if (attentionSteps.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = stringResource(Res.string.watch_v2_diagnostics_attention_section),
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        ) {
                            attentionSteps.forEach { step ->
                                WatchDiagnosticRow(step = step, busy = busy, onFix = onFix)
                            }
                        }
                    }
                    if (attentionSteps.any { it.check == WatchDiagnosticCheck.NOTIFICATIONS }) {
                        item {
                            DiagnosticsNotificationsTip(Modifier.padding(horizontal = Spacing.lg))
                        }
                    }
                }
                if (passedSteps.isNotEmpty()) {
                    item {
                        SettingsSection(
                            title = stringResource(Res.string.watch_v2_diagnostics_passing_section),
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        ) {
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

@Composable
private fun DiagnosticsSummaryCard(
    total: Int,
    attentionCount: Int,
    busy: Boolean,
    onCheck: () -> Unit,
) {
    val hasIssues = attentionCount > 0
    val largeText = LocalDensity.current.fontScale >= 1.6f
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(24.dp),
        color = if (hasIssues) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (hasIssues) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (largeText) {
                DiagnosticsSummaryBadge(hasIssues = hasIssues, busy = busy)
                DiagnosticsSummaryCopy(total = total, attentionCount = attentionCount)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DiagnosticsSummaryBadge(hasIssues = hasIssues, busy = busy)
                    DiagnosticsSummaryCopy(total = total, attentionCount = attentionCount)
                }
            }
            FilledTonalButton(
                onClick = onCheck,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                if (!busy) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(stringResource(if (busy) Res.string.watch_v2_check_running else Res.string.watch_v2_check_again))
            }
        }
    }
}

@Composable
private fun DiagnosticsSummaryBadge(
    hasIssues: Boolean,
    busy: Boolean,
) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
        )
    } else {
        Icon(
            imageVector = if (hasIssues) Icons.Rounded.WarningAmber else Icons.Rounded.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun DiagnosticsSummaryCopy(
    total: Int,
    attentionCount: Int,
) {
    val hasIssues = attentionCount > 0
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text =
                if (hasIssues) {
                    pluralStringResource(Res.plurals.watch_v2_diagnostics_issues_found, attentionCount, attentionCount)
                } else {
                    stringResource(Res.string.watch_v2_diagnostics_all_good)
                },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                if (hasIssues) {
                    stringResource(Res.string.watch_v2_diagnostics_issues_summary)
                } else {
                    stringResource(Res.string.watch_v2_diagnostics_all_passed_summary, total)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WatchDiagnosticRow(
    step: WatchDiagnosticStep,
    busy: Boolean,
    onFix: (WatchDiagnosticFix) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.6f
    val statusIcon =
        when (step.outcome) {
            WatchDiagnosticOutcome.PASSED -> Icons.Rounded.CheckCircle
            WatchDiagnosticOutcome.FIXABLE -> Icons.Rounded.WarningAmber
            WatchDiagnosticOutcome.FAILED -> Icons.Rounded.ErrorOutline
        }
    val statusTint =
        when (step.outcome) {
            WatchDiagnosticOutcome.PASSED -> MaterialTheme.colorScheme.primary
            WatchDiagnosticOutcome.FIXABLE -> MaterialTheme.colorScheme.error
            WatchDiagnosticOutcome.FAILED -> MaterialTheme.colorScheme.error
        }
    val statusText =
        stringResource(
            when (step.outcome) {
                WatchDiagnosticOutcome.PASSED -> Res.string.watch_v2_check_passed
                WatchDiagnosticOutcome.FIXABLE -> Res.string.watch_v2_check_fixable
                WatchDiagnosticOutcome.FAILED -> Res.string.watch_v2_check_failed
            },
        )
    val fix = step.fix
    SettingsRow(
        enabled = !busy,
        leadingContent = {
            Icon(
                imageVector = statusIcon,
                contentDescription = null,
                tint = statusTint,
            )
        },
        headlineContent = { Text(stringResource(step.check.title())) },
        supportingContent = {
            val subtitleColor =
                if (step.outcome == WatchDiagnosticOutcome.PASSED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            if (largeText && fix != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(statusText, color = subtitleColor)
                    DiagnosticFixButton(fix = fix, busy = busy, onFix = onFix)
                }
            } else {
                Text(statusText, color = subtitleColor)
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

@Composable
private fun DiagnosticsNotificationsTip(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(Res.string.watch_v2_notifications_fix),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
