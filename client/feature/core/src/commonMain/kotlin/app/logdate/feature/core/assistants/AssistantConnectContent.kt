@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.assistants

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.assistant_connect_activity
import logdate.client.feature.core.generated.resources.assistant_connect_allow
import logdate.client.feature.core.generated.resources.assistant_connect_allowing
import logdate.client.feature.core.generated.resources.assistant_connect_anytime
import logdate.client.feature.core.generated.resources.assistant_connect_check
import logdate.client.feature.core.generated.resources.assistant_connect_choose
import logdate.client.feature.core.generated.resources.assistant_connect_deny
import logdate.client.feature.core.generated.resources.assistant_connect_device_only
import logdate.client.feature.core.generated.resources.assistant_connect_never_writes
import logdate.client.feature.core.generated.resources.assistant_connect_retention
import logdate.client.feature.core.generated.resources.assistant_connect_subtitle
import logdate.client.feature.core.generated.resources.assistant_connect_title
import logdate.client.ui.generated.resources.common_close
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Shown when an assistant asks to connect: the person turns on what it can see and do, then allows or
 * declines. [anytimeAccess] reflects the Settings switch and only changes the availability fact.
 * [failure] is the caller's specific reason the last attempt to allow did not succeed.
 */
@Composable
fun AssistantConnectContent(
    request: AssistantConnectRequest,
    journals: List<AssistantJournalOption>,
    selection: AssistantAccessSelection,
    anytimeAccess: Boolean,
    allowing: Boolean,
    onSelectionChange: (AssistantAccessSelection) -> Unit,
    onAllow: (AssistantAccessGrant) -> Unit,
    onDeny: () -> Unit,
    failure: String? = null,
) {
    val grant = selection.grant(request.requestedCapabilities, journals.map { it.id }.toSet())
    val name = request.assistantName
    AssistantPage(
        navigationIcon = {
            IconButton(onClick = onDeny) { Icon(Icons.Rounded.Close, contentDescription = stringResource(UiRes.string.common_close)) }
        },
        bottomBar = {
            AssistantConnectActions(
                message = failure ?: stringResource(Res.string.assistant_connect_choose).takeIf { grant == null },
                isFailure = failure != null,
                allowing = allowing,
                canAllow = grant != null,
                onAllow = { grant?.let(onAllow) },
                onDeny = onDeny,
            )
        },
    ) {
        item(key = "header") { AssistantConnectHeader(name) }
        assistantAccessTiles(request.requestedCapabilities, journals, selection, enabled = !allowing, onSelectionChange)
        item(key = "facts") {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    AssistantFact(Icons.Rounded.Lock, stringResource(Res.string.assistant_connect_never_writes))
                    AssistantFact(Icons.Rounded.History, stringResource(Res.string.assistant_connect_activity))
                    AssistantFact(Icons.Rounded.ContentCopy, stringResource(Res.string.assistant_connect_retention))
                    if (anytimeAccess) {
                        AssistantFact(Icons.Rounded.Bedtime, stringResource(Res.string.assistant_connect_anytime))
                    } else {
                        AssistantFact(Icons.Rounded.Laptop, stringResource(Res.string.assistant_connect_device_only, request.deviceName))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantConnectHeader(name: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AssistantAvatar(name, 64.dp)
            Icon(Icons.Rounded.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(
                modifier =
                    Modifier
                        .size(64.dp)
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("L", style = MaterialTheme.typography.displaySmallEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(
            stringResource(Res.string.assistant_connect_title, name),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.assistant_connect_subtitle), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(Res.string.assistant_connect_check, name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AssistantConnectActions(
    message: String?,
    isFailure: Boolean,
    allowing: Boolean,
    canAllow: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (message != null && !allowing) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                    OutlinedButton(
                        onClick = onDeny,
                        shape = ButtonGroupDefaults.connectedLeadingButtonShapes().shape,
                        contentPadding = ButtonDefaults.MediumContentPadding,
                        modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight),
                    ) { Text(stringResource(Res.string.assistant_connect_deny), style = MaterialTheme.typography.titleMedium) }
                    Button(
                        onClick = onAllow,
                        enabled = canAllow && !allowing,
                        shape = ButtonGroupDefaults.connectedTrailingButtonShapes().shape,
                        contentPadding = ButtonDefaults.MediumContentPadding,
                        modifier = Modifier.weight(1f).heightIn(min = ButtonDefaults.MediumContainerHeight),
                    ) {
                        Text(
                            stringResource(if (allowing) Res.string.assistant_connect_allowing else Res.string.assistant_connect_allow),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}
