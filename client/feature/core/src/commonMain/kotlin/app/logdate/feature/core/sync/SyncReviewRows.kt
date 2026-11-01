@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.logdate.client.sync.TextConflictVersions
import app.logdate.client.sync.metadata.SyncDeadLetterReason
import app.logdate.client.sync.metadata.SyncDeadLetterRecord
import app.logdate.client.sync.metadata.effectiveReason
import app.logdate.ui.theme.Spacing
import app.logdate.util.asTime
import app.logdate.util.formatDateLocalized
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.sync_issue_connection
import logdate.client.feature.core.generated.resources.sync_issue_failed
import logdate.client.feature.core.generated.resources.sync_issue_interrupted
import logdate.client.feature.core.generated.resources.sync_issue_missing_media
import logdate.client.feature.core.generated.resources.sync_issue_retry
import logdate.client.feature.core.generated.resources.sync_issue_sign_in
import logdate.client.feature.core.generated.resources.sync_issue_too_large
import logdate.client.feature.core.generated.resources.sync_review_attachment
import logdate.client.feature.core.generated.resources.sync_review_context
import logdate.client.feature.core.generated.resources.sync_review_draft
import logdate.client.feature.core.generated.resources.sync_review_earlier
import logdate.client.feature.core.generated.resources.sync_review_earlier_explanation
import logdate.client.feature.core.generated.resources.sync_review_entry
import logdate.client.feature.core.generated.resources.sync_review_hide_versions
import logdate.client.feature.core.generated.resources.sync_review_item
import logdate.client.feature.core.generated.resources.sync_review_journal
import logdate.client.feature.core.generated.resources.sync_review_local
import logdate.client.feature.core.generated.resources.sync_review_remote
import logdate.client.feature.core.generated.resources.sync_review_show_versions
import logdate.client.feature.core.generated.resources.sync_review_versions
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
internal fun EarlierSyncIssues(count: Int) {
    Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(pluralStringResource(Res.plurals.sync_review_earlier, count, count), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(Res.string.sync_review_earlier_explanation))
    }
}

@Composable
internal fun SavedWritingVersions(versions: TextConflictVersions) {
    var expanded by remember(versions) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(Res.string.sync_review_versions), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) Res.string.sync_review_hide_versions else Res.string.sync_review_show_versions))
        }
        if (expanded) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(Res.string.sync_review_local), style = MaterialTheme.typography.labelLarge)
                    Text(versions.local.content)
                    Text(stringResource(Res.string.sync_review_remote), style = MaterialTheme.typography.labelLarge)
                    Text(versions.remote.content)
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun SyncRetryIssueRow(
    issue: SyncDeadLetterRecord,
    onRetry: (String) -> Unit,
) {
    val kind =
        when (issue.entityType) {
            "NOTE" -> Res.string.sync_review_entry
            "JOURNAL", "JOURNAL_MERGE" -> Res.string.sync_review_journal
            "MEDIA" -> Res.string.sync_review_attachment
            "DRAFT" -> Res.string.sync_review_draft
            else -> Res.string.sync_review_item
        }
    val message =
        when (issue.effectiveReason()) {
            SyncDeadLetterReason.MISSING_FILE -> Res.string.sync_issue_missing_media
            SyncDeadLetterReason.FILE_TOO_LARGE -> Res.string.sync_issue_too_large
            SyncDeadLetterReason.SIGN_IN_REQUIRED -> Res.string.sync_issue_sign_in
            SyncDeadLetterReason.APP_CLOSED -> Res.string.sync_issue_interrupted
            SyncDeadLetterReason.NETWORK_UNAVAILABLE, SyncDeadLetterReason.SERVER_UNAVAILABLE -> Res.string.sync_issue_connection
            SyncDeadLetterReason.UNKNOWN -> Res.string.sync_issue_failed
        }
    val failed = Instant.fromEpochMilliseconds(issue.failedAt)
    Column(Modifier.padding(horizontal = Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            stringResource(
                Res.string.sync_review_context,
                stringResource(kind),
                formatDateLocalized(failed.toLocalDateTime(TimeZone.currentSystemDefault()).date),
                failed.asTime,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { onRetry(issue.id) }) { Text(stringResource(Res.string.sync_issue_retry)) }
        HorizontalDivider()
    }
}
