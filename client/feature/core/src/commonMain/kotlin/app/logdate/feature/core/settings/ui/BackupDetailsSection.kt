@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.client.sync.SyncStatus
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.backup_overview_conflicts
import logdate.client.feature.core.generated.resources.backup_overview_pending
import logdate.client.feature.core.generated.resources.sync_review_earlier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BackupDetailsSection(
    syncStatus: SyncStatus?,
    archiveStatus: CloudArchiveStatus,
    onReviewIssues: () -> Unit,
    modifier: Modifier = Modifier,
    reviewableIssueCount: Int = 0,
) {
    val state = backupOverviewState(syncStatus, archiveStatus, reviewableIssueCount)
    if (!state.hasDetails) return
    MaterialContainer(modifier = modifier) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (state.pendingChanges > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(Res.string.backup_overview_pending),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(state.pendingChanges.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
            val currentIssues = (syncStatus?.conflictCount ?: 0) + reviewableIssueCount
            if (currentIssues > 0) {
                TextButton(onClick = onReviewIssues) {
                    Text(pluralStringResource(Res.plurals.backup_overview_conflicts, currentIssues, currentIssues))
                }
            }
            val earlierIssues = syncStatus?.earlierConflictCount ?: 0
            if (earlierIssues > 0) {
                TextButton(onClick = onReviewIssues) {
                    Text(pluralStringResource(Res.plurals.sync_review_earlier, earlierIssues, earlierIssues))
                }
            }
        }
    }
}
