@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.assistants

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.assistant_can_see_and_do
import logdate.client.feature.core.generated.resources.assistant_connected_on
import logdate.client.feature.core.generated.resources.assistant_disconnect
import logdate.client.feature.core.generated.resources.assistant_disconnect_note
import logdate.client.feature.core.generated.resources.assistant_disconnecting
import logdate.client.feature.core.generated.resources.assistant_no_activity
import logdate.client.feature.core.generated.resources.assistant_recent_activity
import logdate.client.feature.core.generated.resources.assistants_anytime_description
import logdate.client.feature.core.generated.resources.assistants_anytime_explanation
import logdate.client.feature.core.generated.resources.assistants_anytime_how
import logdate.client.feature.core.generated.resources.assistants_anytime_title
import logdate.client.feature.core.generated.resources.assistants_anytime_updating
import logdate.client.feature.core.generated.resources.assistants_connected
import logdate.client.feature.core.generated.resources.assistants_copy_link
import logdate.client.feature.core.generated.resources.assistants_device_only
import logdate.client.feature.core.generated.resources.assistants_hero_body
import logdate.client.feature.core.generated.resources.assistants_hero_hint
import logdate.client.feature.core.generated.resources.assistants_hero_title
import logdate.client.feature.core.generated.resources.assistants_not_used
import logdate.client.feature.core.generated.resources.assistants_title
import logdate.client.feature.core.generated.resources.assistants_used
import logdate.client.ui.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

/**
 * Settings › Assistants. [anytimeAccess] is null when the protected gateway isn't available, so its switch
 * never appears for something that can't work. [anytimeFailure] is the caller's specific reason the last
 * change to anytime access did not take effect.
 */
@Composable
fun ConnectedAssistantsContent(
    assistants: List<ConnectedAssistantSummary>,
    deviceName: String,
    anytimeAccess: Boolean?,
    onOpenAssistant: (String) -> Unit,
    onCopyConnectorLink: () -> Unit,
    onAnytimeAccessChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    anytimeChanging: Boolean = false,
    anytimeFailure: String? = null,
) {
    AssistantPage(navigationIcon = { BackButton(onBack) }) {
        item(key = "title") {
            Text(
                stringResource(Res.string.assistants_title),
                style = MaterialTheme.typography.headlineLargeEmphasized,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (assistants.isEmpty()) {
            item(key = "hero") { AssistantsHero(onCopyConnectorLink) }
        } else {
            item(key = "connected") { AssistantSectionLabel(stringResource(Res.string.assistants_connected)) }
            assistants.forEach { assistant ->
                item(key = assistant.id) { ConnectedAssistantCard(assistant, onClick = { onOpenAssistant(assistant.id) }) }
            }
        }
        item(key = "availability") {
            AssistantsAvailability(deviceName, anytimeAccess, anytimeChanging, anytimeFailure, onAnytimeAccessChange)
        }
        if (assistants.isNotEmpty()) {
            item(key = "hero") { AssistantsHero(onCopyConnectorLink) }
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(UiRes.string.common_back))
    }
}

@Composable
private fun AssistantsHero(onCopyConnectorLink: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                stringResource(Res.string.assistants_hero_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(Res.string.assistants_hero_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Button(
                onClick = onCopyConnectorLink,
                contentPadding = ButtonDefaults.MediumContentPadding,
                modifier = Modifier.padding(top = Spacing.sm).heightIn(min = ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.MediumIconSize))
                Text(
                    stringResource(Res.string.assistants_copy_link),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = ButtonDefaults.MediumIconSpacing),
                )
            }
            Text(
                stringResource(Res.string.assistants_hero_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ConnectedAssistantCard(
    assistant: ConnectedAssistantSummary,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AssistantAvatar(assistant.name, 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(assistant.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    AssistantCapabilityIcons(assistant.capabilities)
                    Text(
                        assistant.lastUsedLabel?.let { stringResource(Res.string.assistants_used, it) }
                            ?: stringResource(Res.string.assistants_not_used),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AssistantsAvailability(
    deviceName: String,
    anytimeAccess: Boolean?,
    changing: Boolean,
    failure: String?,
    onAnytimeAccessChange: (Boolean) -> Unit,
) {
    if (anytimeAccess == null) {
        AssistantFact(Icons.Rounded.Laptop, stringResource(Res.string.assistants_device_only, deviceName), Modifier.padding(Spacing.sm))
        return
    }
    var explained by rememberSaveable { mutableStateOf(false) }
    AssistantToggleTile(
        icon = Icons.Rounded.Bedtime,
        title = stringResource(Res.string.assistants_anytime_title, deviceName),
        subtitle =
            stringResource(if (changing) Res.string.assistants_anytime_updating else Res.string.assistants_anytime_description),
        checked = anytimeAccess,
        enabled = !changing,
        onCheckedChange = onAnytimeAccessChange,
    ) {
        Column {
            if (failure != null && !changing) {
                Text(
                    failure,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
            TextButton(onClick = { explained = !explained }) { Text(stringResource(Res.string.assistants_anytime_how)) }
            AnimatedVisibility(explained) {
                Text(
                    stringResource(Res.string.assistants_anytime_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.sm),
                )
            }
        }
    }
}

/**
 * One connected assistant: what it has done recently, what it can see and do, and the way to disconnect.
 * [offered] is what the assistant originally asked for; access can be narrowed or widened within it.
 * [failure] is the caller's specific reason the last change or disconnect did not complete.
 */
@Composable
fun ConnectedAssistantContent(
    assistant: ConnectedAssistantSummary,
    offered: Set<AssistantCapability>,
    journals: List<AssistantJournalOption>,
    access: AssistantAccessSelection,
    activity: List<AssistantActivity>,
    disconnecting: Boolean,
    onAccessChange: (AssistantAccessSelection) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
    failure: String? = null,
) {
    AssistantPage(navigationIcon = { BackButton(onBack) }) {
        item(key = "header") {
            Column(
                Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AssistantAvatar(assistant.name, 96.dp)
                Text(
                    assistant.name,
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    listOfNotNull(
                        stringResource(Res.string.assistant_connected_on, assistant.connectedLabel),
                        assistant.lastUsedLabel?.let { stringResource(Res.string.assistants_used, it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "activity-label") { AssistantSectionLabel(stringResource(Res.string.assistant_recent_activity)) }
        if (activity.isEmpty()) {
            item(key = "no-activity") {
                Text(
                    stringResource(Res.string.assistant_no_activity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item(key = "activity") {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        activity.forEach { entry -> AssistantActivityRow(entry) }
                    }
                }
            }
        }
        item(key = "access-label") { AssistantSectionLabel(stringResource(Res.string.assistant_can_see_and_do)) }
        assistantAccessTiles(offered, journals, access, enabled = !disconnecting, onAccessChange, showGroupLabels = false)
        item(key = "disconnect") {
            Column(Modifier.padding(top = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (failure != null && !disconnecting) {
                    Text(failure, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                FilledTonalButton(
                    onClick = onDisconnect,
                    enabled = !disconnecting,
                    colors =
                        ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    contentPadding = ButtonDefaults.MediumContentPadding,
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(
                        stringResource(if (disconnecting) Res.string.assistant_disconnecting else Res.string.assistant_disconnect),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    stringResource(Res.string.assistant_disconnect_note, assistant.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AssistantActivityRow(entry: AssistantActivity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        AssistantIconBadge(
            icon = entry.capability.icon,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Column(Modifier.weight(1f)) {
            Text(entry.description, style = MaterialTheme.typography.bodyLarge)
            Text(entry.timeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
