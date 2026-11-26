@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.ui.watch.v2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WatchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.settings.ui.watch.WatchSystemSetupButton
import app.logdate.feature.core.settings.ui.watch.messageResource
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.common.SettingsScaffold
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.step.StepPrimaryButton
import app.logdate.ui.step.StepSecondaryButton
import app.logdate.ui.theme.Spacing
import app.logdate.ui.watch.rememberPhonePlayStoreLauncher
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_companion_versions
import logdate.client.feature.core.generated.resources.watch_diagnostics_prerequisite
import logdate.client.feature.core.generated.resources.watch_setup_complete_action
import logdate.client.feature.core.generated.resources.watch_setup_pair_title
import logdate.client.feature.core.generated.resources.watch_v2_action_failed
import logdate.client.feature.core.generated.resources.watch_v2_action_sent_install
import logdate.client.feature.core.generated.resources.watch_v2_action_sent_open
import logdate.client.feature.core.generated.resources.watch_v2_action_sent_pair
import logdate.client.feature.core.generated.resources.watch_v2_action_sent_sync
import logdate.client.feature.core.generated.resources.watch_v2_action_sent_update_phone
import logdate.client.feature.core.generated.resources.watch_v2_action_sent_update_watch
import logdate.client.feature.core.generated.resources.watch_v2_check_again
import logdate.client.feature.core.generated.resources.watch_v2_check_app_version
import logdate.client.feature.core.generated.resources.watch_v2_check_connection
import logdate.client.feature.core.generated.resources.watch_v2_check_error
import logdate.client.feature.core.generated.resources.watch_v2_check_error_title
import logdate.client.feature.core.generated.resources.watch_v2_check_notifications
import logdate.client.feature.core.generated.resources.watch_v2_check_running
import logdate.client.feature.core.generated.resources.watch_v2_check_sync
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_missing
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_ok_diff
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_ok_installed
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_ok_same
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_unknown
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_update_phone
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_update_phone_versions
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_update_watch
import logdate.client.feature.core.generated.resources.watch_v2_detail_app_version_update_watch_versions
import logdate.client.feature.core.generated.resources.watch_v2_detail_connection_missing_app
import logdate.client.feature.core.generated.resources.watch_v2_detail_connection_needs_pair
import logdate.client.feature.core.generated.resources.watch_v2_detail_connection_ok
import logdate.client.feature.core.generated.resources.watch_v2_detail_connection_unresponsive
import logdate.client.feature.core.generated.resources.watch_v2_detail_notifications_ok
import logdate.client.feature.core.generated.resources.watch_v2_detail_notifications_waiting
import logdate.client.feature.core.generated.resources.watch_v2_detail_sync_failed
import logdate.client.feature.core.generated.resources.watch_v2_detail_sync_ok
import logdate.client.feature.core.generated.resources.watch_v2_detail_sync_pending
import logdate.client.feature.core.generated.resources.watch_v2_detail_sync_waiting
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_all_good
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_all_passed_summary
import logdate.client.feature.core.generated.resources.watch_v2_diagnostics_passed_count
import logdate.client.feature.core.generated.resources.watch_v2_fix_install
import logdate.client.feature.core.generated.resources.watch_v2_fix_open
import logdate.client.feature.core.generated.resources.watch_v2_fix_pair
import logdate.client.feature.core.generated.resources.watch_v2_fix_sync
import logdate.client.feature.core.generated.resources.watch_v2_fix_update_phone
import logdate.client.feature.core.generated.resources.watch_v2_fix_update_watch
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
    val phoneStoreLauncher = rememberPhonePlayStoreLauncher()
    WatchDiagnosticsContent(
        state = state,
        busy = busy,
        onCheck = model::check,
        onFix = { fix ->
            if (fix == WatchDiagnosticFix.UPDATE_PHONE) {
                model.reportLocalAction(fix, phoneStoreLauncher?.invoke() == true)
            } else {
                model.fix(fix)
            }
        },
        onBack = onBack,
    )
}

