@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.handoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.handoff_backup_unavailable
import logdate.client.feature.editor.generated.resources.handoff_fetch_failed
import logdate.client.feature.editor.generated.resources.handoff_missing_title
import logdate.client.feature.editor.generated.resources.handoff_not_uploaded
import logdate.client.feature.editor.generated.resources.handoff_start_new_entry
import logdate.client.feature.editor.generated.resources.handoff_try_again
import logdate.client.feature.editor.generated.resources.handoff_waiting_body
import logdate.client.feature.editor.generated.resources.handoff_waiting_title
import org.jetbrains.compose.resources.stringResource

private val ContentMaxWidth = 480.dp
private val StatusSize = 48.dp

/**
 * What the editor shows while an entry handed off from another device is on its way, or when it
 * did not arrive. A [HandoffDraftArrival.Ready] draft opens in the editor instead, so it is not
 * drawn here.
 *
 * Waiting offers a way out, so the person is never stuck on a draft that may not come; a missing
 * draft says why, in terms of what to check, and offers to fetch again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HandoffDraftArrivalContent(
    arrival: HandoffDraftArrival,
    onRetry: () -> Unit,
    onStartNewEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                when (arrival) {
                    is HandoffDraftArrival.Missing -> {
                        Icon(
                            imageVector = Icons.Outlined.CloudOff,
                            contentDescription = null,
                            modifier = Modifier.size(StatusSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Message(
                            title = stringResource(Res.string.handoff_missing_title),
                            body = arrival.reason.message(),
                        )
                        // Large text puts the buttons on separate lines instead of wrapping their labels.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = onStartNewEntry) { Text(stringResource(Res.string.handoff_start_new_entry)) }
                            Button(onClick = onRetry) { Text(stringResource(Res.string.handoff_try_again)) }
                        }
                    }

                    else -> {
                        CircularProgressIndicator(modifier = Modifier.size(StatusSize))
                        Message(
                            title = stringResource(Res.string.handoff_waiting_title),
                            body = stringResource(Res.string.handoff_waiting_body),
                        )
                        TextButton(onClick = onStartNewEntry) { Text(stringResource(Res.string.handoff_start_new_entry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(
    title: String,
    body: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        // Untranslated text in a right-to-left language keeps its own direction and punctuation.
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HandoffDraftMissingReason.message(): String =
    when (this) {
        HandoffDraftMissingReason.BackupUnavailable -> stringResource(Res.string.handoff_backup_unavailable)
        is HandoffDraftMissingReason.FetchFailed -> stringResource(Res.string.handoff_fetch_failed, message)
        HandoffDraftMissingReason.NotUploaded -> stringResource(Res.string.handoff_not_uploaded)
    }
