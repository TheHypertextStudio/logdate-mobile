@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.ToggleSettingsItem
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.privacy_auth_failed
import logdate.client.feature.core.generated.resources.privacy_auth_unavailable
import logdate.client.feature.core.generated.resources.privacy_authenticating
import logdate.client.feature.core.generated.resources.privacy_enrollment_required
import logdate.client.feature.core.generated.resources.privacy_preference_loading
import logdate.client.feature.core.generated.resources.privacy_preference_read_failed
import logdate.client.feature.core.generated.resources.privacy_preference_refreshing
import logdate.client.feature.core.generated.resources.privacy_preference_save_failed
import logdate.client.feature.core.generated.resources.privacy_preference_saving
import logdate.client.feature.core.generated.resources.privacy_security_update_required
import logdate.client.ui.generated.resources.common_retry
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun PrivacyPreferenceItem(
    title: String,
    description: String,
    state: PrivacyPreferenceState,
    onCheckedChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    when (state) {
        PrivacyPreferenceState.Loading ->
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(stringResource(Res.string.privacy_preference_loading)) },
                trailingContent = { PrivacyPreferenceProgress() },
            )
        PrivacyPreferenceState.Unavailable ->
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = { Text(stringResource(Res.string.privacy_preference_read_failed)) },
                trailingContent = {
                    TextButton(onClick = onRetry) { Text(stringResource(UiRes.string.common_retry)) }
                },
            )
        is PrivacyPreferenceState.Available -> {
            val pending =
                state.operation == PrivacyPreferenceOperation.Authenticating ||
                    state.operation is PrivacyPreferenceOperation.Saving
            ToggleSettingsItem(
                title = title,
                description = description,
                checked = state.enabled,
                onCheckedChange = onCheckedChange,
                enabled = !pending,
            )
            val failedOperation = state.operation as? PrivacyPreferenceOperation.Failed
            val status =
                when (val operation = state.operation) {
                    PrivacyPreferenceOperation.Authenticating -> stringResource(Res.string.privacy_authenticating)
                    is PrivacyPreferenceOperation.Saving -> stringResource(Res.string.privacy_preference_saving)
                    is PrivacyPreferenceOperation.Failed ->
                        stringResource(
                            when (operation.reason) {
                                PrivacyPreferenceFailure.SAVE -> Res.string.privacy_preference_save_failed
                                PrivacyPreferenceFailure.AUTHENTICATION -> Res.string.privacy_auth_failed
                                PrivacyPreferenceFailure.AUTHENTICATION_UNAVAILABLE -> Res.string.privacy_auth_unavailable
                                PrivacyPreferenceFailure.ENROLLMENT_REQUIRED -> Res.string.privacy_enrollment_required
                                PrivacyPreferenceFailure.SECURITY_UPDATE_REQUIRED -> Res.string.privacy_security_update_required
                            },
                        )
                    PrivacyPreferenceOperation.Idle ->
                        when {
                            state.isRefreshing -> stringResource(Res.string.privacy_preference_refreshing)
                            state.refreshFailed -> stringResource(Res.string.privacy_preference_read_failed)
                            else -> null
                        }
                }
            if (status != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (pending || state.isRefreshing) PrivacyPreferenceProgress()
                    Text(
                        text = status,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (failedOperation != null || state.refreshFailed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    if (failedOperation != null || (state.refreshFailed && !pending)) {
                        TextButton(onClick = onRetry) { Text(stringResource(UiRes.string.common_retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivacyPreferenceProgress() {
    val reduceMotion by rememberSystemReduceMotion()
    if (reduceMotion) {
        Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(20.dp))
    } else {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
