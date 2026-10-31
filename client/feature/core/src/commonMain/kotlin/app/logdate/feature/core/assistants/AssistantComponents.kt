@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package app.logdate.feature.core.assistants

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.assistant_capability_capture
import logdate.client.feature.core.generated.resources.assistant_capability_capture_description
import logdate.client.feature.core.generated.resources.assistant_capability_entries
import logdate.client.feature.core.generated.resources.assistant_capability_entries_description
import logdate.client.feature.core.generated.resources.assistant_capability_people
import logdate.client.feature.core.generated.resources.assistant_capability_people_description
import logdate.client.feature.core.generated.resources.assistant_capability_places
import logdate.client.feature.core.generated.resources.assistant_capability_places_description
import logdate.client.feature.core.generated.resources.assistant_connect_do
import logdate.client.feature.core.generated.resources.assistant_connect_journal_gone
import logdate.client.feature.core.generated.resources.assistant_connect_remove_journal
import logdate.client.feature.core.generated.resources.assistant_connect_see
import logdate.client.feature.core.generated.resources.assistant_journals_more
import logdate.client.feature.core.generated.resources.assistant_unfiled
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Journals named in a summary before the rest are counted. */
private const val NAMED_JOURNALS = 2

private val ContentMaxWidth = 600.dp

private val seeCapabilities = listOf(AssistantCapability.READ_MEMORIES, AssistantCapability.READ_PLACES, AssistantCapability.READ_PEOPLE)
private val doCapabilities = listOf(AssistantCapability.START_CAPTURE)

internal val AssistantCapability.icon: ImageVector
    get() =
        when (this) {
            AssistantCapability.READ_MEMORIES -> Icons.AutoMirrored.Rounded.MenuBook
            AssistantCapability.READ_PLACES -> Icons.Rounded.Place
            AssistantCapability.READ_PEOPLE -> Icons.Rounded.People
            AssistantCapability.START_CAPTURE -> Icons.Rounded.Mic
        }

private val AssistantCapability.title: StringResource
    get() =
        when (this) {
            AssistantCapability.READ_MEMORIES -> Res.string.assistant_capability_entries
            AssistantCapability.READ_PLACES -> Res.string.assistant_capability_places
            AssistantCapability.READ_PEOPLE -> Res.string.assistant_capability_people
            AssistantCapability.START_CAPTURE -> Res.string.assistant_capability_capture
        }

private val AssistantCapability.description: StringResource
    get() =
        when (this) {
            AssistantCapability.READ_MEMORIES -> Res.string.assistant_capability_entries_description
            AssistantCapability.READ_PLACES -> Res.string.assistant_capability_places_description
            AssistantCapability.READ_PEOPLE -> Res.string.assistant_capability_people_description
            AssistantCapability.START_CAPTURE -> Res.string.assistant_capability_capture_description
        }

/** Full-height page for assistant surfaces: one top bar action, content capped to a readable width. */
@Composable
internal fun AssistantPage(
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = navigationIcon,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                content = content,
            )
        }
    }
}

/** The assistant's initial inside an expressive shape, so each assistant reads as a distinct presence. */
@Composable
internal fun AssistantAvatar(
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(size).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            style = if (size >= 64.dp) MaterialTheme.typography.displaySmallEmphasized else MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
internal fun AssistantIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Box(
        modifier = modifier.size(40.dp).clip(MaterialShapes.Cookie4Sided.toShape()).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

/** A short fact with an icon; used instead of paragraphs. */
@Composable
internal fun AssistantFact(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun AssistantSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = Spacing.sm).semantics { heading() },
    )
}

/**
 * The "See" and "Do" switches for the capabilities an assistant asked for. Entries expand into a journal
 * picker so the person can narrow what is shared without leaving the page.
 */
