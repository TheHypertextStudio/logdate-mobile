@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsRow
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_app_not_installed
import logdate.client.feature.core.generated.resources.watch_app_not_installed_description
import logdate.client.feature.core.generated.resources.watch_association_pending
import logdate.client.feature.core.generated.resources.watch_association_pending_description
import logdate.client.feature.core.generated.resources.watch_attention_not_sent
import logdate.client.feature.core.generated.resources.watch_attention_not_sent_description
import logdate.client.feature.core.generated.resources.watch_attention_set_up_again
import logdate.client.feature.core.generated.resources.watch_attention_set_up_again_description
import logdate.client.feature.core.generated.resources.watch_connected
import logdate.client.feature.core.generated.resources.watch_install_on_watch
import logdate.client.feature.core.generated.resources.watch_link_on_its_own
import logdate.client.feature.core.generated.resources.watch_link_through_phone
import logdate.client.feature.core.generated.resources.watch_loading
import logdate.client.feature.core.generated.resources.watch_needs_association
import logdate.client.feature.core.generated.resources.watch_needs_association_description
import logdate.client.feature.core.generated.resources.watch_no_paired
import logdate.client.feature.core.generated.resources.watch_no_paired_description
import logdate.client.feature.core.generated.resources.watch_out_of_range
import logdate.client.feature.core.generated.resources.watch_pair_watch
import logdate.client.feature.core.generated.resources.watch_sync_now
import logdate.client.feature.core.generated.resources.watch_sync_pending
import logdate.client.feature.core.generated.resources.watch_sync_up_to_date
import logdate.client.feature.core.generated.resources.watch_unsupported
import logdate.client.feature.core.generated.resources.watch_window_days
import org.jetbrains.compose.resources.stringResource

/** The card at the top of the Watch page: what the phone knows about its watch, and the next step. */
@Composable
internal fun WatchStatusCard(
    status: WatchStatus?,
    requestFailure: WatchRequestFailure?,
    onBeginAssociation: () -> Unit,
    onInstallOnWatch: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestSync: (() -> Unit)? = null,
) {
    MaterialContainer(modifier = modifier.testTag("watch_status_card")) {
        when (status) {
            null -> {
                LookingForWatch()
            }

            WatchStatus.Unsupported -> {
                CenteredStatus(
                    icon = Icons.Outlined.Watch,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = stringResource(Res.string.watch_unsupported),
                )
            }

            WatchStatus.NoWatch -> {
                CenteredStatus(
                    icon = Icons.Outlined.Watch,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = stringResource(Res.string.watch_no_paired),
                    description = stringResource(Res.string.watch_no_paired_description),
                ) { WatchSystemSetupButton() }
            }

            is WatchStatus.SettingUp -> {
                SettingUpStatus(status.step, onBeginAssociation)
            }

            is WatchStatus.NotInstalled -> {
                CenteredStatus(
                    icon = Icons.Default.Download,
                    iconTint = MaterialTheme.colorScheme.primary,
                    title = stringResource(Res.string.watch_app_not_installed),
                    description = stringResource(Res.string.watch_app_not_installed_description, status.watchName),
                ) {
                    Button(onClick = onInstallOnWatch) {
                        Text(stringResource(Res.string.watch_install_on_watch))
                    }
                    requestFailure?.let { WatchRequestFailureMessage(it) }
                }
            }

            is WatchStatus.Ready -> {
                ReadyStatus(status, onRequestSync)
            }

            is WatchStatus.NeedsAttention -> {
                AttentionStatus(status, onRequestSync)
            }
        }
    }
}

@Composable
private fun LookingForWatch() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(Res.string.watch_loading),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingUpStatus(
    step: WatchSetupStep,
    onBeginAssociation: () -> Unit,
) {
    when (step) {
        is WatchSetupStep.PairWithPhone -> {
            CenteredStatus(
                icon = Icons.Default.Watch,
                iconTint = MaterialTheme.colorScheme.primary,
                title = stringResource(Res.string.watch_needs_association),
                description = stringResource(Res.string.watch_needs_association_description, step.watchName),
            ) {
                Button(onClick = onBeginAssociation) {
                    Text(stringResource(Res.string.watch_pair_watch))
                }
            }
        }

        WatchSetupStep.ConfirmOnPhone -> {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Text(
                    text = stringResource(Res.string.watch_association_pending),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.watch_association_pending_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredStatus(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = iconTint,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actions()
    }
}

@Composable
private fun ReadyStatus(
    status: WatchStatus.Ready,
    onRequestSync: (() -> Unit)?,
) {
    WatchReportSurface {
        WatchNameRow(
            name = status.watchName,
            supporting = stringResource(if (status.nearby) Res.string.watch_connected else Res.string.watch_out_of_range),
            accent = if (status.nearby) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lines =
            listOfNotNull(
                status.link?.let { link ->
                    when (link) {
                        WatchLinkLine.THROUGH_PHONE -> stringResource(Res.string.watch_link_through_phone)
                        WatchLinkLine.ON_ITS_OWN -> stringResource(Res.string.watch_link_on_its_own)
                    }
                },
                status.window?.let { stringResource(Res.string.watch_window_days, it.days) },
            )
        if (lines.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        onRequestSync?.let { sync ->
            WatchSyncAction(
                statusText =
                    status.sync?.let {
                        if (it.waiting > 0) {
                            stringResource(Res.string.watch_sync_pending, it.waiting)
                        } else {
                            stringResource(Res.string.watch_sync_up_to_date)
                        }
                    },
                enabled = status.nearby,
                onClick = sync,
            )
        }
    }
}

@Composable
private fun AttentionStatus(
    status: WatchStatus.NeedsAttention,
    onRequestSync: (() -> Unit)?,
) {
    val (title, description) =
        when (val issue = status.issue) {
            WatchIssue.SetUpAgain -> {
                stringResource(Res.string.watch_attention_set_up_again) to
                    stringResource(Res.string.watch_attention_set_up_again_description)
            }

            is WatchIssue.CapturesNotSent -> {
                stringResource(Res.string.watch_attention_not_sent, issue.count) to
                    stringResource(Res.string.watch_attention_not_sent_description)
            }
        }
    WatchReportSurface {
        WatchNameRow(name = status.watchName, supporting = title, accent = MaterialTheme.colorScheme.error)
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        )
        if (status.issue is WatchIssue.CapturesNotSent && onRequestSync != null) {
            WatchSyncAction(statusText = null, enabled = status.nearby, onClick = onRequestSync)
        }
    }
}

@Composable
private fun WatchSyncAction(
    statusText: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        statusText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(Res.string.watch_sync_now))
        }
    }
}

/**
 * One surface behind the watch's name and everything it reported, so the lines under the name sit
 * on the same background as the name instead of on the card's lighter base.
 */
@Composable
private fun WatchReportSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun WatchNameRow(
    name: String,
    supporting: String,
    accent: Color,
) {
    SettingsRow(
        headlineContent = { Text(name) },
        supportingContent = {
            Text(
                text = supporting,
                color = accent,
            )
        },
        leadingContent = {
            Icon(
                Icons.Default.Watch,
                contentDescription = null,
                tint = accent,
            )
        },
    )
}
