@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.phone_activity_checking
import logdate.client.feature.core.generated.resources.phone_activity_connecting
import logdate.client.feature.core.generated.resources.phone_activity_continue
import logdate.client.feature.core.generated.resources.phone_activity_denied
import logdate.client.feature.core.generated.resources.phone_activity_link
import logdate.client.feature.core.generated.resources.phone_activity_linked
import logdate.client.feature.core.generated.resources.phone_activity_not_now
import logdate.client.feature.core.generated.resources.phone_activity_offer_description
import logdate.client.feature.core.generated.resources.phone_activity_offer_title
import logdate.client.feature.core.generated.resources.phone_activity_open_settings
import logdate.client.feature.core.generated.resources.phone_activity_permission_needed
import logdate.client.feature.core.generated.resources.phone_activity_provider_failed
import logdate.client.feature.core.generated.resources.phone_activity_save_failed
import logdate.client.feature.core.generated.resources.phone_activity_unavailable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Optional setup content. The host owns placement and decides where continuing goes. */
@Composable
fun PhoneActivityOnboardingOffer(
    link: PhoneActivityLink,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linked = link.state == PhoneActivityLinkState.Linked
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(if (linked) Res.string.phone_activity_linked else Res.string.phone_activity_offer_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(Res.string.phone_activity_offer_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!linked) {
                link.state.detail()?.let { detail ->
                    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            val action =
                when (link.state) {
                    PhoneActivityLinkState.Linked -> Res.string.phone_activity_continue to onContinue
                    PhoneActivityLinkState.Denied -> Res.string.phone_activity_open_settings to link.onOpenSettings
                    PhoneActivityLinkState.Unavailable -> null
                    else -> Res.string.phone_activity_link to link.onLink
                }
            action?.let { (label, perform) ->
                Button(onClick = perform, enabled = !link.state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(label))
                }
            }
            if (!linked) {
                TextButton(
                    onClick = onContinue,
                    enabled = link.state != PhoneActivityLinkState.Requesting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.phone_activity_not_now)) }
            }
        }
    }
}

internal val PhoneActivityLinkState.busy: Boolean
    get() =
        this == PhoneActivityLinkState.Checking || this == PhoneActivityLinkState.Connecting ||
            this == PhoneActivityLinkState.Requesting

internal fun PhoneActivityLinkState.detail(): StringResource? =
    when (this) {
        PhoneActivityLinkState.Checking -> Res.string.phone_activity_checking
        PhoneActivityLinkState.Connecting -> Res.string.phone_activity_connecting
        PhoneActivityLinkState.NeedsPermission -> Res.string.phone_activity_permission_needed
        PhoneActivityLinkState.Denied -> Res.string.phone_activity_denied
        PhoneActivityLinkState.Unavailable -> Res.string.phone_activity_unavailable
        PhoneActivityLinkState.ProviderFailed -> Res.string.phone_activity_provider_failed
        PhoneActivityLinkState.LinkWriteFailed, PhoneActivityLinkState.UnlinkWriteFailed -> Res.string.phone_activity_save_failed
        PhoneActivityLinkState.Off, PhoneActivityLinkState.Linked, PhoneActivityLinkState.Requesting -> null
    }