internal fun LazyListScope.assistantAccessTiles(
    offered: Set<AssistantCapability>,
    journals: List<AssistantJournalOption>,
    selection: AssistantAccessSelection,
    enabled: Boolean,
    onSelectionChange: (AssistantAccessSelection) -> Unit,
    showGroupLabels: Boolean = true,
) {
    val availableIds = journals.map { it.id }.toSet()
    val groups = listOf(Res.string.assistant_connect_see to seeCapabilities, Res.string.assistant_connect_do to doCapabilities)
    groups.forEach { (label, group) ->
        val shown = group.filter { it in offered }
        if (shown.isEmpty()) return@forEach
        if (showGroupLabels) item(key = label.key) { AssistantSectionLabel(stringResource(label)) }
        shown.forEach { capability ->
            item(key = capability.name) {
                val checked = capability in selection.capabilities
                AssistantToggleTile(
                    icon = capability.icon,
                    title = stringResource(capability.title),
                    subtitle =
                        if (capability == AssistantCapability.READ_MEMORIES && checked) {
                            entriesSummary(journals, selection)
                        } else {
                            stringResource(capability.description)
                        },
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = { onSelectionChange(selection.withCapability(capability, it, availableIds)) },
                ) {
                    if (capability == AssistantCapability.READ_MEMORIES) {
                        AnimatedVisibility(checked) {
                            AssistantJournalPicker(journals, selection, enabled, onSelectionChange)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun entriesSummary(
    journals: List<AssistantJournalOption>,
    selection: AssistantAccessSelection,
): String {
    val names =
        journals.filter { it.id in selection.journalIds }.map { it.title } +
            listOfNotNull(stringResource(Res.string.assistant_unfiled).takeIf { selection.includeUnfiled })
    if (names.size <= NAMED_JOURNALS + 1) return names.joinToString(", ")
    return stringResource(Res.string.assistant_journals_more, names.take(NAMED_JOURNALS).joinToString(", "), names.size - NAMED_JOURNALS)
}

@Composable
private fun AssistantJournalPicker(
    journals: List<AssistantJournalOption>,
    selection: AssistantAccessSelection,
    enabled: Boolean,
    onSelectionChange: (AssistantAccessSelection) -> Unit,
) {
    val availableIds = journals.map { it.id }.toSet()
    Column(Modifier.padding(top = Spacing.sm)) {
        journals.forEach { journal ->
            AssistantCheckRow(journal.title, journal.id in selection.journalIds, enabled) { checked ->
                val ids = if (checked) selection.journalIds + journal.id else selection.journalIds - journal.id
                onSelectionChange(selection.withEntries(ids, selection.includeUnfiled))
            }
        }
        AssistantCheckRow(stringResource(Res.string.assistant_unfiled), selection.includeUnfiled, enabled) { checked ->
            onSelectionChange(selection.withEntries(selection.journalIds, checked))
        }
        if (!availableIds.containsAll(selection.journalIds)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.assistant_connect_journal_gone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        onSelectionChange(selection.withEntries(selection.journalIds intersect availableIds, selection.includeUnfiled))
                    },
                    enabled = enabled,
                ) { Text(stringResource(Res.string.assistant_connect_remove_journal)) }
            }
        }
    }
}

@Composable
private fun AssistantCheckRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
                .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(horizontal = Spacing.sm))
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A tonal tile whose whole surface toggles one capability; [extra] sits under the row when present. */
@Composable
internal fun AssistantToggleTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AssistantIconBadge(
                    icon = icon,
                    container = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    content = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    thumbContent =
                        if (checked) {
                            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                        } else {
                            null
                        },
                )
            }
            extra()
        }
    }
}

/** Small capability glyphs used to summarize an assistant's access in one line. */
@Composable
internal fun AssistantCapabilityIcons(
    capabilities: Set<AssistantCapability>,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        AssistantCapability.entries.filter { it in capabilities }.forEach { capability ->
            Icon(capability.icon, contentDescription = stringResource(capability.title), modifier = Modifier.size(18.dp))
        }
    }
}
