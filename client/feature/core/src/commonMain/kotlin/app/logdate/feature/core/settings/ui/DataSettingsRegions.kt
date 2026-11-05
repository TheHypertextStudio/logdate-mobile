@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.data_state_account_failed
import logdate.client.feature.core.generated.resources.data_state_account_loading
import logdate.client.feature.core.generated.resources.data_state_quota_failed
import logdate.client.feature.core.generated.resources.data_state_quota_loading
import logdate.client.feature.core.generated.resources.data_state_retry
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AccountLoadingRegion(
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (failed) {
            Text(stringResource(Res.string.data_state_account_failed), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.data_state_retry)) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(stringResource(Res.string.data_state_account_loading))
            }
        }
    }
}

@Composable
internal fun QuotaSettingsRegion(
    quotaUsage: StorageQuotaUi,
    availability: QuotaAvailability?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (availability == null || availability.quota != null) QuotaUsageBlock(quotaUsage)
        when (availability) {
            null, is QuotaAvailability.Available -> Unit
            is QuotaAvailability.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(stringResource(Res.string.data_state_quota_loading))
                }
            }
            is QuotaAvailability.Unavailable -> {
                Text(stringResource(Res.string.data_state_quota_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.data_state_retry)) }
            }
        }
    }
}