@Composable
fun WatchDiagnosticsContent(
    state: WatchDiagnosticsState,
    busy: Boolean,
    onCheck: () -> Unit,
    onFix: (WatchDiagnosticFix) -> Unit,
    onBack: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val reduceMotion by rememberSystemReduceMotion()
    val checkWithHaptic = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onCheck()
    }
    val fixWithHaptic: (WatchDiagnosticFix) -> Unit = { fix ->
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onFix(fix)
    }
    when (val current = state) {
        WatchDiagnosticsState.Checking -> {
            WatchFlowScaffold(
                title = stringResource(Res.string.watch_v2_check_running),
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
                picture = { SearchingPicture(it) },
            ) {}
        }

        WatchDiagnosticsState.NeedsWatch -> {
            WatchFlowScaffold(
                title = stringResource(Res.string.watch_setup_pair_title),
                description = stringResource(Res.string.watch_diagnostics_prerequisite),
                onBack = onBack,
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

        WatchDiagnosticsState.Failed -> {
            WatchFlowScaffold(
                title = stringResource(Res.string.watch_v2_check_error_title),
                description = stringResource(Res.string.watch_v2_check_error),
                onBack = onBack,
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

        is WatchDiagnosticsState.Ready -> {
            val actionSteps = current.steps.filter { it.outcome == WatchDiagnosticOutcome.NEEDS_ACTION }
            val waitingSteps = current.steps.filter { it.outcome == WatchDiagnosticOutcome.WAITING }
            val passedSteps = current.steps.filter { it.outcome == WatchDiagnosticOutcome.PASSED }
            if (actionSteps.isEmpty() && waitingSteps.isEmpty()) {
                val summary =
                    if (current.watchVersion != null && current.phoneVersion != null) {
                        val versionLine = stringResource(Res.string.watch_companion_versions, current.watchVersion, current.phoneVersion)
                        "${stringResource(Res.string.watch_v2_diagnostics_all_passed_summary, current.steps.size)}\n$versionLine"
                    } else {
                        stringResource(Res.string.watch_v2_diagnostics_all_passed_summary, current.steps.size)
                    }
                WatchFlowScaffold(
                    title = stringResource(Res.string.watch_v2_diagnostics_all_good),
                    description = summary,
                    onBack = onBack,
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
            } else {
                var showPassed by rememberSaveable { mutableStateOf(false) }
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
                            IconButton(onClick = checkWithHaptic) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.watch_v2_check_again),
                                )
                            }
                        }
                    },
                ) {
                    if (current.watchName != null || (current.watchVersion != null && current.phoneVersion != null)) {
                        item {
                            DiagnosticDeviceHeader(
                                watchName = current.watchName,
                                watchVersion = current.watchVersion,
                                phoneVersion = current.phoneVersion,
                            )
                        }
                    }
                    item {
                        AnimatedVisibility(
                            visible = current.feedback != null,
                            enter = if (reduceMotion) fadeIn() else fadeIn() + expandVertically(),
                            exit = if (reduceMotion) fadeOut() else fadeOut() + shrinkVertically(),
                        ) {
                            current.feedback?.let { feedback ->
                                DiagnosticFeedbackBanner(feedback)
                            }
                        }
                    }
                    if (actionSteps.isNotEmpty()) {
                        item {
                            MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                                actionSteps.forEach { step ->
                                    WatchDiagnosticRow(step = step, busy = busy, onFix = fixWithHaptic)
                                }
                            }
                        }
                    }
                    if (waitingSteps.isNotEmpty()) {
                        item {
                            MaterialContainer(Modifier.padding(horizontal = Spacing.lg)) {
                                waitingSteps.forEach { step ->
                                    WatchDiagnosticRow(step = step, busy = busy, onFix = fixWithHaptic)
                                }
                            }
                        }
                    }
                    if (passedSteps.isNotEmpty()) {
                        item {
                            val chevronRotation by animateFloatAsState(
                                targetValue = if (showPassed) 180f else 0f,
                                label = "diagnostics_passed_chevron",
                            )
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
                                            imageVector = Icons.Rounded.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.rotate(chevronRotation),
                                        )
                                    },
                                    modifier =
                                        Modifier.clickable(role = Role.Button) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showPassed = !showPassed
                                        },
                                )
                                AnimatedVisibility(
                                    visible = showPassed,
                                    enter = if (reduceMotion) fadeIn() else fadeIn() + expandVertically(),
                                    exit = if (reduceMotion) fadeOut() else fadeOut() + shrinkVertically(),
                                ) {
                                    Column {
                                        passedSteps.forEach { step ->
                                            WatchDiagnosticRow(step = step, busy = busy, onFix = fixWithHaptic)
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
}

@Composable
private fun DiagnosticDeviceHeader(
    watchName: String?,
    watchVersion: String?,
    phoneVersion: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Watch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                watchName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (watchVersion != null && phoneVersion != null) {
                    Text(
                        text = stringResource(Res.string.watch_companion_versions, watchVersion, phoneVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticFeedbackBanner(feedback: WatchDiagnosticFeedback) {
    val (container, content, message) =
        when (feedback) {
            is WatchDiagnosticFeedback.Sent -> {
                Triple(
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    stringResource(feedback.fix.sentResource()),
                )
            }

            is WatchDiagnosticFeedback.Failed -> {
                Triple(
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                    stringResource(feedback.reason?.messageResource() ?: Res.string.watch_v2_action_failed),
                )
            }
        }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
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
    val statusText = step.detail.message()
    val fix = step.fix
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stackActionBelow = largeText || maxWidth < 480.dp
        SettingsRow(
            enabled = !busy && step.outcome != WatchDiagnosticOutcome.WAITING,
            leadingContent = { DiagnosticStatusBadge(step.outcome) },
            headlineContent = { Text(stringResource(step.check.title())) },
            supportingContent = {
                if (stackActionBelow && fix != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(statusText)
                        DiagnosticFixButton(fix = fix, busy = busy, onFix = onFix)
                    }
                } else {
                    Text(statusText)
                }
            },
            trailingContent =
                if (!stackActionBelow && fix != null) {
                    { DiagnosticFixButton(fix = fix, busy = busy, onFix = onFix) }
                } else {
                    null
                },
        )
    }
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

        WatchDiagnosticOutcome.WAITING -> {
            Icon(
                imageVector = Icons.Rounded.HourglassEmpty,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        WatchDiagnosticOutcome.NEEDS_ACTION -> {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
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

@Composable
private fun WatchDiagnosticDetail.message(): String =
    when (this) {
        is WatchDiagnosticDetail.VersionSame -> {
            stringResource(Res.string.watch_v2_detail_app_version_ok_same, version)
        }

        is WatchDiagnosticDetail.VersionCompatibleDifferent -> {
            stringResource(Res.string.watch_v2_detail_app_version_ok_diff, watchVersion, phoneVersion)
        }

        is WatchDiagnosticDetail.AppInstalled -> {
            stringResource(Res.string.watch_v2_detail_app_version_ok_installed, watchName)
        }

        is WatchDiagnosticDetail.AppMissing -> {
            stringResource(Res.string.watch_v2_detail_app_version_missing, watchName)
        }

        is WatchDiagnosticDetail.AppUnconfirmed -> {
            stringResource(Res.string.watch_v2_detail_app_version_unknown, watchName)
        }

        is WatchDiagnosticDetail.WatchNeedsUpdate -> {
            if (watchVersion != null && phoneVersion != null) {
                stringResource(Res.string.watch_v2_detail_app_version_update_watch_versions, watchVersion, phoneVersion)
            } else {
                stringResource(Res.string.watch_v2_detail_app_version_update_watch)
            }
        }

        is WatchDiagnosticDetail.PhoneNeedsUpdate -> {
            if (watchVersion != null && phoneVersion != null) {
                stringResource(Res.string.watch_v2_detail_app_version_update_phone_versions, watchVersion, phoneVersion)
            } else {
                stringResource(Res.string.watch_v2_detail_app_version_update_phone)
            }
        }

        is WatchDiagnosticDetail.ConnectionReady -> {
            stringResource(Res.string.watch_v2_detail_connection_ok, watchName)
        }

        is WatchDiagnosticDetail.ConnectionNeedsLink -> {
            stringResource(Res.string.watch_v2_detail_connection_needs_pair, watchName)
        }

        is WatchDiagnosticDetail.ConnectionUnresponsive -> {
            stringResource(Res.string.watch_v2_detail_connection_unresponsive, watchName)
        }

        is WatchDiagnosticDetail.ConnectionWaitingOnApp -> {
            stringResource(Res.string.watch_v2_detail_connection_missing_app, watchName)
        }

        WatchDiagnosticDetail.SyncUpToDate -> {
            stringResource(Res.string.watch_v2_detail_sync_ok)
        }

        is WatchDiagnosticDetail.SyncPending -> {
            pluralStringResource(Res.plurals.watch_v2_detail_sync_pending, waiting, waiting)
        }

        is WatchDiagnosticDetail.SyncFailed -> {
            pluralStringResource(Res.plurals.watch_v2_detail_sync_failed, failed, failed)
        }

        WatchDiagnosticDetail.SyncWaitingOnConnection -> {
            stringResource(Res.string.watch_v2_detail_sync_waiting)
        }

        WatchDiagnosticDetail.NotificationsAllowed -> {
            stringResource(Res.string.watch_v2_detail_notifications_ok)
        }

        WatchDiagnosticDetail.NotificationsDisabled -> {
            stringResource(Res.string.watch_v2_notifications_fix)
        }

        WatchDiagnosticDetail.NotificationsWaitingOnConnection -> {
            stringResource(Res.string.watch_v2_detail_notifications_waiting)
        }
    }

private fun WatchDiagnosticCheck.title() =
    when (this) {
        WatchDiagnosticCheck.APP_VERSION -> Res.string.watch_v2_check_app_version
        WatchDiagnosticCheck.CONNECTION -> Res.string.watch_v2_check_connection
        WatchDiagnosticCheck.SYNC -> Res.string.watch_v2_check_sync
        WatchDiagnosticCheck.NOTIFICATIONS -> Res.string.watch_v2_check_notifications
    }

private fun WatchDiagnosticFix.label() =
    when (this) {
        WatchDiagnosticFix.PAIR -> Res.string.watch_v2_fix_pair
        WatchDiagnosticFix.INSTALL -> Res.string.watch_v2_fix_install
        WatchDiagnosticFix.UPDATE_WATCH -> Res.string.watch_v2_fix_update_watch
        WatchDiagnosticFix.UPDATE_PHONE -> Res.string.watch_v2_fix_update_phone
        WatchDiagnosticFix.OPEN_WATCH -> Res.string.watch_v2_fix_open
        WatchDiagnosticFix.SYNC -> Res.string.watch_v2_fix_sync
    }

private fun WatchDiagnosticFix.sentResource() =
    when (this) {
        WatchDiagnosticFix.PAIR -> Res.string.watch_v2_action_sent_pair
        WatchDiagnosticFix.INSTALL -> Res.string.watch_v2_action_sent_install
        WatchDiagnosticFix.UPDATE_WATCH -> Res.string.watch_v2_action_sent_update_watch
        WatchDiagnosticFix.UPDATE_PHONE -> Res.string.watch_v2_action_sent_update_phone
        WatchDiagnosticFix.OPEN_WATCH -> Res.string.watch_v2_action_sent_open
        WatchDiagnosticFix.SYNC -> Res.string.watch_v2_action_sent_sync
    }
