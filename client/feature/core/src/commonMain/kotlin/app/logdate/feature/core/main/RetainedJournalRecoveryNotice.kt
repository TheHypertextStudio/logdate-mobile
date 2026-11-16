@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.retained_journal_recovery_action
import logdate.client.feature.core.generated.resources.retained_journal_recovery_message
import logdate.client.feature.core.generated.resources.retained_journal_recovery_title
import org.jetbrains.compose.resources.stringResource

/**
 * Tells someone on Home that a restored journal is still waiting for its key, so the fresh
 * working journal underneath is not mistaken for lost entries, and reopens the recovery options.
 */
@Composable
internal fun RetainedJournalRecoveryNotice(onOpenRecoveryOptions: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(Res.string.retained_journal_recovery_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = stringResource(Res.string.retained_journal_recovery_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Button(onClick = onOpenRecoveryOptions, modifier = Modifier.padding(top = Spacing.md)) {
                Text(stringResource(Res.string.retained_journal_recovery_action))
            }
        }
    }
}
