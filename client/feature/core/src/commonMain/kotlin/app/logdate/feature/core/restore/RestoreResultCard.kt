@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.restore

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.logdate.feature.core.common.DataTransferArchiveSummary
import app.logdate.feature.core.common.DataTransferResultHeader
import app.logdate.feature.core.common.OperationFailureCard
import app.logdate.feature.core.common.archiveDetails
import app.logdate.feature.core.common.dataTransferCountsSummary
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateShort
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.restore_complete_description
import logdate.client.feature.core.generated.resources.restore_complete_title
import logdate.client.feature.core.generated.resources.restore_failed_title
import logdate.client.feature.core.generated.resources.restore_interrupted_description
import logdate.client.feature.core.generated.resources.restore_interrupted_title
import logdate.client.feature.core.generated.resources.restore_media_recovery_progress
import logdate.client.feature.core.generated.resources.restore_missing_originals
import logdate.client.feature.core.generated.resources.restore_missing_previews
import logdate.client.feature.core.generated.resources.restore_partial_description
import logdate.client.feature.core.generated.resources.restore_partial_title
import logdate.client.feature.core.generated.resources.restore_paused_description
import logdate.client.feature.core.generated.resources.restore_paused_title
import logdate.client.feature.core.generated.resources.restore_recover_media
import logdate.client.feature.core.generated.resources.restore_recovering_description
import logdate.client.feature.core.generated.resources.restore_recovering_media
import logdate.client.feature.core.generated.resources.restore_resume_import
import logdate.client.feature.core.generated.resources.restore_resuming_description
import logdate.client.feature.core.generated.resources.restore_resuming_title
import logdate.client.feature.core.generated.resources.restore_unreadable_description
import logdate.client.feature.core.generated.resources.restore_unreadable_title
import logdate.client.ui.generated.resources.common_done
import logdate.client.ui.generated.resources.common_retry
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun RestoreSuccessCard(
    summary: RestoreSummary,
    onDismiss: () -> Unit,
    onRecoverMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val incomplete =
        summary.needsAnotherArchive || summary.metadataIncomplete || summary.unavailableOriginals + summary.unavailablePreviews > 0
    val title =
        when {
            summary.metadataIncomplete && summary.recoveringMedia -> Res.string.restore_resuming_title
            summary.metadataIncomplete && summary.canResume -> Res.string.restore_paused_title
            summary.metadataIncomplete -> Res.string.restore_interrupted_title
            summary.needsAnotherArchive -> Res.string.restore_unreadable_title
            summary.recoveringMedia -> Res.string.restore_recovering_media
            incomplete -> Res.string.restore_partial_title
            else -> Res.string.restore_complete_title
        }
    val description =
        when {
            summary.metadataIncomplete && summary.recoveringMedia -> Res.string.restore_resuming_description
            summary.metadataIncomplete && summary.canResume -> Res.string.restore_paused_description
            summary.metadataIncomplete -> Res.string.restore_interrupted_description
            summary.needsAnotherArchive -> Res.string.restore_unreadable_description
            summary.recoveringMedia -> Res.string.restore_recovering_description
            incomplete -> Res.string.restore_partial_description
            else -> Res.string.restore_complete_description
        }
    val statusIcon =
        when {
            summary.metadataIncomplete && summary.recoveringMedia -> PlatformIcons.history()
            summary.metadataIncomplete && summary.canResume -> PlatformIcons.pause()
            summary.metadataIncomplete || summary.recoveringMedia -> PlatformIcons.history()
            summary.needsAnotherArchive || incomplete -> PlatformIcons.info()
            else -> PlatformIcons.checkCircle()
        }
    val statusTint =
        if (incomplete || summary.recoveringMedia) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        }
    val fileDetails = archiveDetails(summary.exportDate?.toReadableDateShort(), summary.appVersion)
    val needsAction =
        incomplete &&
            (!summary.recoveringMedia || (summary.needsAnotherArchive && !summary.metadataIncomplete)) &&
            summary.recoveryId != null

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        DataTransferResultHeader(
            title = stringResource(title),
            description = stringResource(description),
            icon = statusIcon,
            iconTint = statusTint,
        )
        if (needsAction) {
            Button(onClick = onRecoverMedia, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (summary.canResume) Res.string.restore_resume_import else Res.string.restore_recover_media))
            }
        }
        if (summary.recoveringMedia && summary.mediaRecoveryTotal > 0) {
            Text(
                stringResource(
                    Res.string.restore_media_recovery_progress,
                    summary.mediaRecoveryChecked.coerceAtMost(summary.mediaRecoveryTotal),
                    summary.mediaRecoveryTotal,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary.unavailableOriginals > 0 || summary.unavailablePreviews > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (summary.unavailableOriginals > 0) {
                    Text(
                        stringResource(Res.string.restore_missing_originals, summary.unavailableOriginals),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.unavailablePreviews > 0) {
                    Text(
                        stringResource(Res.string.restore_missing_previews, summary.unavailablePreviews),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DataTransferArchiveSummary(
            fileName = summary.source,
            fileDetails = fileDetails,
            countsSummary =
                dataTransferCountsSummary(
                    summary.journalsImported,
                    summary.notesImported,
                    summary.draftsImported,
                    summary.mediaImported,
                ),
        )
        if (needsAction) {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(UiRes.string.common_done))
            }
        } else {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(UiRes.string.common_done))
            }
        }
    }
}

@Composable
internal fun RestoreFailureCard(
    reason: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
) {
    OperationFailureCard(
        title = stringResource(Res.string.restore_failed_title),
        reason = reason,
        onRetry = onRetry,
        onDismiss = onDismiss,
        modifier = modifier,
        actionLabel = actionLabel ?: stringResource(UiRes.string.common_retry),
    )
}
