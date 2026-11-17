@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.people.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.journals.JournalNote
import app.logdate.shared.model.Event
import app.logdate.shared.model.Person
import app.logdate.ui.common.MaterialContainer
import app.logdate.ui.common.SettingsNavigationItem
import app.logdate.ui.common.SettingsSection
import app.logdate.ui.common.SimpleSettingsItem
import app.logdate.ui.notes.NoteThumbnail
import app.logdate.ui.notes.noteWords
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateShort
import app.logdate.util.toReadableDateTimeShort
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.person_aliases_label
import logdate.client.feature.core.generated.resources.person_detail_linked_entries
import logdate.client.feature.core.generated.resources.person_detail_linked_events
import logdate.client.feature.core.generated.resources.person_detail_load_failed
import logdate.client.feature.core.generated.resources.person_detail_no_linked_entries
import logdate.client.feature.core.generated.resources.person_detail_no_linked_events
import logdate.client.feature.core.generated.resources.person_remove_action
import logdate.client.ui.generated.resources.common_loading
import org.jetbrains.compose.resources.stringResource
import kotlin.uuid.Uuid
import logdate.client.ui.generated.resources.Res as UiRes

private const val ENTRY_PREVIEW_LENGTH = 120

/**
 * Stands in for the page while the person is loading or when they could not be loaded.
 */
@Composable
internal fun PersonStatus(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            if (isLoading) {
                stringResource(UiRes.string.common_loading)
            } else {
                stringResource(Res.string.person_detail_load_failed)
            },
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

/**
 * Who the person is: name, relationship, how they came to be in the list, other names and notes.
 */
@Composable
internal fun PersonIdentity(
    person: Person,
    modifier: Modifier = Modifier,
) {
    MaterialContainer(modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = person.name,
                style = MaterialTheme.typography.headlineSmall,
            )
            person.relationshipLabel?.let { relationship ->
                Text(
                    text = relationship,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(person.origin.labelResource()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (person.aliases.isNotEmpty()) {
                Text(
                    text = stringResource(Res.string.person_aliases_label, person.aliases.joinToString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            person.notes?.let { notes ->
                Text(
                    text = notes,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Every entry linked to the person, each with a short preview and its date. An entry opens when
 * [onOpenEntry] is supplied and is listed without an action otherwise.
 */
@Composable
internal fun PersonEntries(
    entries: List<JournalNote>,
    onOpenEntry: ((Uuid) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsSection(
        title = stringResource(Res.string.person_detail_linked_entries),
        modifier = modifier,
    ) {
        if (entries.isEmpty()) {
            SimpleSettingsItem(
                title = stringResource(Res.string.person_detail_no_linked_entries),
                description = null,
                action = {},
            )
            return@SettingsSection
        }
        entries.forEach { entry ->
            PersonEntryRow(entry = entry, onOpenEntry = onOpenEntry)
        }
    }
}

@Composable
private fun PersonEntryRow(
    entry: JournalNote,
    onOpenEntry: ((Uuid) -> Unit)?,
) {
    // The entry's own words lead; a photo, video, recording or page of ink with no words leads
    // with its date. The thumbnail shows the entry itself either way.
    val words = entry.noteWords()?.take(ENTRY_PREVIEW_LENGTH)
    val date = entry.creationTimestamp.toReadableDateShort()
    val title = words ?: date
    val description = if (words != null) date else entry.creationTimestamp.toReadableDateTimeShort()
    val thumbnail: @Composable () -> Unit = { NoteThumbnail(entry, size = 40.dp, shape = RoundedCornerShape(8.dp)) }
    if (onOpenEntry == null) {
        SimpleSettingsItem(
            title = title,
            description = description,
            leadingContent = thumbnail,
            action = {},
        )
        return
    }
    SettingsNavigationItem(
        title = title,
        description = description,
        icon = thumbnail,
        onClick = { onOpenEntry(entry.uid) },
    )
}

/**
 * Every event linked to the person. Callers show this only while Events is part of the release.
 */
@Composable
internal fun PersonEvents(
    events: List<Event>,
    modifier: Modifier = Modifier,
) {
    SettingsSection(
        title = stringResource(Res.string.person_detail_linked_events),
        modifier = modifier,
    ) {
        if (events.isEmpty()) {
            SimpleSettingsItem(
                title = stringResource(Res.string.person_detail_no_linked_events),
                description = null,
                action = {},
            )
            return@SettingsSection
        }
        events.forEach { event ->
            SimpleSettingsItem(
                title = event.title,
                description = null,
                action = {},
            )
        }
    }
}

/**
 * Asks to remove the person from the list. The caller confirms before anything is removed.
 */
@Composable
internal fun RemovePersonButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(stringResource(Res.string.person_remove_action))
    }
}
